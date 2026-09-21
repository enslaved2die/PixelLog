package com.pixellog.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.pixellog.nativebridge.PixelLogEngine
import java.util.concurrent.Executor

/**
 * CameraController coordinates the Camera2 HAL state machine for the Pixel 11 Pro:
 * - 4:3 Open-Gate RAW_SENSOR session
 * - Auto Mode: Continuous AE/AF with frozen ISP tone curves and suppressed sharpening
 * - Full Manual Mode: 180° shutter lock, manual ISO, focus diopters, Kelvin/Tint WB
 * - Real-time per-frame dynamic black level streaming to the GPU pipeline
 */
class CameraController(
    private val context: Context,
    private val engine: PixelLogEngine
) {
    companion object {
        private const val TAG = "CameraController"
        const val TARGET_FPS = 30
        const val FRAME_DURATION_NS = 1_000_000_000L / TARGET_FPS // 33,333,333 ns
        const val SHUTTER_180_NS = FRAME_DURATION_NS / 2L          // 16,666,666 ns
        const val BINNED_RES_WIDTH = 2032
        const val BINNED_RES_HEIGHT = 1532
    }

    enum class FramerateConfig(
        val label: String,
        val fps: Double,
        val frameDurationNs: Long,
        val shutter180Ns: Long,
        val fpsRange: Range<Int>,
        val isBinned: Boolean
    ) {
        FPS_24("24", 24.0, 41_666_667L, 20_833_333L, Range(24, 24), false),
        FPS_25("25", 25.0, 40_000_000L, 20_000_000L, Range(24, 30), false),
        FPS_29_97("29.97", 29.970029, 33_366_667L, 16_683_333L, Range(30, 30), false),
        FPS_30("30", 30.0, 33_333_333L, 16_666_666L, Range(30, 30), false),
        FPS_50("50", 50.0, 20_000_000L, 10_000_000L, Range(15, 60), false),
        FPS_60("60", 60.0, 16_666_666L, 8_333_333L, Range(30, 60), false);

        val targetFps: Double get() = fps
        val binned: Boolean get() = isBinned
    }

    enum class ControlMode {
        AUTO,
        FULL_MANUAL
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraCharacteristics: CameraCharacteristics? = null

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraExecutor: Executor? = null

    // State Variables
    var currentMode: ControlMode = ControlMode.AUTO
        private set

    var currentFramerate: FramerateConfig = FramerateConfig.FPS_30
        private set

    var targetIso: Int = 100
    var targetShutterNs: Long = SHUTTER_180_NS
    var targetFocusDiopter: Float = 0.0f // 0.0 = Optical infinity
    var targetKelvin: Int = 5600
    var targetTint: Int = 0
    var isTorchEnabled: Boolean = false
        set(value) {
            field = value
            applyStateAndRepeat()
        }

    var fullSensorWidth: Int = 4080
        private set
    var fullSensorHeight: Int = 3064
        private set

    var activeWidth: Int = 4080
    var activeHeight: Int = 3072
    var bayerPattern: Int = 0 // 0: RGGB

    // Callback for UI updates (e.g. dynamic black level, exposure feedback)
    var onFrameMetadataListener: ((dynamicBlackLevel: FloatArray, whiteLevel: Float) -> Unit)? = null

    fun start() {
        cameraThread = HandlerThread("PixelLog-Camera-Thread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
        cameraExecutor = Executor { command -> cameraHandler?.post(command) }
    }

    fun stop() {
        closeCamera()
        cameraThread?.quitSafely()
        try {
            cameraThread?.join(2000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        cameraThread = null
        cameraHandler = null
    }

    @SuppressLint("MissingPermission")
    fun openCamera(preferredWidth: Int = 4080, preferredHeight: Int = 3064) {
        val cameraId = findPrimaryWideCameraId()
            ?: throw IllegalStateException("Pixel 11 Pro primary wide camera not found")

        val chars = cameraManager.getCameraCharacteristics(cameraId)
        cameraCharacteristics = chars

        // Discover Bayer filter arrangement
        val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        bayerPattern = cfa

        // Dynamically discover supported RAW_SENSOR output sizes from HAL
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
        if (!rawSizes.isNullOrEmpty()) {
            val matchedSize = rawSizes.find { it.width == preferredWidth && it.height == preferredHeight }
                ?: rawSizes.filter { s ->
                    val ratio = s.width.toFloat() / s.height.toFloat()
                    Math.abs(ratio - (4.0f / 3.0f)) < 0.05f
                }.maxByOrNull { it.width * it.height }
                ?: rawSizes.maxByOrNull { it.width * it.height }
                ?: rawSizes.first()

            fullSensorWidth = matchedSize.width
            fullSensorHeight = matchedSize.height
            if (currentFramerate.isBinned) {
                activeWidth = BINNED_RES_WIDTH
                activeHeight = BINNED_RES_HEIGHT
            } else {
                activeWidth = matchedSize.width
                activeHeight = matchedSize.height
            }
            Log.i(TAG, "Selected Camera2 RAW_SENSOR size: ${activeWidth}x${activeHeight} (from: ${rawSizes.joinToString { "${it.width}x${it.height}" }})")
        } else {
            fullSensorWidth = preferredWidth
            fullSensorHeight = preferredHeight
            if (currentFramerate.isBinned) {
                activeWidth = BINNED_RES_WIDTH
                activeHeight = BINNED_RES_HEIGHT
            } else {
                activeWidth = preferredWidth
                activeHeight = preferredHeight
            }
            Log.w(TAG, "No RAW_SENSOR sizes reported by HAL, using ${activeWidth}x${activeHeight}")
        }

        Log.i(TAG, "Opening primary sensor: ID $cameraId, Open-Gate $activeWidth x $activeHeight, CFA $bayerPattern")

        // Initialize native GPU pipeline with open-gate resolution and CFA pattern
        engine.initialize(activeWidth, activeHeight, bayerPattern)

        cameraManager.openCamera(cameraId, cameraExecutor!!, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createOpenGateSession()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Camera device error: $error")
                camera.close()
                cameraDevice = null
            }
        })
    }

    private fun findPrimaryWideCameraId(): String? {
        if ("0" in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics("0")
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            if (facing == CameraCharacteristics.LENS_FACING_BACK &&
                caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
                return "0"
            }
        }
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val hasRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)

            if (facing == CameraCharacteristics.LENS_FACING_BACK && hasRaw) {
                return id
            }
        }
        return cameraManager.cameraIdList.firstOrNull()
    }

    private fun createOpenGateSession() {
        val camera = cameraDevice ?: return
        val rawSurface = engine.getCameraSurface()
            ?: throw IllegalStateException("PixelLogEngine native camera surface is null")

        val rawOutput = OutputConfiguration(rawSurface)
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(rawOutput),
            cameraExecutor!!,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    applyStateAndRepeat()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Failed to configure open-gate capture session")
                }
            }
        )

        camera.createCaptureSession(sessionConfig)
    }

    fun setMode(mode: ControlMode) {
        currentMode = mode
        applyStateAndRepeat()
    }

    fun setFramerate(config: FramerateConfig) {
        val targetWidth = if (config.isBinned) BINNED_RES_WIDTH else fullSensorWidth
        val targetHeight = if (config.isBinned) BINNED_RES_HEIGHT else fullSensorHeight
        val crossResolution = (targetWidth != activeWidth || targetHeight != activeHeight)

        currentFramerate = config
        targetShutterNs = config.shutter180Ns

        if (!crossResolution) {
            // Target resolution matches active resolution (<= 30 fps),
            // update repeating request immediately with new SENSOR_FRAME_DURATION and CONTROL_AE_TARGET_FPS_RANGE
            applyStateAndRepeat()
        } else {
            // Cross-resolution (50/60 fps binned 2032x1532), close captureSession and reconfigure open gate session with new width/height
            val task = Runnable {
                try {
                    captureSession?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing capture session during reconfigure: ${e.message}")
                }
                captureSession = null

                activeWidth = targetWidth
                activeHeight = targetHeight
                engine.initialize(activeWidth, activeHeight, bayerPattern)

                createOpenGateSession()
            }
            val executor = cameraExecutor
            if (executor != null) {
                executor.execute(task)
            } else {
                task.run()
            }
        }
    }

    fun updateManualControls(iso: Int, shutterNs: Long, focusDiopter: Float, kelvin: Int, tint: Int) {
        targetIso = iso
        targetShutterNs = shutterNs
        targetFocusDiopter = focusDiopter
        targetKelvin = kelvin
        targetTint = tint

        if (currentMode == ControlMode.FULL_MANUAL) {
            applyStateAndRepeat()
        }
    }

    private fun applyStateAndRepeat() {
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val rawSurface = engine.getCameraSurface() ?: return

        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        builder.addTarget(rawSurface)

        // Baseline: Clamp frame rate to configured cadence
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, currentFramerate.fpsRange)
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, currentFramerate.frameDurationNs)

        // Baseline: Prevent digital gain highlight clipping
        builder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, 100)

        // Baseline: Suppress all ISP synthetic sharpening and denoising artifacts
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
        builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
        builder.set(CaptureRequest.SHADING_MODE, CameraMetadata.SHADING_MODE_OFF)
        builder.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_OFF)
        builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF)
        builder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraMetadata.DISTORTION_CORRECTION_MODE_OFF)

        // Baseline: Freeze ISP dynamic tone-mapping to linear identity
        builder.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
        val linearCurve = floatArrayOf(0.0f, 0.0f, 1.0f, 1.0f)
        builder.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(linearCurve, linearCurve, linearCurve))

        // Torch / Flash Mode
        if (isTorchEnabled) {
            builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
        } else {
            builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        }

        if (currentMode == ControlMode.AUTO) {
            // Auto Mode: Continuous AE & AF with frozen ISP curves
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        } else {
            // Full Manual Mode
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, targetIso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, targetShutterNs)

            // Manual Focus
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, targetFocusDiopter)

            // Manual Kelvin & Tint White Balance
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)

            val gains = ColorScienceUtils.calculateColorGains(targetKelvin, targetTint)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, gains.toRggbChannelVector())

            val compMatrix = ColorScienceUtils.computeCompositeColorMatrix(gains)
            builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, ColorScienceUtils.floatMatrixToColorSpaceTransform(compMatrix))
        }

        // Repeating Capture Request Callback: dynamic black level ingestion
        session.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                // Extract Dynamic Black Level vector
                val dynBlack = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
                    ?: floatArrayOf(256.0f, 256.0f, 256.0f, 256.0f)

                val whiteLevel = cameraCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.toFloat() ?: 4095.0f

                val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
                    ?: RggbChannelVector(1.0f, 1.0f, 1.0f, 1.0f)
                val gainsArr = floatArrayOf(gains.red, gains.greenEven, gains.greenOdd, gains.blue)

                val compMatrix = ColorScienceUtils.computeCompositeColorMatrix()

                // Dispatch to C++ GPU debayering pipeline
                engine.updateMetadata(dynBlack, whiteLevel, gainsArr, compMatrix)

                onFrameMetadataListener?.invoke(dynBlack, whiteLevel)
            }
        }, cameraHandler)
    }

    fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        engine.destroy()
    }
}
