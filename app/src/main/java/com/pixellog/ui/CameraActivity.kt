package com.pixellog.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pixellog.R
import com.pixellog.audio.AudioCapturePipeline
import com.pixellog.audio.AudioInputManager
import com.pixellog.camera.CameraController
import com.pixellog.nativebridge.PixelLogEngine
import com.pixellog.recording.PixelLogEncoderPipeline
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class CameraActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "CameraActivity"
        private const val PERMISSIONS_REQUEST_CODE = 1001
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    private lateinit var viewfinderSurface: SurfaceView
    private lateinit var textGateResolution: TextView
    private lateinit var textDcgMode: TextView
    private lateinit var textTimecode: TextView
    private lateinit var textBitrateStatus: TextView
    private lateinit var btnToggleLut: Button
    private lateinit var btnLogCurve: Button
    private lateinit var btnFramerate: Button
    private lateinit var btnCodec: Button
    private lateinit var btnBitrate: Button
    private lateinit var btnTorch: Button
    private lateinit var btnMic: Button
    private lateinit var btnModeSwitch: Button
    private lateinit var btnRecord: Button
    private lateinit var panelManualControls: LinearLayout

    private lateinit var progressAudioL: ProgressBar
    private lateinit var progressAudioR: ProgressBar
    private lateinit var textAudioDbfs: TextView

    private lateinit var audioInputManager: AudioInputManager
    private var audioCapturePipeline: AudioCapturePipeline? = null

    private lateinit var labelShutter: TextView
    private lateinit var seekShutter: SeekBar
    private lateinit var labelIso: TextView
    private lateinit var seekIso: SeekBar
    private lateinit var labelKelvin: TextView
    private lateinit var seekKelvin: SeekBar
    private lateinit var seekTint: SeekBar

    private val engine = PixelLogEngine()
    private lateinit var cameraController: CameraController
    private var encoderPipeline: PixelLogEncoderPipeline? = null

    private var isRecording = false
    private var isLutActive = true
    private var currentLogCurveType = 0 // 0: Pixel-Log, 1: Sony S-Log3, 2: Apple Log
    private var currentCodec = PixelLogEncoderPipeline.VideoCodec.HEVC
    private var currentBitratePreset = PixelLogEncoderPipeline.BitratePreset.MBPS_140
    private var recordStartTimeMs: Long = 0L
    private val timecodeHandler = Handler(Looper.getMainLooper())

    private val timecodeRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val elapsedMs = System.currentTimeMillis() - recordStartTimeMs
                val hours = (elapsedMs / 3_600_000).toInt()
                val minutes = ((elapsedMs % 3_600_000) / 60_000).toInt()
                val seconds = ((elapsedMs % 60_000) / 1_000).toInt()
                val fps = cameraController.currentFramerate.fps.toInt().coerceAtLeast(1)
                val frameDuration = 1000 / fps
                val frames = ((elapsedMs % 1_000) / frameDuration).toInt()
                textTimecode.text = String.format(Locale.US, "%02d:%02d:%02d:%02d", hours, minutes, seconds, frames)
                timecodeHandler.postDelayed(this, 33)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        viewfinderSurface = findViewById(R.id.viewfinderSurface)
        textGateResolution = findViewById(R.id.textGateResolution)
        textDcgMode = findViewById(R.id.textDcgMode)
        textTimecode = findViewById(R.id.textTimecode)
        textBitrateStatus = findViewById(R.id.textBitrateStatus)
        btnToggleLut = findViewById(R.id.btnToggleLut)
        btnLogCurve = findViewById(R.id.btnLogCurve)
        btnFramerate = findViewById(R.id.btnFramerate)
        btnCodec = findViewById(R.id.btnCodec)
        btnBitrate = findViewById(R.id.btnBitrate)
        btnTorch = findViewById(R.id.btnTorch)
        btnMic = findViewById(R.id.btnMic)
        btnModeSwitch = findViewById(R.id.btnModeSwitch)
        btnRecord = findViewById(R.id.btnRecord)
        panelManualControls = findViewById(R.id.panelManualControls)

        progressAudioL = findViewById(R.id.progressAudioL)
        progressAudioR = findViewById(R.id.progressAudioR)
        textAudioDbfs = findViewById(R.id.textAudioDbfs)

        audioInputManager = AudioInputManager(this)
        audioInputManager.onActiveDeviceChanged = {
            runOnUiThread { updateMicButtonState() }
        }
        audioInputManager.onDeviceListChanged = {
            runOnUiThread { updateMicButtonState() }
        }

        labelShutter = findViewById(R.id.labelShutter)
        seekShutter = findViewById(R.id.seekShutter)
        labelIso = findViewById(R.id.labelIso)
        seekIso = findViewById(R.id.seekIso)
        labelKelvin = findViewById(R.id.labelKelvin)
        seekKelvin = findViewById(R.id.seekKelvin)
        seekTint = findViewById(R.id.seekTint)

        cameraController = CameraController(this, engine)

        viewfinderSurface.setZOrderMediaOverlay(true)
        viewfinderSurface.holder.setFormat(android.graphics.PixelFormat.RGBA_1010102)
        viewfinderSurface.holder.addCallback(this)

        setupListeners()

        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST_CODE)
        } else {
            setupAudioPipeline()
        }
    }

    private fun hasPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun setupListeners() {
        btnModeSwitch.setOnClickListener {
            if (cameraController.currentMode == CameraController.ControlMode.AUTO) {
                cameraController.setMode(CameraController.ControlMode.FULL_MANUAL)
                btnModeSwitch.text = "MODE: MANUAL"
                btnModeSwitch.setTextColor(0xFFFFAB00.toInt())
                panelManualControls.visibility = View.VISIBLE
            } else {
                cameraController.setMode(CameraController.ControlMode.AUTO)
                btnModeSwitch.text = "MODE: AUTO"
                btnModeSwitch.setTextColor(0xFF00E5FF.toInt())
                panelManualControls.visibility = View.GONE
            }
        }

        btnToggleLut.setOnClickListener {
            isLutActive = !isLutActive
            engine.setLutEnabled(isLutActive)
            if (isLutActive) {
                btnToggleLut.text = "LUT: Rec.709 ON"
                btnToggleLut.setTextColor(0xFFFFFFFF.toInt())
            } else {
                btnToggleLut.text = "LUT: CLEAN LOG"
                btnToggleLut.setTextColor(0xFFFFAB00.toInt())
            }
        }

        btnLogCurve.setOnClickListener {
            currentLogCurveType = (currentLogCurveType + 1) % 3
            engine.setLogCurveType(currentLogCurveType)
            val (label, color) = when (currentLogCurveType) {
                0 -> Pair("CURVE: PIXEL-LOG", 0xFF00E5FF.toInt())
                1 -> Pair("CURVE: S-LOG3", 0xFFFF4081.toInt())
                else -> Pair("CURVE: APPLE LOG", 0xFFE040FB.toInt())
            }
            btnLogCurve.text = label
            btnLogCurve.setTextColor(color)
        }

        btnFramerate.setOnClickListener {
            val allConfigs = CameraController.FramerateConfig.values()
            val nextIndex = (cameraController.currentFramerate.ordinal + 1) % allConfigs.size
            val nextConfig = allConfigs[nextIndex]
            cameraController.setFramerate(nextConfig)
            btnFramerate.text = "FPS: ${nextConfig.label}"
            textGateResolution.text = "${cameraController.activeWidth}x${cameraController.activeHeight} 4:3 OPEN GATE"
        }

        btnCodec.setOnClickListener {
            currentCodec = if (currentCodec == PixelLogEncoderPipeline.VideoCodec.HEVC) {
                PixelLogEncoderPipeline.VideoCodec.AV1
            } else {
                PixelLogEncoderPipeline.VideoCodec.HEVC
            }
            btnCodec.text = if (currentCodec == PixelLogEncoderPipeline.VideoCodec.AV1) "CODEC: AV1" else "CODEC: HEVC"
            btnCodec.setTextColor(if (currentCodec == PixelLogEncoderPipeline.VideoCodec.AV1) 0xFF00E676.toInt() else 0xFFFFD600.toInt())
            updateBitrateStatusText()
        }

        btnBitrate.setOnClickListener {
            val presets = PixelLogEncoderPipeline.BitratePreset.values()
            val nextIdx = (currentBitratePreset.ordinal + 1) % presets.size
            currentBitratePreset = presets[nextIdx]
            btnBitrate.text = currentBitratePreset.label
            encoderPipeline?.setDynamicBitrate(currentBitratePreset.targetBps)
            updateBitrateStatusText()
        }

        btnTorch.setOnClickListener {
            cameraController.isTorchEnabled = !cameraController.isTorchEnabled
            if (cameraController.isTorchEnabled) {
                btnTorch.text = "TORCH: ON"
                btnTorch.setTextColor(0xFFFFEA00.toInt())
            } else {
                btnTorch.text = "TORCH: OFF"
                btnTorch.setTextColor(0xFFFFFFFF.toInt())
            }
        }

        btnMic.setOnClickListener {
            val next = audioInputManager.cycleNextDevice()
            updateMicButtonState()
            val toastText = if (audioInputManager.isAutoRouting) {
                "Microphone: AUTO (${audioInputManager.getActiveDeviceLabel()})"
            } else {
                "Microphone: ${next?.name ?: "Internal"}"
            }
            Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show()
        }

        btnRecord.setOnClickListener {
            if (!isRecording) {
                startRecording()
            } else {
                stopRecording()
            }
        }

        seekIso.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val iso = progress.coerceAtLeast(50)
                val dcg = if (iso >= 400) "HCG" else "LCG"
                labelIso.text = "ISO: $iso ($dcg)"
                textDcgMode.text = "DCG: $dcg"
                textDcgMode.setTextColor(if (iso >= 400) 0xFFFF9100.toInt() else 0xFF76FF03.toInt())

                if (fromUser) updateManualParameters()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        seekShutter.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val angle = progress.coerceAtLeast(1)
                val fps = cameraController.currentFramerate.fps
                val fractionDenominator = (fps * 360.0 / angle).toInt()
                labelShutter.text = "SHUTTER: ${angle}° (1/${fractionDenominator}s)"
                if (fromUser) updateManualParameters()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        val wbListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val kelvin = 2000 + seekKelvin.progress
                val tint = seekTint.progress - 100
                labelKelvin.text = "WB: ${kelvin}K / ${if (tint >= 0) "+$tint" else "$tint"}"
                if (fromUser) updateManualParameters()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        }
        seekKelvin.setOnSeekBarChangeListener(wbListener)
        seekTint.setOnSeekBarChangeListener(wbListener)

        cameraController.onFrameMetadataListener = { dynBlack, whiteLevel ->
            runOnUiThread {
                val blAvg = (dynBlack[0] + dynBlack[1] + dynBlack[2] + dynBlack[3]) * 0.25f
                textBitrateStatus.text = String.format(
                    Locale.US,
                    "%s %s\nBL: %.1f | WL: %.0f",
                    if (currentCodec == PixelLogEncoderPipeline.VideoCodec.AV1) "AV1 10-BIT" else "HEVC 10-BIT",
                    currentBitratePreset.label,
                    blAvg, whiteLevel
                )
            }
        }
    }

    private fun updateBitrateStatusText() {
        textBitrateStatus.text = String.format(
            Locale.US,
            "%s %s",
            if (currentCodec == PixelLogEncoderPipeline.VideoCodec.AV1) "AV1 10-BIT" else "HEVC 10-BIT",
            currentBitratePreset.label
        )
    }

    private fun updateManualParameters() {
        val iso = seekIso.progress.coerceAtLeast(50)
        val angle = seekShutter.progress.coerceAtLeast(1)
        val shutterNs = (cameraController.currentFramerate.frameDurationNs * (angle / 360.0)).toLong().coerceAtLeast(100_000L)
        val kelvin = 2000 + seekKelvin.progress
        val tint = seekTint.progress - 100

        cameraController.updateManualControls(
            iso = iso,
            shutterNs = shutterNs,
            focusDiopter = 0.0f,
            kelvin = kelvin,
            tint = tint
        )
    }

    private fun startRecording() {
        try {
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val pixDir = File(moviesDir, "PixelLog")
            pixDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val codecTag = if (currentCodec == PixelLogEncoderPipeline.VideoCodec.AV1) "AV1" else "HEVC"
            val file = File(pixDir, "PixelLog_${timestamp}_${codecTag}_${currentBitratePreset.label.replace(" ", "")}.mp4")

            val (encWidth, encHeight) = PixelLogEncoderPipeline.getOptimalResolution(
                currentCodec,
                cameraController.activeWidth,
                cameraController.activeHeight
            )

            encoderPipeline = PixelLogEncoderPipeline(
                codec = currentCodec,
                bitratePreset = currentBitratePreset,
                width = encWidth,
                height = encHeight,
                frameRate = cameraController.currentFramerate.fps.toInt(),
                outputFile = file,
                audioCapturePipeline = audioCapturePipeline
            )

            val encoderSurface = encoderPipeline!!.prepare()
            engine.setEncoderSurface(encoderSurface)
            encoderPipeline!!.startRecording()

            isRecording = true
            recordStartTimeMs = System.currentTimeMillis()
            btnRecord.text = "STOP"
            btnRecord.setBackgroundColor(0xFFD50000.toInt())
            timecodeHandler.post(timecodeRunnable)

            Toast.makeText(this, "Recording started (${encWidth}x${encHeight} ${codecTag})", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Recording failed", e)
            Toast.makeText(this, "Recording failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        timecodeHandler.removeCallbacks(timecodeRunnable)

        btnRecord.text = "REC"
        btnRecord.setBackgroundColor(0xFFFF1744.toInt())

        val recordedFile = encoderPipeline?.outputFile
        engine.setEncoderSurface(null)
        encoderPipeline?.stopRecording()
        encoderPipeline = null

        // Register saved video clip into Android MediaStore so Gallery/Files app sees it immediately
        if (recordedFile != null && recordedFile.exists()) {
            MediaScannerConnection.scanFile(
                this,
                arrayOf(recordedFile.absolutePath),
                arrayOf("video/mp4")
            ) { path, uri ->
                Log.i(TAG, "MediaScanner registered clip: $path -> $uri")
            }
            Toast.makeText(this, "Saved: ${recordedFile.name}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadAssetLut() {
        try {
            assets.open("luts/PixelLog_to_Rec709_Display.cube").use { stream ->
                val bytes = stream.readBytes()
                engine.loadDisplayLut(bytes)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Display LUT not found in assets", Toast.LENGTH_SHORT).show()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        cameraController.start()
        cameraController.openCamera(4080, 3064)
        engine.setDisplaySurface(holder.surface)
        textGateResolution.text = "${cameraController.activeWidth}x${cameraController.activeHeight} 4:3 OPEN GATE"
        loadAssetLut()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        engine.setDisplaySurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (isRecording) stopRecording()
        cameraController.stop()
        engine.setDisplaySurface(null)
    }

    private fun setupAudioPipeline() {
        if (!hasPermissions()) return
        if (audioCapturePipeline != null) return

        audioCapturePipeline = AudioCapturePipeline(audioInputManager).apply {
            onAudioLevels = { leftDbfs, rightDbfs ->
                runOnUiThread {
                    updateAudioLevels(leftDbfs, rightDbfs)
                }
            }
            startCapture()
        }
        updateMicButtonState()
    }

    private fun updateMicButtonState() {
        btnMic.text = audioInputManager.getActiveDeviceLabel()
        val dev = audioInputManager.selectedDevice
        if (dev?.isExternal == true) {
            btnMic.setTextColor(0xFFFF4081.toInt())
        } else {
            btnMic.setTextColor(0xFF00E5FF.toInt())
        }
    }

    private fun updateAudioLevels(leftDbfs: Float, rightDbfs: Float) {
        val progL = (((leftDbfs + 60f) / 60f) * 100f).toInt().coerceIn(0, 100)
        val progR = (((rightDbfs + 60f) / 60f) * 100f).toInt().coerceIn(0, 100)

        progressAudioL.progress = progL
        progressAudioR.progress = progR

        fun getTint(dbfs: Float): Int = when {
            dbfs >= -3.0f -> 0xFFFF1744.toInt()
            dbfs >= -12.0f -> 0xFFFFD600.toInt()
            else -> 0xFF76FF03.toInt()
        }

        progressAudioL.progressTintList = ColorStateList.valueOf(getTint(leftDbfs))
        progressAudioR.progressTintList = ColorStateList.valueOf(getTint(rightDbfs))

        val devLabel = if (audioInputManager.selectedDevice?.isExternal == true) "EXT" else "INT"
        textAudioDbfs.text = String.format(Locale.US, "[%s] L:%.0f | R:%.0f dBFS", devLabel, leftDbfs, rightDbfs)
        textAudioDbfs.setTextColor(getTint(maxOf(leftDbfs, rightDbfs)))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE && hasPermissions()) {
            setupAudioPipeline()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) stopRecording()
        audioCapturePipeline?.stopCapture()
        audioCapturePipeline = null
        audioInputManager.release()
        cameraController.stop()
        engine.destroy()
    }
}
