package com.pixellog.recording

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GyroflowTelemetryLogger captures high-rate uncalibrated gyroscope and accelerometer
 * data for post-stabilization tools like Gyroflow (https://gyroflow.xyz/).
 *
 * Emits standardized Gyroflow CSV 1.3 (.gcsv) sidecars alongside video files:
 * - Uses TYPE_GYROSCOPE_UNCALIBRATED to avoid Kalman zero-rate bias step jumps
 * - Microsecond relative timestamps unified with Camera2 SENSOR_TIMESTAMP (CLOCK_BOOTTIME)
 * - Zero-allocation binary disk buffer during recording to eliminate GC pressure
 * - Encodes rolling shutter readout skew from HAL for scanline de-jello correction
 */
class GyroflowTelemetryLogger(private val context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "GyroflowLogger"
        const val GCSV_VERSION = "1.3"
        // 1 g = 9.80665 m/s^2; scale factor to convert m/s^2 to g
        const val ASCALE_TO_G = 0.10197162f
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Prefer uncalibrated IMU sensors to prevent dynamic bias adjustment step jumps
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private var tempBinaryFile: File? = null
    private var binaryOutputStream: DataOutputStream? = null

    private val isLogging = AtomicBoolean(false)
    private var baselineTimestampNs = -1L
    private var rollingShutterSkewMs = 22.45f
    private var frameRateFps = 30.0
    private var lensName = "1x"
    private var targetVideoFile: File? = null

    // Latest accelerometer readings to pair with gyro samples
    @Volatile private var latestAx = 0f
    @Volatile private var latestAy = 0f
    @Volatile private var latestAz = 0f

    /**
     * Starts high-frequency IMU logging to an asynchronous binary buffer.
     */
    fun startLogging(
        videoFile: File,
        readoutSkewNs: Long,
        fps: Double,
        currentLensLabel: String = "1x",
        startBaselineNs: Long = -1L
    ) {
        if (isLogging.getAndSet(true)) return

        targetVideoFile = videoFile
        frameRateFps = fps
        lensName = currentLensLabel
        rollingShutterSkewMs = if (readoutSkewNs > 0L) {
            readoutSkewNs / 1_000_000.0f
        } else {
            22.45f // Fallback for 4080x3064 4:3 open-gate
        }
        baselineTimestampNs = startBaselineNs

        tempBinaryFile = File(videoFile.parentFile, "${videoFile.nameWithoutExtension}.imu.tmp")
        binaryOutputStream = DataOutputStream(BufferedOutputStream(FileOutputStream(tempBinaryFile), 65536))

        workerThread = HandlerThread("PixelLog-GyroLogger", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply {
            start()
            workerHandler = Handler(looper)
        }

        workerHandler?.let { handler ->
            gyroSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler)
            }
            accelSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST, handler)
            }
        }

        Log.i(TAG, "Started Gyroflow IMU telemetry logging (skew: ${rollingShutterSkewMs}ms, fps: $fps) -> ${tempBinaryFile?.name}")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isLogging.get()) return
        val stream = binaryOutputStream ?: return

        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
            Sensor.TYPE_GYROSCOPE -> {
                val gx = event.values[0]
                val gy = event.values[1]
                val gz = event.values[2]

                if (baselineTimestampNs <= 0L) {
                    baselineTimestampNs = event.timestamp
                }
                val timeOffsetUs = (event.timestamp - baselineTimestampNs) / 1000L

                try {
                    // Binary layout per sample (32 bytes):
                    // Long timeOffsetUs (8 bytes) + 6 Floats (24 bytes)
                    stream.writeLong(timeOffsetUs)
                    stream.writeFloat(gx)
                    stream.writeFloat(gy)
                    stream.writeFloat(gz)
                    stream.writeFloat(latestAx)
                    stream.writeFloat(latestAy)
                    stream.writeFloat(latestAz)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write IMU binary sample: ${e.message}")
                }
            }
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
            Sensor.TYPE_ACCELEROMETER -> {
                latestAx = event.values[0]
                latestAy = event.values[1]
                latestAz = event.values[2]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * Stops logging, finalizes the .gcsv sidecar file, and cleans up temporary buffers.
     * Returns the finalized .gcsv File or null on failure.
     */
    fun stopLogging(): File? {
        if (!isLogging.getAndSet(false)) return null

        try {
            sensorManager.unregisterListener(this)
        } catch (_: Exception) {}

        workerThread?.quitSafely()
        workerThread = null
        workerHandler = null

        try {
            binaryOutputStream?.flush()
            binaryOutputStream?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing binary IMU stream: ${e.message}")
        }
        binaryOutputStream = null

        val video = targetVideoFile ?: return null
        val binaryTmp = tempBinaryFile ?: return null
        val gcsvFile = File(video.parentFile, "${video.nameWithoutExtension}.gcsv")

        return try {
            if (binaryTmp.exists() && binaryTmp.length() > 0) {
                finalizeGcsv(binaryTmp, gcsvFile, video.name)
                Log.i(TAG, "Successfully emitted Gyroflow sidecar: ${gcsvFile.name} (${gcsvFile.length() / 1024} KB)")
                gcsvFile
            } else {
                Log.w(TAG, "Binary IMU buffer was empty, skipping .gcsv export")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finalizing GCSV file", e)
            null
        } finally {
            binaryTmp.delete()
        }
    }

    /**
     * Converts the binary stream into the standardized Gyroflow CSV 1.3 format.
     */
    private fun finalizeGcsv(binaryTmp: File, destination: File, videoFileName: String) {
        val deviceModel = Build.MODEL.replace(" ", "_")
        val orientation = "YxZ" // Standard Android rear camera in landscape

        destination.bufferedWriter().use { writer ->
            writer.write("GYROFLOW IMU LOG\n")
            writer.write("version,$GCSV_VERSION\n")
            writer.write("id,${deviceModel}_${lensName}\n")
            writer.write("orientation,$orientation\n")
            writer.write("tscale,0.000001\n") // Microseconds to seconds
            writer.write("gscale,1.0\n")      // rad/s (Android native)
            writer.write("ascale,$ASCALE_TO_G\n") // m/s^2 to g
            writer.write("frame_readout_time,${String.format(Locale.US, "%.2f", rollingShutterSkewMs)}\n")
            writer.write("frame_readout_direction,0\n") // Top to bottom
            writer.write("camera_fps,${String.format(Locale.US, "%.3f", frameRateFps)}\n")
            writer.write("videofilename,$videoFileName\n")
            writer.write("lensprofile,Google/${deviceModel}_${lensName}_4x3.json\n")
            writer.write("note,Recorded with PixelLog\n")
            writer.write("t,gx,gy,gz,ax,ay,az\n")

            DataInputStream(FileInputStream(binaryTmp).buffered()).use { dis ->
                while (dis.available() > 0) {
                    val t = dis.readLong()
                    val gx = dis.readFloat()
                    val gy = dis.readFloat()
                    val gz = dis.readFloat()
                    val ax = dis.readFloat()
                    val ay = dis.readFloat()
                    val az = dis.readFloat()

                    writer.write(String.format(Locale.US, "%d,%.6f,%.6f,%.6f,%.4f,%.4f,%.4f\n", t, gx, gy, gz, ax, ay, az))
                }
            }
        }
    }
}
