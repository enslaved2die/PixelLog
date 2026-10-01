package com.pixellog.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraMetadata
import android.media.MediaFormat
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.net.Uri
import android.provider.OpenableColumns
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pixellog.R
import com.pixellog.audio.AudioCapturePipeline
import com.pixellog.audio.AudioInputManager
import com.pixellog.camera.CameraController
import com.pixellog.camera.LogParams
import com.pixellog.nativebridge.PixelLogEngine
import com.pixellog.recording.GyroflowTelemetryLogger
import com.pixellog.recording.PixelLogEncoderPipeline
import com.pixellog.storage.StorageBenchmark
import com.pixellog.storage.StorageTarget
import com.pixellog.storage.StorageTargetManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

/**
 * CameraActivity — Cinema-style camera UI for PixelLog.
 *
 * Layout matches the Penpot design board: landscape-first with vertical lens
 * selector pill, circular record button, vertical focus dial strip, horizontal
 * scrolling dial strip for manual controls (WB/SHUTTER/EV/ISO), settings modal overlay,
 * CPU/RAM/GPU performance bars, and stereo audio VU level meters.
 */
class CameraActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "CameraActivity"
        private const val PERMISSIONS_REQUEST_CODE = 1001
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        // Standard 1/3-stop ISO scale + sensor physical endpoints
        val MASTER_ISO_PRESETS = intArrayOf(
            24, 25, 30, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320,
            400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 7500
        )

        // Standard cinematic & photographic shutter speeds (1/x sec)
        val MASTER_SHUTTER_PRESETS = intArrayOf(
            20000, 16000, 12000, 10000, 8000, 5000, 4000, 2000, 1000, 500, 250, 125, 120, 100, 96, 60, 50, 48, 30, 25, 24, 20, 15, 12, 10, 8, 6, 5, 4, 3, 2, 1
        )
    }

    // ── Core components ──
    private val engine = PixelLogEngine()
    private lateinit var cameraController: CameraController
    private var encoderPipeline: PixelLogEncoderPipeline? = null
    private lateinit var audioInputManager: AudioInputManager
    private var audioCapturePipeline: AudioCapturePipeline? = null
    private var performanceMonitor: PerformanceMonitor? = null
    private lateinit var prefs: CameraPreferences

    // ── Audio Meter Colors & Debug Testing ──
    private val colorAudioGreen by lazy { ContextCompat.getColor(this, R.color.audio_meter_green) }
    private val colorAudioOrange by lazy { ContextCompat.getColor(this, R.color.audio_meter_orange) }
    private val colorAudioRed by lazy { ContextCompat.getColor(this, R.color.audio_meter_red) }
    private val cslAudioGreen by lazy { ColorStateList.valueOf(colorAudioGreen) }
    private val cslAudioOrange by lazy { ColorStateList.valueOf(colorAudioOrange) }
    private val cslAudioRed by lazy { ColorStateList.valueOf(colorAudioRed) }
    private var debugAudioOverride = false

    private val testCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.pixellog.TEST_AUDIO_LEVEL" -> {
                    debugAudioOverride = intent.getBooleanExtra("override", true)
                    val left = intent.getFloatExtra("left", -18f)
                    val right = intent.getFloatExtra("right", -8f)
                    runOnUiThread { updateAudioMeters(left, right) }
                }
                "com.pixellog.RECORD_TRIGGER" -> {
                    runOnUiThread {
                        if (!isRecording) startRecording() else stopRecording()
                    }
                }
                "com.pixellog.SET_TRANSFER" -> {
                    val mode = intent.getStringExtra("mode")
                    runOnUiThread {
                        currentColorTransfer = if (mode.equals("HLG", ignoreCase = true)) {
                            PixelLogEncoderPipeline.ColorTransferMode.HLG
                        } else {
                            PixelLogEncoderPipeline.ColorTransferMode.SDR_LOG
                        }
                        prefs.colorTransfer = currentColorTransfer
                        reloadCurrentLut()
                        updateSettingsDisplay()
                    }
                }
                "com.pixellog.SET_BAKE_LUT" -> {
                    val enabled = intent.getBooleanExtra("enabled", false)
                    runOnUiThread {
                        isBakeLutActive = enabled
                        prefs.isBakeLutActive = enabled
                        engine.setBakeLutToEncoder(enabled)
                        reloadCurrentLut()
                        updateSettingsDisplay()
                    }
                }
                "com.pixellog.SELECT_LUT" -> {
                    val index = intent.getIntExtra("index", 0)
                    runOnUiThread {
                        selectLut(index)
                    }
                }
                "com.pixellog.SET_LENS" -> {
                    val lensStr = intent.getStringExtra("lens") ?: "WIDE_1X"
                    try {
                        val lens = CameraController.LensZoom.valueOf(lensStr)
                        runOnUiThread { switchLens(lens) }
                    } catch (_: Exception) {}
                }
                "com.pixellog.SET_FPS" -> {
                    val fpsStr = intent.getStringExtra("fps") ?: "FPS_30"
                    try {
                        val config = CameraController.FramerateConfig.valueOf(fpsStr)
                        runOnUiThread {
                            applyFramerate(config)
                        }
                    } catch (_: Exception) {}
                }
                "com.pixellog.SET_CODEC" -> {
                    runOnUiThread {
                        currentCodec = PixelLogEncoderPipeline.VideoCodec.HEVC
                        prefs.codec = currentCodec
                        updateSettingsDisplay()
                    }
                }
                "com.pixellog.SET_STABILIZATION" -> {
                    val stabStr = intent.getStringExtra("mode") ?: "OIS"
                    try {
                        val mode = CameraController.StabilizationMode.valueOf(stabStr)
                        runOnUiThread { switchStabilization(mode) }
                    } catch (_: Exception) {}
                }
                "com.pixellog.TRIGGER_TAP_FOCUS" -> {
                    val x = intent.getFloatExtra("x", 0.5f)
                    val y = intent.getFloatExtra("y", 0.5f)
                    runOnUiThread {
                        val px = x * focusExposureOverlay.width
                        val py = y * focusExposureOverlay.height
                        focusExposureOverlay.showFocus(px, py)
                        focusExposureOverlay.onTapFocus?.invoke(x, y)
                    }
                }
                "com.pixellog.TRIGGER_HOLD_EXPOSURE" -> {
                    val x = intent.getFloatExtra("x", 0.5f)
                    val y = intent.getFloatExtra("y", 0.5f)
                    runOnUiThread {
                        val px = x * focusExposureOverlay.width
                        val py = y * focusExposureOverlay.height
                        focusExposureOverlay.showExposure(px, py, locked = true)
                        focusExposureOverlay.onHoldExposure?.invoke(x, y)
                    }
                }
                "com.pixellog.RESET_FOCUS_EXPOSURE" -> {
                    runOnUiThread {
                        focusExposureOverlay.resetAll()
                        focusExposureOverlay.onResetAfAe?.invoke()
                    }
                }
                "com.pixellog.SET_EV" -> {
                    val evVal = intent.getFloatExtra("ev", 0.0f)
                    runOnUiThread {
                        val closestIdx = findClosestEvIndex(evVal)
                        currentEvIndex = closestIdx
                        val targetEv = evValues[currentEvIndex]
                        cameraController.setEvCompensationValue(targetEv)
                        if (activeParamTab == ParamTab.EV) {
                            dialStrip.setCurrentIndex(closestIdx)
                            updateAutoManualToggleForActiveTab()
                            updateDialValueLabel()
                        }
                    }
                }
                "com.pixellog.SET_SHUTTER" -> {
                    val speed = intent.getIntExtra("speed", 60)
                    runOnUiThread {
                        val closestIdx = findClosestShutterIndex(speed)
                        currentShutterIndex = closestIdx
                        val chosenSpeed = shutterSpeeds[currentShutterIndex]
                        val shutterNs = (1_000_000_000L / chosenSpeed).coerceAtLeast(100_000L)
                        cameraController.setShutter(shutterNs, isAuto = false)
                        if (activeParamTab == ParamTab.SHUTTER) {
                            dialStrip.setCurrentIndex(closestIdx)
                            updateAutoManualToggleForActiveTab()
                            updateDialValueLabel()
                        }
                    }
                }
            }
        }
    }

    // ── Recording state ──
    private var isRecording = false
    private var currentCodec = PixelLogEncoderPipeline.VideoCodec.HEVC
    private var currentBitratePreset = PixelLogEncoderPipeline.BitratePreset.MBPS_140
    private var recordStartTimeMs: Long = 0L

    // ── Signal & Export state ──
    private var currentColorTransfer = PixelLogEncoderPipeline.ColorTransferMode.SDR_LOG
    private var isBakeLutActive = false
    private var isSidecarEnabled = false

    // ── LUT Catalog state ──
    data class LutItem(
        val id: String,
        val displayName: String,
        val assetPath: String? = null,
        val file: File? = null,
        val targetColorStandard: Int = MediaFormat.COLOR_STANDARD_BT709,
        val targetColorTransfer: Int = MediaFormat.COLOR_TRANSFER_SDR_VIDEO
    )
    private val lutCatalog = mutableListOf<LutItem>()
    private var currentLutIndex = 0

    // ── Custom LUT File Picker Launcher ──
    private val lutPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val fileName = getFileName(uri) ?: "imported_${System.currentTimeMillis()}.cube"
            val lutsDir = File(filesDir, "luts").apply { mkdirs() }
            val targetFile = File(lutsDir, fileName)

            contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            // Quick validation: verify that file contains LUT_3D_SIZE or LUT_1D_SIZE
            val headerSample = targetFile.bufferedReader().use { reader ->
                val lines = mutableListOf<String>()
                for (i in 0 until 50) {
                    val line = reader.readLine() ?: break
                    lines.add(line)
                }
                lines.joinToString("\n")
            }

            if (!headerSample.contains("LUT_3D_SIZE") && !headerSample.contains("LUT_1D_SIZE")) {
                targetFile.delete()
                Toast.makeText(this, "Invalid .cube file (missing LUT_3D_SIZE)", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }

            refreshLutCatalog()
            val newIdx = lutCatalog.indexOfFirst { it.file?.absolutePath == targetFile.absolutePath }
            if (newIdx >= 0) {
                selectLut(newIdx)
            }
            Toast.makeText(this, "Imported LUT: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import LUT", e)
            Toast.makeText(this, "Failed to import LUT: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Camera mode state ──
    private var isLutActive = true

    // ── Active parameter tab ──
    private enum class ParamTab { WB, SHUTTER, EV, ISO }
    private var activeParamTab = ParamTab.SHUTTER

    // ── Focus mode ──
    private var isFocusAuto = true

    // ── Thermal ──
    private var powerManager: PowerManager? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    // ── Handlers ──
    private val mainHandler = Handler(Looper.getMainLooper())
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
                mainHandler.postDelayed(this, 33)
            }
        }
    }
    private val remainingTimeRunnable = object : Runnable {
        override fun run() {
            updateRemainingTime()
            mainHandler.postDelayed(this, 5000)
        }
    }

    private val storageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runOnUiThread {
                val available = storageTargetManager.getAvailableTargets()
                val currentTargetStillValid = available.any { it.id == currentStorageTarget?.id }
                if (isRecording && currentStorageTarget?.isPrimary == false && !currentTargetStillValid) {
                    Log.w(TAG, "Active recording storage disconnected! Emergency stop.")
                    Toast.makeText(this@CameraActivity, "External storage disconnected! Stopping recording...", Toast.LENGTH_LONG).show()
                    stopRecording()
                }
                currentStorageTarget = storageTargetManager.resolveTarget(prefs.storageTargetId)
                updateSettingsDisplay()
                updateRemainingTime()
            }
        }
    }

    // ── View references ──
    private lateinit var viewfinderSurface: SurfaceView
    private lateinit var focusExposureOverlay: FocusExposureOverlayView
    private lateinit var textTimecode: TextView
    private lateinit var textRemainingMinutes: TextView
    private lateinit var btnSettings: ImageButton
    private lateinit var btnRecord: View
    private lateinit var btnAutoManualToggle: TextView
    private lateinit var btnFocusMode: TextView
    private lateinit var dialStrip: DialStripView
    private lateinit var focusDialStrip: DialStripView
    private lateinit var textDialValue: TextView
    private lateinit var badgeDcg: TextView
    private lateinit var settingsModalOverlay: FrameLayout
    private lateinit var settingsBackdrop: View
    private lateinit var textSettingsFps: TextView
    private lateinit var textSettingsMbps: TextView
    private lateinit var textSettingsTransfer: TextView
    private lateinit var textSettingsBakeLut: TextView
    private lateinit var textSettingsLut: TextView
    private lateinit var textSettingsImportLut: TextView
    private lateinit var textSettingsMic: TextView
    private lateinit var textSettingsSidecar: TextView
    private lateinit var textSettingsStorage: TextView
    private lateinit var textSettingsStorageSpeed: TextView
    private lateinit var textSettingsResolution: TextView

    // Top HUD storage badge & low speed warning
    private lateinit var textStorageBadge: TextView
    private lateinit var textStorageWarning: TextView

    // Storage Target & Benchmark Manager
    private lateinit var storageTargetManager: StorageTargetManager
    private var currentStorageTarget: StorageTarget? = null
    private var isBenchmarkingStorage = false

    // Main UI controls: Stabilization & Torch (top bar right of viewfinder)
    private lateinit var btnStabToggle: TextView
    private lateinit var btnTorchToggle: ImageButton
    private var gyroTelemetryLogger: GyroflowTelemetryLogger? = null

    // Lens buttons
    private lateinit var btnLens12: TextView
    private lateinit var btnLens24: TextView
    private lateinit var btnLens120: TextView

    // Parameter tabs
    private lateinit var tabWb: TextView
    private lateinit var tabShutter: TextView
    private lateinit var tabEv: TextView
    private lateinit var tabIso: TextView

    // EV Meter visualizer
    private lateinit var evMeterView: EvMeterView

    // Performance bars view (CPU / RAM / GPU)
    private lateinit var performanceBarsView: PerformanceBarsView

    // Audio meter fills
    private lateinit var barAudioLFill: View
    private lateinit var barAudioRFill: View

    // ── Shutter / ISO / WB tracking for dial ──
    private var currentShutterIndex = 10 // Default to 1/60s (180° for default FPS_30)
    private var currentIsoIndex = 2      // ISO 100
    private var currentEvIndex = 8       // EV 0
    private var currentWbIndex = 8       // 5600K

    private var shutterSpeeds = MASTER_SHUTTER_PRESETS
    private var isoValues = MASTER_ISO_PRESETS
    // EV presets (-4.0 to +4.0 EV in 0.5 EV steps matching hardware range)
    private val evValues = floatArrayOf(
        -4.0f, -3.5f, -3.0f, -2.5f, -2.0f, -1.5f, -1.0f, -0.5f,
        0.0f,
        0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f
    )
    // WB Kelvin presets
    private val wbValues = intArrayOf(2000, 2500, 2800, 3200, 3500, 4000, 4500, 5000, 5600, 6000, 6500, 7000, 7500, 8000, 10000)

    private fun findClosestShutterIndex(speed: Int): Int {
        var bestIdx = 0
        var bestDiff = Int.MAX_VALUE
        for (i in shutterSpeeds.indices) {
            val diff = kotlin.math.abs(shutterSpeeds[i] - speed)
            if (diff < bestDiff) {
                bestDiff = diff
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun formatShutterSpeed(speed: Int): String {
        return if (speed <= 1) "1s" else "1/$speed"
    }

    private fun findClosestIsoIndex(iso: Int): Int {
        var bestIdx = 0
        var bestDiff = Int.MAX_VALUE
        for (i in isoValues.indices) {
            val diff = kotlin.math.abs(isoValues[i] - iso)
            if (diff < bestDiff) {
                bestDiff = diff
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun findClosestWbIndex(kelvin: Int): Int {
        var bestIdx = 0
        var bestDiff = Int.MAX_VALUE
        for (i in wbValues.indices) {
            val diff = kotlin.math.abs(wbValues[i] - kelvin)
            if (diff < bestDiff) {
                bestDiff = diff
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun findClosestEvIndex(ev: Float): Int {
        var bestIdx = 0
        var bestDiff = Float.MAX_VALUE
        for (i in evValues.indices) {
            val diff = kotlin.math.abs(evValues[i] - ev)
            if (diff < bestDiff) {
                bestDiff = diff
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun updateActiveIsoValues() {
        val profile = cameraController.getActiveSensorProfile()
        val minIso = profile.isoRange.lower
        val maxIso = profile.isoRange.upper

        val filtered = MASTER_ISO_PRESETS.filter { it in minIso..maxIso }.toMutableList()
        if (filtered.isEmpty() || filtered.first() > minIso) {
            filtered.add(0, minIso)
        }
        if (filtered.isNotEmpty() && filtered.last() < maxIso && (maxIso - filtered.last()) >= 50) {
            filtered.add(maxIso)
        }
        val previousIso = isoValues.getOrNull(currentIsoIndex) ?: 100
        isoValues = filtered.distinct().toIntArray()
        currentIsoIndex = findClosestIsoIndex(previousIso)
    }

    private fun updateActiveShutterSpeeds() {
        val profile = cameraController.getActiveSensorProfile()
        val minExposureNs = profile.exposureTimeRangeNs.lower
        val maxExposureNs = profile.exposureTimeRangeNs.upper

        val maxAllowedSpeed = (1_000_000_000.0 / minExposureNs).toInt()
        val minAllowedSpeed = (1_000_000_000.0 / maxExposureNs).roundToInt().coerceAtLeast(1)

        val previousSpeed = shutterSpeeds.getOrNull(currentShutterIndex) ?: 60
        val filtered = MASTER_SHUTTER_PRESETS.filter { speed ->
            speed in minAllowedSpeed..maxAllowedSpeed
        }.toMutableList()

        if (filtered.isEmpty()) {
            filtered.add(minAllowedSpeed)
        }
        shutterSpeeds = filtered.distinct().sortedDescending().toIntArray()
        currentShutterIndex = findClosestShutterIndex(previousSpeed)
    }

    private fun get180ShutterIndexForFramerate(config: CameraController.FramerateConfig): Int {
        val targetSpeed = config.shutter180Speed
        val idx = shutterSpeeds.indexOf(targetSpeed)
        return if (idx >= 0) idx else findClosestShutterIndex(targetSpeed)
    }

    private fun applyFramerate(config: CameraController.FramerateConfig) {
        cameraController.setFramerate(config)
        prefs.framerate = config

        updateActiveShutterSpeeds()

        // Default shutter speed to 180° for the new framerate
        val shutter180Idx = get180ShutterIndexForFramerate(config)
        currentShutterIndex = shutter180Idx
        val speed = shutterSpeeds[currentShutterIndex]
        val shutterNs = (1_000_000_000L / speed).coerceAtLeast(100_000L)
        cameraController.targetShutterNs = shutterNs
        if (activeParamTab == ParamTab.SHUTTER) {
            dialStrip.setRange(0, shutterSpeeds.size - 1, shutterSpeeds.size - 1)
            dialStrip.setValue(currentShutterIndex)
        }
        updateDialValueLabel()
        updateSettingsDisplay()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        setContentView(R.layout.activity_camera)

        cameraController = CameraController(this, engine)
        audioInputManager = AudioInputManager(this)
        prefs = CameraPreferences(this)
        storageTargetManager = StorageTargetManager(this)
        currentStorageTarget = storageTargetManager.resolveTarget(prefs.storageTargetId)

        val storageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        try {
            ContextCompat.registerReceiver(
                this,
                storageReceiver,
                storageFilter,
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register storageReceiver", e)
        }

        // Initialize active ISO and shutter speeds based on the default sensor and framerate
        updateActiveIsoValues()
        updateActiveShutterSpeeds()

        // Restore persisted state variables
        currentCodec = prefs.codec
        currentBitratePreset = prefs.bitratePreset
        currentColorTransfer = prefs.colorTransfer
        isBakeLutActive = prefs.isBakeLutActive
        isSidecarEnabled = prefs.isSidecarEnabled
        
        // Camera controls (Focus, Shutter, WB, EV, ISO) start fresh in Auto mode on app start
        val default180Idx = get180ShutterIndexForFramerate(prefs.framerate)
        currentShutterIndex = default180Idx
        currentIsoIndex = isoValues.indexOf(100).coerceAtLeast(0)
        currentEvIndex = 8       // EV 0
        currentWbIndex = 8       // 5600K
        isFocusAuto = true

        // Seed CameraController with main sensor (1x), persisted settings, and fresh auto controls
        cameraController.setInitialLensAndFramerate(CameraController.LensZoom.WIDE_1X, prefs.framerate)
        cameraController.isShutterAuto = true
        cameraController.isIsoAuto = true
        cameraController.isWbAuto = true
        cameraController.isFocusAuto = true

        val initialSpeed = shutterSpeeds[currentShutterIndex]
        cameraController.targetShutterNs = (1_000_000_000L / initialSpeed).coerceAtLeast(100_000L)
        cameraController.targetIso = isoValues[currentIsoIndex]
        cameraController.targetKelvin = wbValues[currentWbIndex]
        cameraController.targetEvCompensation = 0
        cameraController.targetFocusDiopter = 0.0f
        cameraController.setStabilization(prefs.stabilizationMode)

        bindViews()
        setupViewfinder()
        setupLensSelector()
        setupStabilizationSwitches()
        setupRecordButton()
        setupSettingsModal()
        setupParameterTabs()
        setupDialStrip()
        setupFocusDial()
        setupAutoManualToggle()
        setupLiveTelemetry()
        setupFocusExposureOverlay()
        setupPerformanceMonitor()
        setupThermalMonitoring()
        updateRemainingTime()

        // Start remaining-time polling
        mainHandler.postDelayed(remainingTimeRunnable, 5000)

        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSIONS_REQUEST_CODE)
        } else {
            setupAudioPipeline()
        }

        val testFilter = IntentFilter().apply {
            addAction("com.pixellog.TEST_AUDIO_LEVEL")
            addAction("com.pixellog.RECORD_TRIGGER")
            addAction("com.pixellog.SET_TRANSFER")
            addAction("com.pixellog.SET_BAKE_LUT")
            addAction("com.pixellog.SELECT_LUT")
            addAction("com.pixellog.SET_LENS")
            addAction("com.pixellog.SET_FPS")
            addAction("com.pixellog.SET_CODEC")
            addAction("com.pixellog.SET_CURVE")
            addAction("com.pixellog.SET_STABILIZATION")
            addAction("com.pixellog.TRIGGER_TAP_FOCUS")
            addAction("com.pixellog.TRIGGER_HOLD_EXPOSURE")
            addAction("com.pixellog.RESET_FOCUS_EXPOSURE")
            addAction("com.pixellog.SET_EV")
            addAction("com.pixellog.SET_SHUTTER")
        }
        ContextCompat.registerReceiver(
            this,
            testCommandReceiver,
            testFilter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun bindViews() {
        viewfinderSurface = findViewById(R.id.viewfinderSurface)
        focusExposureOverlay = findViewById(R.id.focusExposureOverlay)
        textTimecode = findViewById(R.id.textTimecode)
        textTimecode.typeface = Typeface.MONOSPACE
        textRemainingMinutes = findViewById(R.id.textRemainingMinutes)
        btnSettings = findViewById(R.id.btnSettings)
        btnRecord = findViewById(R.id.btnRecord)
        evMeterView = findViewById(R.id.evMeterView)
        btnAutoManualToggle = findViewById(R.id.btnAutoManualToggle)
        btnFocusMode = findViewById(R.id.btnFocusMode)
        dialStrip = findViewById(R.id.dialStrip)
        focusDialStrip = findViewById(R.id.focusDialStrip)
        textDialValue = findViewById(R.id.textDialValue)
        badgeDcg = findViewById(R.id.badgeDcg)
        settingsModalOverlay = findViewById(R.id.settingsModalOverlay)
        settingsBackdrop = findViewById(R.id.settingsBackdrop)
        textSettingsFps = findViewById(R.id.textSettingsFps)
        textSettingsMbps = findViewById(R.id.textSettingsMbps)
        textSettingsTransfer = findViewById(R.id.textSettingsTransfer)
        textSettingsBakeLut = findViewById(R.id.textSettingsBakeLut)
        textSettingsLut = findViewById(R.id.textSettingsLut)
        textSettingsImportLut = findViewById(R.id.textSettingsImportLut)
        textSettingsMic = findViewById(R.id.textSettingsMic)
        textSettingsSidecar = findViewById(R.id.textSettingsSidecar)
        textSettingsStorage = findViewById(R.id.textSettingsStorage)
        textSettingsStorageSpeed = findViewById(R.id.textSettingsStorageSpeed)
        textSettingsResolution = findViewById(R.id.textSettingsResolution)

        textStorageBadge = findViewById(R.id.textStorageBadge)
        textStorageWarning = findViewById(R.id.textStorageWarning)

        btnStabToggle = findViewById(R.id.btnStabToggle)
        btnTorchToggle = findViewById(R.id.btnTorchToggle)
        btnTorchToggle.setOnClickListener { toggleTorch() }
        updateTorchUI()

        btnLens12 = findViewById(R.id.btnLens12)
        btnLens24 = findViewById(R.id.btnLens24)
        btnLens120 = findViewById(R.id.btnLens120)

        tabWb = findViewById(R.id.tabWb)
        tabShutter = findViewById(R.id.tabShutter)
        tabEv = findViewById(R.id.tabEv)
        tabIso = findViewById(R.id.tabIso)

        performanceBarsView = findViewById(R.id.performanceBarsView)

        barAudioLFill = findViewById(R.id.barAudioLFill)
        barAudioRFill = findViewById(R.id.barAudioRFill)
    }

    // ── Viewfinder ──

    private fun setupViewfinder() {
        viewfinderSurface.setZOrderMediaOverlay(true)
        viewfinderSurface.holder.setFormat(android.graphics.PixelFormat.RGBA_1010102)
        viewfinderSurface.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        cameraController.start()
        try {
            cameraController.openCamera(4080, 3064)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera: ${e.message}", e)
        }
        engine.setDisplaySurface(holder.surface)
        engine.setBakeLutToEncoder(isBakeLutActive)
        refreshLutCatalog()
        val savedLutId = prefs.selectedLutId
        val targetIdx = lutCatalog.indexOfFirst { it.id == savedLutId }.takeIf { it >= 0 } ?: 0
        selectLut(targetIdx)
        updateTorchUI()
        updateSettingsDisplay()
        updateLensButtonsUi(cameraController.currentLens)
        updateAutoManualToggleForActiveTab()
        updateDialForTab()

        cameraController.onFrameMetadataListener = { _, _ -> }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        engine.setDisplaySurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (isRecording) stopRecording()
        cameraController.stop()
        engine.setDisplaySurface(null)
    }

    // ── 3D LUT Catalog & Selection ──

    private fun refreshLutCatalog() {
        lutCatalog.clear()
        // Built-in calibrated technical 3D LUTs
        lutCatalog.add(LutItem("rec709", "REC.709", assetPath = "luts/PixelLog_to_Rec709_Display.cube",
            targetColorStandard = MediaFormat.COLOR_STANDARD_BT709,
            targetColorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO))
        lutCatalog.add(LutItem("agx_base", "AGX FILM", assetPath = "luts/PixelLog_to_AgX_Base.cube",
            targetColorStandard = MediaFormat.COLOR_STANDARD_BT709,
            targetColorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO))
        lutCatalog.add(LutItem("agx_punchy", "AGX PUNCH", assetPath = "luts/PixelLog_to_AgX_Punchy.cube",
            targetColorStandard = MediaFormat.COLOR_STANDARD_BT709,
            targetColorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO))
        lutCatalog.add(LutItem("dwg", "DWG", assetPath = "luts/PixelLog_to_DWG_Intermediate.cube",
            targetColorStandard = MediaFormat.COLOR_STANDARD_BT2020,
            targetColorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO))
        lutCatalog.add(LutItem("acescg", "ACEScg", assetPath = "luts/PixelLog_to_ACEScg.cube",
            targetColorStandard = MediaFormat.COLOR_STANDARD_BT2020,
            targetColorTransfer = MediaFormat.COLOR_TRANSFER_LINEAR))
        lutCatalog.add(LutItem("linear", "LINEAR", assetPath = "luts/PixelLog_to_Rec2020_Linear.cube",
            targetColorStandard = MediaFormat.COLOR_STANDARD_BT2020,
            targetColorTransfer = MediaFormat.COLOR_TRANSFER_LINEAR))

        // Imported custom .cube files from app private directory
        val lutsDir = File(filesDir, "luts")
        if (lutsDir.exists()) {
            lutsDir.listFiles { f -> f.extension.equals("cube", ignoreCase = true) }?.sortedBy { it.name }?.forEach { f ->
                val shortName = f.nameWithoutExtension.take(8).uppercase(Locale.US)
                lutCatalog.add(LutItem("custom_${f.nameWithoutExtension}", shortName, file = f))
            }
        }

        // Clean Log bypass (LUT disabled)
        lutCatalog.add(LutItem("clean", "CLEAN"))
    }

    /**
     * Resolves active LUT variant: When HLG mode and Bake-in are selected,
     * AGX Film and AGX Punchy automatically switch to their dedicated calibrated
     * AGX HLG and AGX Punchy HLG HDR 3D LUTs (BT.2020 + HLG transfer).
     */
    private fun getEffectiveLutItem(item: LutItem?): LutItem? {
        if (item == null) return null
        if (isBakeLutActive && currentColorTransfer == PixelLogEncoderPipeline.ColorTransferMode.HLG) {
            when (item.id) {
                "agx_base" -> return item.copy(
                    displayName = "AGX HLG",
                    assetPath = "luts/PixelLog_to_AgX_HLG.cube",
                    targetColorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    targetColorTransfer = MediaFormat.COLOR_TRANSFER_HLG
                )
                "agx_punchy" -> return item.copy(
                    displayName = "AGX PUNCH HLG",
                    assetPath = "luts/PixelLog_to_AgX_Punchy_HLG.cube",
                    targetColorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                    targetColorTransfer = MediaFormat.COLOR_TRANSFER_HLG
                )
            }
        }
        return item
    }

    private fun reloadCurrentLut() {
        if (lutCatalog.isEmpty()) return
        val rawItem = lutCatalog.getOrNull(currentLutIndex) ?: return
        val item = getEffectiveLutItem(rawItem) ?: rawItem
        if (item.id == "clean" || !isLutActive) {
            engine.setLutEnabled(false)
            performanceMonitor?.isLutEnabled = false
        } else {
            try {
                val bytes = if (item.assetPath != null) {
                    assets.open(item.assetPath).use { it.readBytes() }
                } else if (item.file != null && item.file.exists()) {
                    item.file.readBytes()
                } else null

                if (bytes != null) {
                    engine.loadDisplayLut(bytes)
                    engine.setLutEnabled(true)
                    performanceMonitor?.isLutEnabled = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reload LUT ${item.displayName}", e)
            }
        }
    }

    private fun selectLut(index: Int) {
        if (lutCatalog.isEmpty()) return
        currentLutIndex = (index % lutCatalog.size + lutCatalog.size) % lutCatalog.size
        val rawItem = lutCatalog[currentLutIndex]
        prefs.selectedLutId = rawItem.id
        val item = getEffectiveLutItem(rawItem) ?: rawItem

        if (item.id == "clean") {
            isLutActive = false
            engine.setLutEnabled(false)
            performanceMonitor?.isLutEnabled = false
        } else {
            isLutActive = true
            try {
                val bytes = if (item.assetPath != null) {
                    assets.open(item.assetPath).use { it.readBytes() }
                } else if (item.file != null && item.file.exists()) {
                    item.file.readBytes()
                } else null

                if (bytes != null) {
                    engine.loadDisplayLut(bytes)
                    engine.setLutEnabled(true)
                    performanceMonitor?.isLutEnabled = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load LUT ${item.displayName}", e)
                Toast.makeText(this, "Failed to load ${item.displayName}", Toast.LENGTH_SHORT).show()
            }
        }
        updateSettingsDisplay()
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        result = it.getString(nameIndex)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path?.let { p ->
                val cut = p.lastIndexOf('/')
                if (cut != -1) p.substring(cut + 1) else p
            }
        }
        return result
    }

    // ── Lens Selector ──

    private fun setupLensSelector() {
        btnLens12.setOnClickListener { switchLens(CameraController.LensZoom.UW_05X) }
        btnLens24.setOnClickListener { switchLens(CameraController.LensZoom.WIDE_1X) }
        btnLens120.setOnClickListener { switchLens(CameraController.LensZoom.TELE_5X) }
        updateLensButtonsUi(cameraController.currentLens)
    }

    private fun switchLens(lens: CameraController.LensZoom) {
        if (isRecording) {
            Toast.makeText(this, "Cannot switch lens while recording", Toast.LENGTH_SHORT).show()
            return
        }
        cameraController.setLensZoom(lens)
        if (!cameraController.currentLensHasOis && cameraController.stabilizationMode == CameraController.StabilizationMode.OIS) {
            cameraController.setStabilization(CameraController.StabilizationMode.OFF)
            prefs.stabilizationMode = CameraController.StabilizationMode.OFF
        }
        updateLensButtonsUi(lens)
        updateStabilizationSwitchesUi()
        updateSettingsDisplay()
        updateIsoTabIndicator()

        // Dynamically adapt ISO and shutter ranges to the new physical sensor
        updateActiveIsoValues()
        updateActiveShutterSpeeds()

        // Re-scale manual focus for the new physical sensor
        if (!isFocusAuto) {
            val activeMax = cameraController.getActiveSensorProfile().minFocusDistanceDiopters
            val diopter = (focusDialStrip.getCurrentIndex() / 100f) * activeMax
            cameraController.setFocusDiopter(diopter)
        }

        updateDialForTab()
        updateDialValueLabel()
    }

    private fun updateLensButtonsUi(activeLens: CameraController.LensZoom) {
        val mapping = listOf(
            Pair(CameraController.LensZoom.TELE_5X, btnLens120),
            Pair(CameraController.LensZoom.WIDE_1X, btnLens24),
            Pair(CameraController.LensZoom.UW_05X, btnLens12)
        )
        for ((lens, btn) in mapping) {
            if (lens == activeLens) {
                btn.setBackgroundResource(R.drawable.bg_lens_selected)
                btn.setTextColor(0xFFFFFFFF.toInt())
            } else {
                btn.background = null
                btn.setTextColor(0xCCFFFFFF.toInt())
            }
        }
    }

    // ── Stabilization Mode Toggle (OFF / OIS / GYRO) from Penpot design ──

    private fun setupStabilizationSwitches() {
        btnStabToggle.setOnClickListener { cycleStabilizationMode() }
        updateStabilizationSwitchesUi()
    }

    private fun cycleStabilizationMode() {
        if (isRecording) {
            Toast.makeText(this, "Cannot switch stabilization while recording", Toast.LENGTH_SHORT).show()
            return
        }

        val hasOis = cameraController.currentLensHasOis
        val nextMode = when (cameraController.stabilizationMode) {
            CameraController.StabilizationMode.OFF -> {
                if (hasOis) CameraController.StabilizationMode.OIS else CameraController.StabilizationMode.GYRO
            }
            CameraController.StabilizationMode.OIS -> {
                CameraController.StabilizationMode.GYRO
            }
            CameraController.StabilizationMode.GYRO -> {
                CameraController.StabilizationMode.OFF
            }
            else -> CameraController.StabilizationMode.OFF
        }

        switchStabilization(nextMode)
    }

    private fun switchStabilization(mode: CameraController.StabilizationMode) {
        if (isRecording) {
            Toast.makeText(this, "Cannot switch stabilization while recording", Toast.LENGTH_SHORT).show()
            return
        }
        val safeMode = if (mode == CameraController.StabilizationMode.EIS || mode == CameraController.StabilizationMode.FULL) {
            CameraController.StabilizationMode.OIS
        } else {
            mode
        }
        cameraController.setStabilization(safeMode)
        prefs.stabilizationMode = safeMode
        updateStabilizationSwitchesUi()
        updateSettingsDisplay()
        val desc = when (safeMode) {
            CameraController.StabilizationMode.OIS -> "OIS Active (Zero Crop)"
            CameraController.StabilizationMode.GYRO -> "GYRO Log Active (Gyroflow Post-Stab, OIS OFF)"
            CameraController.StabilizationMode.OFF -> "Stabilization OFF (Zero Crop)"
            else -> safeMode.label
        }
        Toast.makeText(this, "Stabilization: ${safeMode.label} • $desc", Toast.LENGTH_SHORT).show()
    }

    private fun updateStabilizationSwitchesUi() {
        val currentMode = cameraController.stabilizationMode
        btnStabToggle.text = currentMode.label
        when (currentMode) {
            CameraController.StabilizationMode.OIS -> {
                btnStabToggle.setTextColor(Color.WHITE)
            }
            CameraController.StabilizationMode.GYRO -> {
                btnStabToggle.setTextColor(ContextCompat.getColor(this, R.color.cyan_accent))
            }
            CameraController.StabilizationMode.OFF -> {
                btnStabToggle.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
            else -> {
                btnStabToggle.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
        }
    }

    private fun toggleTorch() {
        cameraController.isTorchEnabled = !cameraController.isTorchEnabled
        updateTorchUI()
    }

    private fun updateTorchUI() {
        val color = if (cameraController.isTorchEnabled) {
            ContextCompat.getColor(this, R.color.cyan_accent)
        } else {
            ContextCompat.getColor(this, R.color.text_secondary)
        }
        btnTorchToggle.setColorFilter(color)
    }

    // ── Record Button ──

    private fun setupRecordButton() {
        btnRecord.setOnClickListener {
            if (!isRecording) {
                startRecording()
            } else {
                stopRecording()
            }
        }
    }

    private fun startRecording() {
        try {
            val target = currentStorageTarget ?: storageTargetManager.getInternalTarget()
            if (target.availableBytes < 100L * 1024L * 1024L) {
                Toast.makeText(this, "Storage full: less than 100MB available", Toast.LENGTH_LONG).show()
                return
            }
            val pixDir = target.recordingDirectory
            pixDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val codecTag = "HEVC"
            val rawLutItem = lutCatalog.getOrNull(currentLutIndex)
            val activeLutItem = getEffectiveLutItem(rawLutItem)
            val isBakeValid = isBakeLutActive && activeLutItem != null && activeLutItem.id != "clean"
            val bakedStd = activeLutItem?.targetColorStandard ?: MediaFormat.COLOR_STANDARD_BT709
            val bakedTransfer = activeLutItem?.targetColorTransfer ?: MediaFormat.COLOR_TRANSFER_SDR_VIDEO

            val transferTag = if (isBakeValid) {
                if (bakedTransfer == MediaFormat.COLOR_TRANSFER_HLG) "HLG" else "REC709"
            } else {
                if (currentColorTransfer == PixelLogEncoderPipeline.ColorTransferMode.HLG) "HLG" else "LOG"
            }
            val bakeTag = if (isBakeValid) "BAKED" else "RAW"
            val file = File(pixDir, "PixelLog_${timestamp}_${codecTag}_${transferTag}_${bakeTag}_${currentBitratePreset.label.replace(" ", "")}.mp4")

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
                audioCapturePipeline = audioCapturePipeline,
                colorTransfer = currentColorTransfer,
                isLutBaked = isBakeValid,
                bakedColorStandard = bakedStd,
                bakedColorTransfer = bakedTransfer
            )

            engine.setBakeLutToEncoder(isBakeValid)
            val encoderSurface = encoderPipeline!!.prepare()
            engine.setEncoderSurface(encoderSurface)

            encoderPipeline!!.onStorageSlowWarning = { backlogFrames, latencyMs ->
                runOnUiThread {
                    if (isRecording) {
                        textStorageWarning.visibility = View.VISIBLE
                        textStorageWarning.text = if (latencyMs > 0) "SLOW MEDIA (${latencyMs}ms)" else "BUFFER DELAY ($backlogFrames)"
                    }
                }
            }

            encoderPipeline!!.startRecording()

            if (cameraController.stabilizationMode == CameraController.StabilizationMode.GYRO) {
                gyroTelemetryLogger = GyroflowTelemetryLogger(this)
                gyroTelemetryLogger?.startLogging(
                    videoFile = file,
                    readoutSkewNs = cameraController.lastRollingShutterSkewNs,
                    fps = cameraController.currentFramerate.fps,
                    currentLensLabel = cameraController.currentLens.label
                )
            }

            isRecording = true
            performanceMonitor?.isRecording = true
            recordStartTimeMs = System.currentTimeMillis()
            btnRecord.setBackgroundResource(R.drawable.bg_record_button_stop)
            mainHandler.post(timecodeRunnable)

            val gyroTag = if (cameraController.stabilizationMode == CameraController.StabilizationMode.GYRO) " [GYRO LOG]" else ""
            Toast.makeText(this, "Recording started (${encWidth}x${encHeight} ${codecTag} ${transferTag} ${bakeTag})$gyroTag", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Recording failed", e)
            Toast.makeText(this, "Recording failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        performanceMonitor?.isRecording = false
        mainHandler.removeCallbacks(timecodeRunnable)
        textStorageWarning.visibility = View.GONE

        btnRecord.setBackgroundResource(R.drawable.bg_record_button)

        val recordedFile = encoderPipeline?.outputFile
        val durationMs = System.currentTimeMillis() - recordStartTimeMs
        engine.setEncoderSurface(null)
        encoderPipeline?.stopRecording()
        encoderPipeline = null

        val gcsvFile = if (cameraController.stabilizationMode == CameraController.StabilizationMode.GYRO) {
            gyroTelemetryLogger?.stopLogging()
        } else {
            gyroTelemetryLogger?.stopLogging()
            null
        }
        gyroTelemetryLogger = null

        if (recordedFile != null && recordedFile.exists()) {
            val sidecarFile = if (isSidecarEnabled) generateSidecarJson(recordedFile, durationMs, gcsvFile) else null
            val filesToScan = mutableListOf(recordedFile.absolutePath)
            if (sidecarFile != null && sidecarFile.exists()) {
                filesToScan.add(sidecarFile.absolutePath)
            }
            if (gcsvFile != null && gcsvFile.exists()) {
                filesToScan.add(gcsvFile.absolutePath)
            }
            val mimeTypes = filesToScan.map {
                when {
                    it.endsWith(".mp4") -> "video/mp4"
                    it.endsWith(".json") -> "application/json"
                    it.endsWith(".gcsv") -> "text/csv"
                    else -> "*/*"
                }
            }.toTypedArray()
            MediaScannerConnection.scanFile(this, filesToScan.toTypedArray(), mimeTypes) { path, uri ->
                Log.i(TAG, "MediaScanner registered: $path -> $uri")
            }
            val toastMsg = buildString {
                append("Saved: ${recordedFile.name}")
                val tags = mutableListOf<String>()
                if (gcsvFile != null) tags.add("+gcsv")
                if (sidecarFile != null) tags.add("+json")
                if (tags.isNotEmpty()) {
                    append(" (${tags.joinToString(", ")})")
                }
            }
            Toast.makeText(this, toastMsg, Toast.LENGTH_LONG).show()
        }
    }

    // ── Settings Modal ──

    private fun setupSettingsModal() {
        btnSettings.setOnClickListener {
            settingsModalOverlay.visibility =
                if (settingsModalOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        settingsBackdrop.setOnClickListener {
            settingsModalOverlay.visibility = View.GONE
        }

        // Row 1: FPS tap cycles
        findViewById<View>(R.id.settingsFpsGroup).setOnClickListener {
            val allConfigs = CameraController.FramerateConfig.values()
            val nextIndex = (cameraController.currentFramerate.ordinal + 1) % allConfigs.size
            val nextConfig = allConfigs[nextIndex]
            applyFramerate(nextConfig)
        }

        // Row 1: Bitrate tap cycles
        findViewById<View>(R.id.settingsBitrateGroup).setOnClickListener {
            val presets = PixelLogEncoderPipeline.BitratePreset.values()
            val nextIdx = (currentBitratePreset.ordinal + 1) % presets.size
            currentBitratePreset = presets[nextIdx]
            prefs.bitratePreset = currentBitratePreset
            encoderPipeline?.setDynamicBitrate(currentBitratePreset.targetBps)
            updateSettingsDisplay()
            updateRemainingTime()
        }

        // Row 1: Signal / Metadata Transfer Tag toggle (LOG <-> HLG)
        findViewById<View>(R.id.settingsTransferGroup).setOnClickListener {
            currentColorTransfer = if (currentColorTransfer == PixelLogEncoderPipeline.ColorTransferMode.SDR_LOG) {
                PixelLogEncoderPipeline.ColorTransferMode.HLG
            } else {
                PixelLogEncoderPipeline.ColorTransferMode.SDR_LOG
            }
            prefs.colorTransfer = currentColorTransfer
            reloadCurrentLut()
            updateSettingsDisplay()
        }

        // Row 2: Bake LUT to recording toggle (OFF <-> BAKED)
        findViewById<View>(R.id.settingsBakeLutGroup).setOnClickListener {
            isBakeLutActive = !isBakeLutActive
            prefs.isBakeLutActive = isBakeLutActive
            engine.setBakeLutToEncoder(isBakeLutActive)
            reloadCurrentLut()
            updateSettingsDisplay()
        }

        // Row 2: LUT selection (Cycles through built-in, custom imported, and CLEAN bypass)
        findViewById<View>(R.id.settingsLutGroup).setOnClickListener {
            if (lutCatalog.isNotEmpty()) {
                val nextIdx = (currentLutIndex + 1) % lutCatalog.size
                selectLut(nextIdx)
            }
        }

        // Row 2: Custom .cube LUT import launcher
        findViewById<View>(R.id.settingsImportLutGroup).setOnClickListener {
            try {
                lutPickerLauncher.launch("*/*")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch LUT picker", e)
                Toast.makeText(this, "Could not launch file picker", Toast.LENGTH_SHORT).show()
            }
        }

        // Row 3: Mic cycle (AUTO -> Built-in -> External -> ...)
        findViewById<View>(R.id.settingsMicGroup).setOnClickListener {
            audioInputManager.cycleNextDevice()
            updateSettingsDisplay()
        }

        // Row 3: Sidecar JSON toggle (OFF / ON)
        findViewById<View>(R.id.settingsSidecarGroup).setOnClickListener {
            isSidecarEnabled = !isSidecarEnabled
            prefs.isSidecarEnabled = isSidecarEnabled
            updateSettingsDisplay()
        }

        // Row 3: Storage destination cycle (INTERNAL -> USB 1 -> USB 2 -> ...)
        findViewById<View>(R.id.settingsStorageGroup).setOnClickListener {
            cycleStorageTarget()
        }

        updateSettingsDisplay()
    }

    private fun updateSettingsDisplay() {
        textSettingsFps.text = cameraController.currentFramerate.label
        textSettingsMbps.text = currentBitratePreset.label.replace(" MBPS", "").replace(" Mbps", "")
            .replace("MBPS", "").trim().let {
                it.filter { c -> c.isDigit() }.ifEmpty { "140" }
            }
        textSettingsResolution.text = "${cameraController.activeWidth} × ${cameraController.activeHeight}"

        val rawLutItem = lutCatalog.getOrNull(currentLutIndex)
        val effectiveLutItem = getEffectiveLutItem(rawLutItem)
        val activeLutName = effectiveLutItem?.displayName ?: if (isLutActive) "REC.709" else "CLEAN"
        textSettingsLut.text = activeLutName

        // Signal transfer tag
        textSettingsTransfer.text = currentColorTransfer.label
        textSettingsTransfer.setTextColor(
            if (currentColorTransfer == PixelLogEncoderPipeline.ColorTransferMode.HLG) {
                Color.parseColor("#FF9800") // Warm amber for HDR HLG
            } else {
                ContextCompat.getColor(this, R.color.cyan_accent)
            }
        )

        // Bake LUT state
        textSettingsBakeLut.text = if (isBakeLutActive) "BAKED" else "OFF"
        textSettingsBakeLut.setTextColor(
            if (isBakeLutActive) {
                Color.parseColor("#FF5252") // Warning red for destructive LUT bake
            } else {
                Color.WHITE
            }
        )

        textSettingsMic.text = if (audioInputManager.isAutoRouting) {
            "AUTO"
        } else {
            audioInputManager.selectedDevice?.typeLabel?.uppercase(Locale.US) ?: "INTERNAL"
        }

        textSettingsSidecar.text = if (isSidecarEnabled) "ON" else "OFF"
        textSettingsSidecar.setTextColor(
            if (isSidecarEnabled) ContextCompat.getColor(this, R.color.cyan_accent) else Color.WHITE
        )

        val target = currentStorageTarget ?: storageTargetManager.getInternalTarget()
        textSettingsStorage.text = target.name
        textStorageBadge.text = if (target.isPrimary) "INT" else "USB"
    }

    private fun cycleStorageTarget() {
        if (isRecording) {
            Toast.makeText(this, "Cannot switch storage while recording", Toast.LENGTH_SHORT).show()
            return
        }
        val targets = storageTargetManager.getAvailableTargets()
        if (targets.isEmpty()) return
        val currentIdx = targets.indexOfFirst { it.id == currentStorageTarget?.id }.takeIf { it >= 0 } ?: 0
        val nextIdx = (currentIdx + 1) % targets.size
        currentStorageTarget = targets[nextIdx]
        prefs.storageTargetId = currentStorageTarget!!.id
        updateSettingsDisplay()
        updateRemainingTime()
        runStorageSpeedTest(currentStorageTarget!!)
    }

    private fun runStorageSpeedTest(target: StorageTarget) {
        if (isBenchmarkingStorage) return
        isBenchmarkingStorage = true
        textSettingsStorageSpeed.text = "Testing..."
        textSettingsStorageSpeed.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))

        Thread {
            try {
                val result = StorageBenchmark.runBenchmark(
                    directory = target.recordingDirectory,
                    targetBitrateBps = currentBitratePreset.targetBps
                )
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    textSettingsStorageSpeed.text = result.message
                    when (result.status) {
                        StorageBenchmark.SpeedStatus.GOOD -> {
                            textSettingsStorageSpeed.setTextColor(ContextCompat.getColor(this, R.color.audio_meter_green))
                        }
                        StorageBenchmark.SpeedStatus.WARN -> {
                            textSettingsStorageSpeed.setTextColor(ContextCompat.getColor(this, R.color.audio_meter_orange))
                            Toast.makeText(this, "Storage Warning: ${result.message}", Toast.LENGTH_SHORT).show()
                        }
                        StorageBenchmark.SpeedStatus.CRITICAL -> {
                            textSettingsStorageSpeed.setTextColor(ContextCompat.getColor(this, R.color.rec_red))
                            Toast.makeText(this, "⚠️ SLOW STORAGE: ${result.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } finally {
                isBenchmarkingStorage = false
            }
        }.start()
    }

    // ── Parameter Tabs ──

    private fun setupParameterTabs() {
        tabWb.setOnClickListener { setActiveTab(ParamTab.WB) }
        tabShutter.setOnClickListener { setActiveTab(ParamTab.SHUTTER) }
        tabEv.setOnClickListener { setActiveTab(ParamTab.EV) }
        tabIso.setOnClickListener { setActiveTab(ParamTab.ISO) }
        tabIso.setOnLongClickListener {
            val msg = if (cameraController.currentLensHasDcg) {
                "Main Sensor (1x): Hardware Dual Conversion Gain (DCG) supported"
            } else {
                "${cameraController.currentLens.label} Sensor: Single Conversion Gain (SCG, DCG not supported)"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            true
        }
        updateIsoTabIndicator()

        val initialTab = try {
            ParamTab.valueOf(prefs.activeParamTabName)
        } catch (_: Exception) {
            ParamTab.SHUTTER
        }
        setActiveTab(initialTab)
    }

    private fun updateIsoTabIndicator() {
        if (cameraController.currentLensHasDcg) {
            val fullText = "ISO • DCG"
            val spannable = SpannableString(fullText)
            val cyan = ContextCompat.getColor(this, R.color.cyan_accent)
            val dotIndex = fullText.indexOf("•")
            if (dotIndex >= 0) {
                spannable.setSpan(ForegroundColorSpan(cyan), dotIndex, fullText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(RelativeSizeSpan(0.82f), dotIndex, fullText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            tabIso.text = spannable
        } else {
            tabIso.text = getString(R.string.label_iso)
        }
    }

    private fun setActiveTab(tab: ParamTab) {
        activeParamTab = tab
        prefs.activeParamTabName = tab.name
        val tabs = listOf(
            Pair(ParamTab.WB, tabWb),
            Pair(ParamTab.SHUTTER, tabShutter),
            Pair(ParamTab.EV, tabEv),
            Pair(ParamTab.ISO, tabIso)
        )
        for ((t, view) in tabs) {
            val pStart = view.paddingStart
            val pTop = view.paddingTop
            val pEnd = view.paddingEnd
            val pBottom = view.paddingBottom
            if (t == tab) {
                view.setBackgroundResource(R.drawable.bg_param_pill_active)
            } else {
                view.background = null
            }
            view.setPaddingRelative(pStart, pTop, pEnd, pBottom)
        }
        updateDialForTab()
        updateAutoManualToggleForActiveTab()
    }

    // ── Dial Strip ──

    private fun setupDialStrip() {
        dialStrip.dialOrientation = DialStripView.Orientation.HORIZONTAL
        dialStrip.setRange(0, shutterSpeeds.size - 1, shutterSpeeds.size - 1)
        dialStrip.setValue(currentShutterIndex)
        dialStrip.dcgThresholdIndex = -1
        badgeDcg.setOnClickListener {
            val msg = if (cameraController.currentLensHasDcg) {
                val iso = isoValues[currentIsoIndex.coerceIn(0, isoValues.size - 1)]
                val mode = if (iso >= 400) "HCG (High Conversion Gain, ISO 400+)" else "LCG (Low Conversion Gain, ISO 50-399)"
                "Main Sensor (1x): Hardware DCG active — $mode"
            } else {
                "${cameraController.currentLens.label} Sensor: Single Conversion Gain (SCG, hardware DCG not present)"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        updateDialValueLabel()

        dialStrip.onUserDragStarted = {
            when (activeParamTab) {
                ParamTab.SHUTTER -> {
                    if (cameraController.isShutterAuto) {
                        cameraController.isShutterAuto = false
                        updateAutoManualToggleForActiveTab()
                    }
                    // Manual shutter overrides AE lock — dismiss exposure reticle
                    if (cameraController.isAeLocked) {
                        cameraController.unlockExposure()
                        focusExposureOverlay.dismissExposure()
                    }
                }
                ParamTab.ISO -> {
                    if (cameraController.isIsoAuto) {
                        cameraController.isIsoAuto = false
                        updateAutoManualToggleForActiveTab()
                    }
                    // Manual ISO overrides AE lock — dismiss exposure reticle
                    if (cameraController.isAeLocked) {
                        cameraController.unlockExposure()
                        focusExposureOverlay.dismissExposure()
                    }
                }
                ParamTab.WB -> {
                    if (cameraController.isWbAuto) {
                        cameraController.isWbAuto = false
                        updateAutoManualToggleForActiveTab()
                    }
                }
                ParamTab.EV -> {
                    // EV dial does not alter Shutter/ISO/WB auto state
                }
            }
        }

        dialStrip.onValueChanged = { index, _ ->
            if (dialStrip.isUserInteracting) {
                when (activeParamTab) {
                    ParamTab.SHUTTER -> {
                        val newIdx = index.coerceIn(0, shutterSpeeds.size - 1)
                        if (newIdx != currentShutterIndex) {
                            currentShutterIndex = newIdx
                            applyShutterFromDial()
                        }
                    }
                    ParamTab.ISO -> {
                        val newIdx = index.coerceIn(0, isoValues.size - 1)
                        if (newIdx != currentIsoIndex) {
                            currentIsoIndex = newIdx
                            applyIsoFromDial()
                        }
                    }
                    ParamTab.EV -> {
                        val newIdx = index.coerceIn(0, evValues.size - 1)
                        if (newIdx != currentEvIndex) {
                            currentEvIndex = newIdx
                            val targetEv = evValues[currentEvIndex]
                            cameraController.setEvCompensationValue(targetEv)
                        }
                    }
                    ParamTab.WB -> {
                        val newIdx = index.coerceIn(0, wbValues.size - 1)
                        if (newIdx != currentWbIndex) {
                            currentWbIndex = newIdx
                            applyWbFromDial()
                        }
                    }
                }
                updateAutoManualToggleForActiveTab()
                updateDialValueLabel()
            }
        }
    }

    private fun updateDialForTab() {
        when (activeParamTab) {
            ParamTab.SHUTTER -> {
                dialStrip.setRange(0, shutterSpeeds.size - 1, shutterSpeeds.size - 1)
                dialStrip.setValue(currentShutterIndex)
                dialStrip.dcgThresholdIndex = -1
            }
            ParamTab.ISO -> {
                dialStrip.setRange(0, isoValues.size - 1, isoValues.size - 1)
                dialStrip.setValue(currentIsoIndex)
                dialStrip.dcgThresholdIndex = if (cameraController.currentLensHasDcg) isoValues.indexOf(400) else -1
            }
            ParamTab.EV -> {
                dialStrip.setRange(0, evValues.size - 1, evValues.size - 1)
                dialStrip.setValue(currentEvIndex)
                dialStrip.dcgThresholdIndex = -1
            }
            ParamTab.WB -> {
                dialStrip.setRange(0, wbValues.size - 1, wbValues.size - 1)
                dialStrip.setValue(currentWbIndex)
                dialStrip.dcgThresholdIndex = -1
            }
        }
        updateDialValueLabel()
    }

    private fun updateDialValueLabel() {
        when (activeParamTab) {
            ParamTab.SHUTTER -> {
                textDialValue.text = formatShutterSpeed(shutterSpeeds[currentShutterIndex.coerceIn(0, shutterSpeeds.size - 1)])
                badgeDcg.visibility = View.GONE
            }
            ParamTab.ISO -> {
                val iso = isoValues[currentIsoIndex.coerceIn(0, isoValues.size - 1)]
                textDialValue.text = "ISO $iso"
                badgeDcg.visibility = View.VISIBLE
                updateDcgBadge(iso)
            }
            ParamTab.EV -> {
                val ev = evValues[currentEvIndex.coerceIn(0, evValues.size - 1)]
                val evFormatted = if (ev >= 0f) "+%.1f".format(Locale.US, ev) else "%.1f".format(Locale.US, ev)
                textDialValue.text = "EV $evFormatted"
                badgeDcg.visibility = View.GONE
            }
            ParamTab.WB -> {
                textDialValue.text = "${wbValues[currentWbIndex.coerceIn(0, wbValues.size - 1)]}K"
                badgeDcg.visibility = View.GONE
            }
        }
    }

    private fun updateDcgBadge(iso: Int) {
        if (cameraController.currentLensHasDcg) {
            val isHcg = iso >= 400
            if (isHcg) {
                badgeDcg.text = "HCG"
                badgeDcg.setBackgroundResource(R.drawable.bg_dcg_badge_hcg)
                badgeDcg.setTextColor(ContextCompat.getColor(this, R.color.perf_cpu_orange))
            } else {
                badgeDcg.text = "LCG"
                badgeDcg.setBackgroundResource(R.drawable.bg_dcg_badge_lcg)
                badgeDcg.setTextColor(ContextCompat.getColor(this, R.color.cyan_accent))
            }
        } else {
            badgeDcg.text = "SCG"
            badgeDcg.setBackgroundResource(R.drawable.bg_dcg_badge_scg)
            badgeDcg.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun applyShutterFromDial() {
        val speed = shutterSpeeds[currentShutterIndex.coerceIn(0, shutterSpeeds.size - 1)]
        val shutterNs = (1_000_000_000L / speed).coerceAtLeast(100_000L)
        cameraController.setShutter(shutterNs, isAuto = false)
    }

    private fun applyIsoFromDial() {
        val iso = isoValues[currentIsoIndex.coerceIn(0, isoValues.size - 1)]
        cameraController.setIso(iso, isAuto = false)
    }

    private fun applyWbFromDial() {
        val kelvin = wbValues[currentWbIndex.coerceIn(0, wbValues.size - 1)]
        cameraController.setWhiteBalance(kelvin, isAuto = false)
    }

    // ── Focus Dial ──

    private fun setupFocusDial() {
        focusDialStrip.dialOrientation = DialStripView.Orientation.VERTICAL
        focusDialStrip.setRange(0, 100, 50)
        val activeMax = cameraController.getActiveSensorProfile().minFocusDistanceDiopters.coerceAtLeast(0.1f)
        val initialFocusIndex = ((cameraController.targetFocusDiopter / activeMax) * 100).roundToInt().coerceIn(0, 100)
        focusDialStrip.setValue(initialFocusIndex)
        btnFocusMode.text = if (isFocusAuto) getString(R.string.label_auto) else getString(R.string.label_manual)

        focusDialStrip.onUserDragStarted = {
            if (isFocusAuto) {
                isFocusAuto = false
                cameraController.isFocusAuto = false
                btnFocusMode.text = getString(R.string.label_manual)
                // Switching to manual focus dismisses the tap-to-focus reticle
                focusExposureOverlay.dismissFocus()
            }
        }

        focusDialStrip.onValueChanged = { index, _ ->
            if (focusDialStrip.isUserInteracting) {
                if (isFocusAuto) {
                    isFocusAuto = false
                    cameraController.isFocusAuto = false
                    btnFocusMode.text = getString(R.string.label_manual)
                }
                val maxD = cameraController.getActiveSensorProfile().minFocusDistanceDiopters.coerceAtLeast(0.1f)
                val diopter = (index / 100f) * maxD
                cameraController.setFocusDiopter(diopter)
            }
        }

        btnFocusMode.setOnClickListener {
            isFocusAuto = !isFocusAuto
            cameraController.isFocusAuto = isFocusAuto
            btnFocusMode.text = if (isFocusAuto) getString(R.string.label_auto) else getString(R.string.label_manual)
            if (!isFocusAuto) {
                val maxD = cameraController.getActiveSensorProfile().minFocusDistanceDiopters.coerceAtLeast(0.1f)
                val diopter = (focusDialStrip.getCurrentIndex() / 100f) * maxD
                cameraController.setFocusDiopter(diopter)
            }
        }
    }

    // ── Auto / Manual Toggle ──

    private fun updateAutoManualToggleForActiveTab() {
        val isPureManual = !cameraController.isShutterAuto && !cameraController.isIsoAuto
        when (activeParamTab) {
            ParamTab.SHUTTER -> {
                btnAutoManualToggle.isEnabled = true
                btnAutoManualToggle.alpha = 1.0f
                btnAutoManualToggle.text = if (cameraController.isShutterAuto) getString(R.string.label_auto) else getString(R.string.label_manual)
            }
            ParamTab.ISO -> {
                btnAutoManualToggle.isEnabled = true
                btnAutoManualToggle.alpha = 1.0f
                btnAutoManualToggle.text = if (cameraController.isIsoAuto) getString(R.string.label_auto) else getString(R.string.label_manual)
            }
            ParamTab.WB -> {
                btnAutoManualToggle.isEnabled = true
                btnAutoManualToggle.alpha = 1.0f
                btnAutoManualToggle.text = if (cameraController.isWbAuto) getString(R.string.label_auto) else getString(R.string.label_manual)
            }
            ParamTab.EV -> {
                btnAutoManualToggle.isEnabled = true
                btnAutoManualToggle.alpha = 1.0f
                val isEvManual = isPureManual || evValues[currentEvIndex.coerceIn(0, evValues.size - 1)] != 0f || cameraController.targetEvCompensation != 0
                btnAutoManualToggle.text = if (isEvManual) getString(R.string.label_manual) else getString(R.string.label_auto)
            }
        }
    }

    private fun setupAutoManualToggle() {
        updateAutoManualToggleForActiveTab()

        btnAutoManualToggle.setOnClickListener {
            when (activeParamTab) {
                ParamTab.SHUTTER -> {
                    if (cameraController.isShutterAuto) {
                        // Switch to manual: default to 180° shutter for current framerate
                        val shutter180Idx = get180ShutterIndexForFramerate(cameraController.currentFramerate)
                        currentShutterIndex = shutter180Idx
                        val speed = shutterSpeeds[currentShutterIndex]
                        val shutterNs = (1_000_000_000L / speed).coerceAtLeast(100_000L)
                        cameraController.setShutter(shutterNs, isAuto = false)
                        dialStrip.setValue(currentShutterIndex)
                    } else {
                        cameraController.isShutterAuto = true
                        cameraController.applySettings()
                    }
                }
                ParamTab.ISO -> {
                    if (cameraController.isIsoAuto) {
                        // Switch to manual: seed target ISO from live ISO
                        val iso = isoValues[currentIsoIndex.coerceIn(0, isoValues.size - 1)]
                        cameraController.setIso(iso, isAuto = false)
                    } else {
                        cameraController.isIsoAuto = true
                        cameraController.applySettings()
                    }
                }
                ParamTab.WB -> {
                    if (cameraController.isWbAuto) {
                        // Switch to manual: seed target Kelvin from live estimated Kelvin
                        val kelvin = wbValues[currentWbIndex.coerceIn(0, wbValues.size - 1)]
                        cameraController.setWhiteBalance(kelvin, isAuto = false)
                    } else {
                        cameraController.isWbAuto = true
                        cameraController.applySettings()
                    }
                }
                ParamTab.EV -> {
                    // Clicking M resets EV compensation to 0.0 (Auto mode)
                    cameraController.setEvCompensationValue(0f)
                    val zeroIdx = evValues.indexOfFirst { it == 0f }.coerceAtLeast(0)
                    currentEvIndex = zeroIdx
                    dialStrip.setCurrentIndex(zeroIdx)
                }
            }
            updateAutoManualToggleForActiveTab()
            updateDialValueLabel()
        }
    }

    // ── Live Telemetry Synchronization (Auto Mode) ──

    private fun setupLiveTelemetry() {
        cameraController.onLiveTelemetry = { iso, shutterNs, focusDiopter, kelvin, ev ->
            runOnUiThread {
                // 1. Live Shutter tracking
                if (cameraController.isShutterAuto) {
                    val speed = if (shutterNs > 0) (1_000_000_000.0 / shutterNs).roundToInt() else 60
                    val closestIdx = findClosestShutterIndex(speed)
                    currentShutterIndex = closestIdx
                    if (activeParamTab == ParamTab.SHUTTER) {
                        textDialValue.text = formatShutterSpeed(speed)
                        dialStrip.setCurrentIndex(closestIdx)
                    }
                }

                // 2. Live ISO tracking
                if (cameraController.isIsoAuto) {
                    val closestIdx = findClosestIsoIndex(iso)
                    currentIsoIndex = closestIdx
                    if (activeParamTab == ParamTab.ISO) {
                        textDialValue.text = "ISO $iso"
                        dialStrip.setCurrentIndex(closestIdx)
                        badgeDcg.visibility = View.VISIBLE
                        updateDcgBadge(iso)
                    }
                }

                // 3. Live WB tracking
                if (cameraController.isWbAuto) {
                    val closestIdx = findClosestWbIndex(kelvin)
                    currentWbIndex = closestIdx
                    if (activeParamTab == ParamTab.WB) {
                        textDialValue.text = "${kelvin}K"
                        dialStrip.setCurrentIndex(closestIdx)
                    }
                }

                // 4. Focus tracking
                if (isFocusAuto) {
                    val maxD = cameraController.getActiveSensorProfile().minFocusDistanceDiopters.coerceAtLeast(0.1f)
                    val focusPercent = ((focusDiopter / maxD) * 100f).roundToInt().coerceIn(0, 100)
                    focusDialStrip.setValue(focusPercent)
                }

                // 6. EV Meter Visualizer tracking (smoothly glides to current sitting exposure level)
                evMeterView.setExposureLevel(ev)
            }
        }
    }

    // ── Focus & Exposure Overlay ──

    private fun setupFocusExposureOverlay() {
        // Single tap → Autofocus at touch point
        focusExposureOverlay.onTapFocus = { normX, normY ->
            // Ensure we're in AF-auto mode
            isFocusAuto = true
            cameraController.isFocusAuto = true
            btnFocusMode.text = getString(R.string.label_auto)

            cameraController.triggerTapToFocus(normX, normY) { afState ->
                runOnUiThread {
                    when (afState) {
                        CameraController.AfState.FOCUSED_LOCKED -> {
                            focusExposureOverlay.setFocusLocked(success = true)
                        }
                        CameraController.AfState.NOT_FOCUSED_LOCKED -> {
                            focusExposureOverlay.setFocusLocked(success = false)
                        }
                        else -> { /* SCANNING or IDLE: no visual change needed */ }
                    }
                }
            }
        }

        // Long press (hold) → Instantaneous AE Lock / Unlock toggle (locks actual exposure)
        focusExposureOverlay.onHoldExposure = { _, _ ->
            if (cameraController.isAeLocked) {
                cameraController.unlockExposure()
                runOnUiThread {
                    focusExposureOverlay.dismissExposure()
                    updateAutoManualToggleForActiveTab()
                    updateDialValueLabel()
                }
            } else {
                cameraController.lockExposureInstantaneous()
                runOnUiThread {
                    focusExposureOverlay.setExposureLocked(locked = true)
                    // Sync live ISO/shutter dial positions and labels to reflect locked exposure
                    val lockedShutter = cameraController.targetShutterNs
                    val lockedIso = cameraController.targetIso
                    if (lockedShutter > 0) {
                        val speed = (1_000_000_000.0 / lockedShutter).roundToInt()
                        currentShutterIndex = findClosestShutterIndex(speed)
                    }
                    currentIsoIndex = findClosestIsoIndex(lockedIso)
                    updateAutoManualToggleForActiveTab()
                    updateDialValueLabel()
                }
            }
        }

        // Long press toggle off → Unlock AE
        focusExposureOverlay.onUnlockExposure = {
            cameraController.unlockExposure()
            runOnUiThread {
                updateAutoManualToggleForActiveTab()
                updateDialValueLabel()
            }
        }

        // Double tap → reset both AF and AE to full-scene continuous mode
        focusExposureOverlay.onResetAfAe = {
            cameraController.resetFocusAndExposure()
            runOnUiThread {
                updateAutoManualToggleForActiveTab()
                updateDialValueLabel()
            }
        }
    }

    // ── Performance Monitor ──

    private fun setupPerformanceMonitor() {
        performanceMonitor = PerformanceMonitor(this).apply {
            isLutEnabled = isLutActive
            isRecording = isRecording
            onPerformanceUpdate = { cpuPercent, ramPercent, gpuPercent ->
                runOnUiThread {
                    performanceBarsView.setPerformance(cpuPercent, ramPercent, gpuPercent)
                }
            }
            start()
        }
    }

    private fun setBarFillPercent(fillView: View, percent: Float) {
        val parent = fillView.parent as? ViewGroup ?: return
        val parentHeight = parent.height
        if (parentHeight <= 0) return
        val fillHeight = (parentHeight * (percent / 100f).coerceIn(0f, 1f)).toInt()
        val params = fillView.layoutParams
        params.height = fillHeight
        fillView.layoutParams = params
    }

    // ── Audio Pipeline ──

    private fun setupAudioPipeline() {
        if (!hasPermissions()) return
        if (audioCapturePipeline != null) return

        audioCapturePipeline = AudioCapturePipeline(audioInputManager).apply {
            onAudioLevels = { leftDbfs, rightDbfs ->
                if (!debugAudioOverride) {
                    runOnUiThread {
                        updateAudioMeters(leftDbfs, rightDbfs)
                    }
                }
            }
            startCapture()
        }
    }

    private fun updateAudioMeters(leftDbfs: Float, rightDbfs: Float) {
        // dBFS ranges from ~ -60 dBFS (silence) to 0 dBFS (full scale)
        val percentL = ((leftDbfs + 60f) / 60f * 100f).coerceIn(0f, 100f)
        val percentR = ((rightDbfs + 60f) / 60f * 100f).coerceIn(0f, 100f)
        setBarFillPercent(barAudioLFill, percentL)
        setBarFillPercent(barAudioRFill, percentR)

        barAudioLFill.backgroundTintList = getAudioLevelColorStateList(leftDbfs)
        barAudioRFill.backgroundTintList = getAudioLevelColorStateList(rightDbfs)
    }

    private fun getAudioLevelColorStateList(dbfs: Float): ColorStateList {
        return when {
            dbfs >= -2.0f -> cslAudioRed     // Red: clipping (>= -2 dBFS)
            dbfs >= -12.0f -> cslAudioOrange // Orange: danger zone (-12 to -2 dBFS)
            else -> cslAudioGreen            // Green: good levels (< -12 dBFS)
        }
    }

    private fun hasPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
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

    // ── Remaining Time ──

    private fun updateRemainingTime() {
        try {
            val target = currentStorageTarget ?: storageTargetManager.getInternalTarget()
            val availableBytes = target.availableBytes
            val bytesPerSecond = currentBitratePreset.targetBps / 8L
            val remainingSeconds = if (bytesPerSecond > 0) availableBytes / bytesPerSecond else 0L
            val remainingMinutes = (remainingSeconds / 60).toInt()
            textRemainingMinutes.text = remainingMinutes.toString()
            textStorageBadge.text = if (target.isPrimary) "INT" else "USB"
        } catch (e: Exception) {
            textRemainingMinutes.text = "–"
        }
    }

    // ── Thermal Monitoring ──

    private fun setupThermalMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                when (status) {
                    PowerManager.THERMAL_STATUS_NONE -> Log.d(TAG, "Thermal status: NONE")
                    PowerManager.THERMAL_STATUS_LIGHT -> Log.i(TAG, "Thermal status: LIGHT")
                    PowerManager.THERMAL_STATUS_MODERATE -> {
                        Log.w(TAG, "Thermal status: MODERATE")
                        runOnUiThread {
                            Toast.makeText(this, "Device warm (MODERATE thermal)", Toast.LENGTH_SHORT).show()
                        }
                    }
                    PowerManager.THERMAL_STATUS_SEVERE,
                    PowerManager.THERMAL_STATUS_CRITICAL,
                    PowerManager.THERMAL_STATUS_EMERGENCY,
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> {
                        Log.e(TAG, "Thermal status: THROTTLING ($status)")
                        runOnUiThread {
                            Toast.makeText(this, "THERMAL THROTTLING: Device is hot!", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            thermalListener?.let { listener ->
                powerManager?.addThermalStatusListener(mainExecutor, listener)
            }
        }
    }

    // ── Sidecar JSON (preserved from original) ──

    private fun generateSidecarJson(videoFile: File, durationMs: Long, gcsvFile: File? = null): File? {
        return try {
            val jsonFile = File(videoFile.parentFile, "${videoFile.nameWithoutExtension}.json")
            val root = JSONObject()

            root.put("clip_name", videoFile.name)
            root.put("format_version", "1.0")
            root.put("created_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(recordStartTimeMs)))

            val devObj = JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("device", Build.DEVICE)
                put("product", Build.PRODUCT)
                put("board", Build.BOARD)
                put("hardware", Build.HARDWARE)
                put("android_version", Build.VERSION.RELEASE)
                put("api_level", Build.VERSION.SDK_INT)
            }
            root.put("device_info", devObj)

            val rawLutItem = lutCatalog.getOrNull(currentLutIndex)
            val activeLutItem = getEffectiveLutItem(rawLutItem)
            val isBakeValid = isBakeLutActive && activeLutItem != null && activeLutItem.id != "clean"
            val standardStr = if (isBakeValid) {
                if (activeLutItem?.targetColorStandard == MediaFormat.COLOR_STANDARD_BT2020) "BT.2020" else "BT.709"
            } else {
                "BT.2020"
            }
            val transferStr = if (isBakeValid) {
                when (activeLutItem?.targetColorTransfer) {
                    MediaFormat.COLOR_TRANSFER_HLG -> "ARIB STD-B67 / ITU-R BT.2100 HLG"
                    MediaFormat.COLOR_TRANSFER_LINEAR -> "Scene-Linear (Unclipped)"
                    else -> "ITU-R BT.709 (SDR)"
                }
            } else {
                if (currentColorTransfer == PixelLogEncoderPipeline.ColorTransferMode.HLG) "ARIB STD-B67 / ITU-R BT.2100 HLG" else LogParams.CURVE_NAME
            }
            val transferModeStr = if (isBakeValid) {
                if (activeLutItem?.targetColorTransfer == MediaFormat.COLOR_TRANSFER_HLG) "HLG" else "SDR"
            } else {
                currentColorTransfer.label
            }

            val vidObj = JSONObject().apply {
                put("codec", currentCodec.displayName)
                put("mime_type", currentCodec.mimeType)
                put("width", encoderPipeline?.width ?: cameraController.activeWidth)
                put("height", encoderPipeline?.height ?: cameraController.activeHeight)
                put("frame_rate", cameraController.currentFramerate.fps)
                put("bitrate_preset", currentBitratePreset.label)
                put("target_bitrate_bps", currentBitratePreset.targetBps)
                put("color_standard", standardStr)
                put("color_range", "Limited (64-940 / 64-960)")
                put("color_transfer", transferStr)
                put("color_transfer_mode", transferModeStr)
                put("lut_baked", isBakeValid)
                put("active_lut", activeLutItem?.displayName ?: if (isLutActive) "REC.709" else "CLEAN")
                put("bit_depth", 10)
            }
            root.put("video", vidObj)

            val camObj = JSONObject().apply {
                put("camera_id", cameraController.currentCameraId)
                put("lens_zoom", cameraController.currentLens.label)
                put("focal_length_equiv_mm", cameraController.currentLens.focalLengthEquivMm)
                put("is_crop", cameraController.currentLens.isCrop || cameraController.stabilizationMode.isCrop)
                put("crop_factor", if (cameraController.stabilizationMode.isCrop) 1.18 else cameraController.currentLens.cropFactor.toDouble())
                put("cfa_pattern", if (cameraController.bayerPattern == 0) "RGGB" else "CFA_${cameraController.bayerPattern}")
                put("iso", cameraController.lastIso)
                put("shutter_ns", cameraController.lastExposureNs)
                val shutterAngle = ((cameraController.lastExposureNs.toDouble() / cameraController.currentFramerate.frameDurationNs.toDouble()) * 360.0).roundToInt()
                put("shutter_angle_deg", shutterAngle)
                put("kelvin", cameraController.targetKelvin)
                put("tint", cameraController.targetTint)
                put("stabilization_mode", cameraController.stabilizationMode.name)
                put("gyroflow_active", cameraController.stabilizationMode == CameraController.StabilizationMode.GYRO)
                put("rolling_shutter_skew_ms", cameraController.lastRollingShutterSkewNs / 1_000_000.0)
                if (gcsvFile != null) {
                    put("gyroflow_gcsv_file", gcsvFile.name)
                }
                put("ois_active", cameraController.stabilizationMode.oisMode != CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF && cameraController.currentLensHasOis)
                put("eis_active", cameraController.stabilizationMode.eisMode != CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }
            root.put("camera", camObj)

            val csObj = JSONObject().apply {
                put("curve_name", LogParams.CURVE_NAME)
                put("gamut", LogParams.GAMUT)
                val paramsObj = JSONObject().apply {
                    put("yb", LogParams.YB.toDouble())
                    put("ym", LogParams.YM.toDouble())
                    put("k", LogParams.K.toDouble())
                    put("xmax", LogParams.XMAX.toDouble())
                    put("beta", LogParams.BETA.toDouble())
                    put("gamma", LogParams.GAMMA.toDouble())
                    put("delta", LogParams.DELTA.toDouble())
                    put("s", LogParams.S.toDouble())
                }
                put("curve_params", paramsObj)

                put("dynamic_black_level", JSONArray(cameraController.lastDynamicBlackLevel.map { it.toDouble() }))
                put("white_level", cameraController.lastWhiteLevel.toDouble())
                put("neutral_color_point", JSONArray(cameraController.lastNeutralColorPoint.map { it.toDouble() }))

                val calib = cameraController.currentCalibration
                if (calib != null) {
                    put("forward_matrix1", JSONArray(calib.forwardMatrix1.map { it.toDouble() }))
                    put("forward_matrix2", JSONArray(calib.forwardMatrix2.map { it.toDouble() }))
                    put("calibration_transform1", JSONArray(calib.calibrationTransform1.map { it.toDouble() }))
                    put("calibration_transform2", JSONArray(calib.calibrationTransform2.map { it.toDouble() }))
                }
                put("composite_matrix_3x3", JSONArray(cameraController.lastCompositeMatrix.map { it.toDouble() }))
            }
            root.put("color_science", csObj)

            val statsObj = JSONObject().apply {
                put("duration_ms", durationMs)
                val estFrames = (durationMs * cameraController.currentFramerate.fps / 1000.0).roundToInt()
                put("estimated_frames", estFrames)
            }
            root.put("stats", statsObj)

            try {
                jsonFile.writeText(root.toString(2))
                Log.i(TAG, "Sidecar metadata written: ${jsonFile.absolutePath}")
                jsonFile
            } catch (ioe: Exception) {
                Log.w(TAG, "Direct write to ${jsonFile.absolutePath} failed (${ioe.message}), writing to fallback")
                val fallbackDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
                fallbackDir.mkdirs()
                val fallbackFile = File(fallbackDir, "${videoFile.nameWithoutExtension}.json")
                fallbackFile.writeText(root.toString(2))
                Log.i(TAG, "Sidecar metadata written to fallback: ${fallbackFile.absolutePath}")
                fallbackFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write sidecar metadata", e)
            null
        }
    }

    // ── Lifecycle & Window Insets ──
 
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    private fun hideSystemBars() {
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) stopRecording()
        mainHandler.removeCallbacks(timecodeRunnable)
        mainHandler.removeCallbacks(remainingTimeRunnable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
            powerManager?.removeThermalStatusListener(thermalListener!!)
            thermalListener = null
        }
        try {
            unregisterReceiver(testCommandReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(storageReceiver)
        } catch (_: Exception) {}
        performanceMonitor?.destroy()
        performanceMonitor = null
        audioCapturePipeline?.stopCapture()
        audioCapturePipeline = null
        audioInputManager.release()
        cameraController.stop()
        engine.destroy()
    }
}
