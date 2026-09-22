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
import com.pixellog.recording.PixelLogEncoderPipeline
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
                        updateSettingsDisplay()
                    }
                }
                "com.pixellog.SET_BAKE_LUT" -> {
                    val enabled = intent.getBooleanExtra("enabled", false)
                    runOnUiThread {
                        isBakeLutActive = enabled
                        prefs.isBakeLutActive = enabled
                        engine.setBakeLutToEncoder(enabled)
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
                    val codecStr = intent.getStringExtra("codec") ?: "HEVC"
                    try {
                        val codec = PixelLogEncoderPipeline.VideoCodec.valueOf(codecStr)
                        runOnUiThread {
                            currentCodec = codec
                            prefs.codec = codec
                            updateSettingsDisplay()
                        }
                    } catch (_: Exception) {}
                }
                "com.pixellog.SET_CURVE" -> {
                    val curve = intent.getIntExtra("curve", 0).coerceIn(0, 2)
                    runOnUiThread {
                        currentLogCurveType = curve
                        prefs.logCurveType = curve
                        engine.setLogCurveType(curve)
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
        val file: File? = null
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
    private var currentLogCurveType = 0

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
    private lateinit var textSettingsCodec: TextView
    private lateinit var textSettingsResolution: TextView
    private lateinit var textSettingsCurve: TextView
    private lateinit var textSettingsLut: TextView
    private lateinit var textSettingsTorch: TextView
    private lateinit var textSettingsMic: TextView
    private lateinit var textSettingsTransfer: TextView
    private lateinit var textSettingsBakeLut: TextView
    private lateinit var textSettingsSidecar: TextView
    private lateinit var textSettingsImportLut: TextView
    private lateinit var textSettingsStab: TextView

    // Stabilization switches (above Lens Selector)
    private lateinit var btnStabOff: TextView
    private lateinit var btnStabOis: TextView
    private lateinit var btnStabEis: TextView
    private lateinit var btnStabFull: TextView

    // Lens buttons
    private lateinit var btnLens12: TextView
    private lateinit var btnLens24: TextView
    private lateinit var btnLens120: TextView

    // Parameter tabs
    private lateinit var tabWb: TextView
    private lateinit var tabShutter: TextView
    private lateinit var tabEv: TextView
    private lateinit var tabIso: TextView

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

    // Shutter speed presets (as 1/x, ordered from fastest to slowest)
    // Includes all standard cinema 180° speeds: 120 (60p), 100 (50p), 96 (48p), 60 (30p), 50 (25p), 48 (24p)
    private val shutterSpeeds = intArrayOf(
        8000, 4000, 2000, 1000, 500, 250, 125, 120, 100, 96, 60, 50, 48, 30, 25, 24, 15, 8, 4, 2
    )
    // ISO presets
    private val isoValues = intArrayOf(50, 64, 100, 125, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 3200)
    // EV presets (-5 to +5)
    private val evValues = floatArrayOf(-5f, -4f, -3f, -2.5f, -2f, -1.5f, -1f, -0.5f, 0f, 0.5f, 1f, 1.5f, 2f, 2.5f, 3f)
    // WB Kelvin presets
    private val wbValues = intArrayOf(2000, 2500, 2800, 3200, 3500, 4000, 4500, 5000, 5600, 6000, 6500, 7000, 7500, 8000, 10000)

    private fun get180ShutterIndexForFramerate(config: CameraController.FramerateConfig): Int {
        val targetSpeed = config.shutter180Speed
        val idx = shutterSpeeds.indexOf(targetSpeed)
        return if (idx >= 0) idx else {
            shutterSpeeds.indices.minByOrNull { kotlin.math.abs(shutterSpeeds[it] - targetSpeed) } ?: 0
        }
    }

    private fun applyFramerate(config: CameraController.FramerateConfig) {
        cameraController.setFramerate(config)
        prefs.framerate = config

        // Default shutter speed to 180° for the new framerate
        val shutter180Idx = get180ShutterIndexForFramerate(config)
        currentShutterIndex = shutter180Idx
        prefs.shutterIndex = currentShutterIndex
        val speed = shutterSpeeds[currentShutterIndex]
        val shutterNs = (1_000_000_000L / speed).coerceAtLeast(100_000L)
        cameraController.targetShutterNs = shutterNs
        if (activeParamTab == ParamTab.SHUTTER) {
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

        // Restore persisted state variables
        currentCodec = prefs.codec
        currentBitratePreset = prefs.bitratePreset
        currentColorTransfer = prefs.colorTransfer
        isBakeLutActive = prefs.isBakeLutActive
        isSidecarEnabled = prefs.isSidecarEnabled
        currentLogCurveType = prefs.logCurveType
        
        val default180Idx = get180ShutterIndexForFramerate(prefs.framerate)
        val savedShutterIdx = prefs.shutterIndex
        currentShutterIndex = if (prefs.isShutterAuto || savedShutterIdx !in shutterSpeeds.indices) {
            default180Idx
        } else {
            savedShutterIdx
        }
        currentIsoIndex = prefs.isoIndex.coerceIn(0, isoValues.size - 1)
        currentEvIndex = prefs.evIndex.coerceIn(0, evValues.size - 1)
        currentWbIndex = prefs.wbIndex.coerceIn(0, wbValues.size - 1)
        isFocusAuto = prefs.isFocusAuto

        // Seed CameraController with persisted configuration
        cameraController.setInitialLensAndFramerate(prefs.lens, prefs.framerate)
        cameraController.isShutterAuto = prefs.isShutterAuto
        cameraController.isIsoAuto = prefs.isIsoAuto
        cameraController.isWbAuto = prefs.isWbAuto
        cameraController.isFocusAuto = prefs.isFocusAuto

        val initialSpeed = shutterSpeeds[currentShutterIndex]
        cameraController.targetShutterNs = (1_000_000_000L / initialSpeed).coerceAtLeast(100_000L)
        cameraController.targetIso = isoValues[currentIsoIndex]
        cameraController.targetKelvin = wbValues[currentWbIndex]
        cameraController.targetEvCompensation = (evValues[currentEvIndex] * 2).roundToInt()
        cameraController.targetFocusDiopter = prefs.focusDiopter
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
        textSettingsCodec = findViewById(R.id.textSettingsCodec)
        textSettingsResolution = findViewById(R.id.textSettingsResolution)
        textSettingsCurve = findViewById(R.id.textSettingsCurve)
        textSettingsLut = findViewById(R.id.textSettingsLut)
        textSettingsTorch = findViewById(R.id.textSettingsTorch)
        textSettingsMic = findViewById(R.id.textSettingsMic)
        textSettingsTransfer = findViewById(R.id.textSettingsTransfer)
        textSettingsBakeLut = findViewById(R.id.textSettingsBakeLut)
        textSettingsSidecar = findViewById(R.id.textSettingsSidecar)
        textSettingsImportLut = findViewById(R.id.textSettingsImportLut)
        textSettingsStab = findViewById(R.id.textSettingsStab)

        btnStabOff = findViewById(R.id.btnStabOff)
        btnStabOis = findViewById(R.id.btnStabOis)
        btnStabEis = findViewById(R.id.btnStabEis)
        btnStabFull = findViewById(R.id.btnStabFull)

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
        engine.setLogCurveType(currentLogCurveType)
        refreshLutCatalog()
        val savedLutId = prefs.selectedLutId
        val targetIdx = lutCatalog.indexOfFirst { it.id == savedLutId }.takeIf { it >= 0 } ?: 0
        selectLut(targetIdx)
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
        lutCatalog.add(LutItem("rec709", "REC.709", assetPath = "luts/PixelLog_to_Rec709_Display.cube"))
        lutCatalog.add(LutItem("agx_base", "AGX FILM", assetPath = "luts/PixelLog_to_AgX_Base.cube"))
        lutCatalog.add(LutItem("agx_punchy", "AGX PUNCH", assetPath = "luts/PixelLog_to_AgX_Punchy.cube"))
        lutCatalog.add(LutItem("dwg", "DWG", assetPath = "luts/PixelLog_to_DWG_Intermediate.cube"))
        lutCatalog.add(LutItem("acescg", "ACEScg", assetPath = "luts/PixelLog_to_ACEScg.cube"))
        lutCatalog.add(LutItem("linear", "LINEAR", assetPath = "luts/PixelLog_to_Rec2020_Linear.cube"))

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

    private fun selectLut(index: Int) {
        if (lutCatalog.isEmpty()) return
        currentLutIndex = (index % lutCatalog.size + lutCatalog.size) % lutCatalog.size
        val item = lutCatalog[currentLutIndex]
        prefs.selectedLutId = item.id

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
        prefs.lens = lens
        updateLensButtonsUi(lens)
        updateStabilizationSwitchesUi()
        updateSettingsDisplay()
        updateIsoTabIndicator()
        if (activeParamTab == ParamTab.ISO) {
            updateDialForTab()
            updateDialValueLabel()
        }
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

    // ── Stabilization Mode Switches (OFF / OIS) [EIS and FULL hidden for now] ──

    private fun setupStabilizationSwitches() {
        btnStabOff.setOnClickListener { switchStabilization(CameraController.StabilizationMode.OFF) }
        btnStabOis.setOnClickListener {
            if (!cameraController.currentLensHasOis) {
                Toast.makeText(this, "0.5x Ultra-Wide lacks hardware OIS", Toast.LENGTH_SHORT).show()
            }
            switchStabilization(CameraController.StabilizationMode.OIS)
        }
        btnStabEis.visibility = View.GONE
        btnStabFull.visibility = View.GONE
        updateStabilizationSwitchesUi()
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
        val resDesc = if (safeMode.isCrop) "3468×2600 (Raw Native Crop)" else "4080×3064 (Full Open-Gate)"
        Toast.makeText(this, "Stabilization: ${safeMode.label} • $resDesc", Toast.LENGTH_SHORT).show()
    }

    private fun updateStabilizationSwitchesUi() {
        val currentMode = cameraController.stabilizationMode
        val hasOis = cameraController.currentLensHasOis

        val buttons = listOf(
            Triple(CameraController.StabilizationMode.OFF, btnStabOff, true),
            Triple(CameraController.StabilizationMode.OIS, btnStabOis, hasOis),
            Triple(CameraController.StabilizationMode.EIS, btnStabEis, true),
            Triple(CameraController.StabilizationMode.FULL, btnStabFull, true)
        )

        for ((mode, btn, available) in buttons) {
            if (mode == currentMode) {
                btn.setBackgroundResource(R.drawable.bg_stab_item_active)
                btn.setTextColor(Color.WHITE)
                btn.alpha = 1.0f
            } else {
                btn.background = null
                btn.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                btn.alpha = if (available) 1.0f else 0.4f
            }
        }
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
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val pixDir = File(moviesDir, "PixelLog")
            pixDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val codecTag = if (currentCodec == PixelLogEncoderPipeline.VideoCodec.AV1) "AV1" else "HEVC"
            val transferTag = if (currentColorTransfer == PixelLogEncoderPipeline.ColorTransferMode.HLG) "HLG" else "LOG"
            val bakeTag = if (isBakeLutActive) "BAKED" else "RAW"
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
                colorTransfer = currentColorTransfer
            )

            engine.setBakeLutToEncoder(isBakeLutActive)
            val encoderSurface = encoderPipeline!!.prepare()
            engine.setEncoderSurface(encoderSurface)
            encoderPipeline!!.startRecording()

            isRecording = true
            performanceMonitor?.isRecording = true
            recordStartTimeMs = System.currentTimeMillis()
            btnRecord.setBackgroundResource(R.drawable.bg_record_button_stop)
            mainHandler.post(timecodeRunnable)

            Toast.makeText(this, "Recording started (${encWidth}x${encHeight} ${codecTag} ${transferTag} ${bakeTag})", Toast.LENGTH_SHORT).show()
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

        btnRecord.setBackgroundResource(R.drawable.bg_record_button)

        val recordedFile = encoderPipeline?.outputFile
        val durationMs = System.currentTimeMillis() - recordStartTimeMs
        engine.setEncoderSurface(null)
        encoderPipeline?.stopRecording()
        encoderPipeline = null

        if (recordedFile != null && recordedFile.exists()) {
            val sidecarFile = if (isSidecarEnabled) generateSidecarJson(recordedFile, durationMs) else null
            val filesToScan = if (sidecarFile != null && sidecarFile.exists()) {
                arrayOf(recordedFile.absolutePath, sidecarFile.absolutePath)
            } else {
                arrayOf(recordedFile.absolutePath)
            }
            val mimeTypes = if (filesToScan.size > 1) {
                arrayOf("video/mp4", "application/json")
            } else {
                arrayOf("video/mp4")
            }
            MediaScannerConnection.scanFile(this, filesToScan, mimeTypes) { path, uri ->
                Log.i(TAG, "MediaScanner registered: $path -> $uri")
            }
            val toastMsg = if (sidecarFile != null) "Saved: ${recordedFile.name} (+json)" else "Saved: ${recordedFile.name}"
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

        // FPS tap cycles
        findViewById<View>(R.id.settingsFpsGroup).setOnClickListener {
            val allConfigs = CameraController.FramerateConfig.values()
            val nextIndex = (cameraController.currentFramerate.ordinal + 1) % allConfigs.size
            val nextConfig = allConfigs[nextIndex]
            applyFramerate(nextConfig)
        }

        // Bitrate tap cycles
        findViewById<View>(R.id.settingsBitrateGroup).setOnClickListener {
            val presets = PixelLogEncoderPipeline.BitratePreset.values()
            val nextIdx = (currentBitratePreset.ordinal + 1) % presets.size
            currentBitratePreset = presets[nextIdx]
            prefs.bitratePreset = currentBitratePreset
            encoderPipeline?.setDynamicBitrate(currentBitratePreset.targetBps)
            updateSettingsDisplay()
        }

        // Codec tap toggles
        textSettingsCodec.setOnClickListener {
            currentCodec = if (currentCodec == PixelLogEncoderPipeline.VideoCodec.HEVC) {
                PixelLogEncoderPipeline.VideoCodec.AV1
            } else {
                PixelLogEncoderPipeline.VideoCodec.HEVC
            }
            prefs.codec = currentCodec
            updateSettingsDisplay()
        }

        // Curve tap cycles (Pixel-Log -> S-Log3 -> Apple Log)
        findViewById<View>(R.id.settingsCurveGroup).setOnClickListener {
            currentLogCurveType = (currentLogCurveType + 1) % 3
            prefs.logCurveType = currentLogCurveType
            engine.setLogCurveType(currentLogCurveType)
            updateSettingsDisplay()
        }

        // LUT selection (Cycles through built-in, custom imported, and CLEAN bypass)
        findViewById<View>(R.id.settingsLutGroup).setOnClickListener {
            if (lutCatalog.isNotEmpty()) {
                val nextIdx = (currentLutIndex + 1) % lutCatalog.size
                selectLut(nextIdx)
            }
        }

        // Signal / Metadata Transfer Tag toggle (LOG <-> HLG)
        findViewById<View>(R.id.settingsTransferGroup).setOnClickListener {
            currentColorTransfer = if (currentColorTransfer == PixelLogEncoderPipeline.ColorTransferMode.SDR_LOG) {
                PixelLogEncoderPipeline.ColorTransferMode.HLG
            } else {
                PixelLogEncoderPipeline.ColorTransferMode.SDR_LOG
            }
            prefs.colorTransfer = currentColorTransfer
            updateSettingsDisplay()
        }

        // Bake LUT to recording toggle (OFF <-> BAKED)
        findViewById<View>(R.id.settingsBakeLutGroup).setOnClickListener {
            isBakeLutActive = !isBakeLutActive
            prefs.isBakeLutActive = isBakeLutActive
            engine.setBakeLutToEncoder(isBakeLutActive)
            updateSettingsDisplay()
        }

        // Custom .cube LUT import launcher
        findViewById<View>(R.id.settingsImportLutGroup).setOnClickListener {
            try {
                lutPickerLauncher.launch("*/*")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch LUT picker", e)
                Toast.makeText(this, "Could not launch file picker", Toast.LENGTH_SHORT).show()
            }
        }

        // Torch toggle (OFF / ON)
        findViewById<View>(R.id.settingsTorchGroup).setOnClickListener {
            cameraController.isTorchEnabled = !cameraController.isTorchEnabled
            updateSettingsDisplay()
        }

        // Mic cycle (AUTO -> Built-in -> External -> ...)
        findViewById<View>(R.id.settingsMicGroup).setOnClickListener {
            audioInputManager.cycleNextDevice()
            updateSettingsDisplay()
        }

        // Sidecar JSON toggle (OFF / ON)
        findViewById<View>(R.id.settingsSidecarGroup).setOnClickListener {
            isSidecarEnabled = !isSidecarEnabled
            prefs.isSidecarEnabled = isSidecarEnabled
            updateSettingsDisplay()
        }

        // Stabilization tap toggles (OFF <-> OIS) [EIS and FULL hidden for now]
        findViewById<View>(R.id.settingsStabGroup).setOnClickListener {
            val nextMode = if (cameraController.stabilizationMode == CameraController.StabilizationMode.OIS) {
                CameraController.StabilizationMode.OFF
            } else {
                CameraController.StabilizationMode.OIS
            }
            switchStabilization(nextMode)
        }

        updateSettingsDisplay()
    }

    private fun updateSettingsDisplay() {
        textSettingsFps.text = cameraController.currentFramerate.label
        textSettingsMbps.text = currentBitratePreset.label.replace(" MBPS", "").replace(" Mbps", "")
            .replace("MBPS", "").trim().let {
                it.filter { c -> c.isDigit() }.ifEmpty { "140" }
            }
        textSettingsCodec.text = if (currentCodec == PixelLogEncoderPipeline.VideoCodec.AV1) "AV1" else "HEVC"
        textSettingsResolution.text = "${cameraController.activeWidth} × ${cameraController.activeHeight}"

        val curveNames = arrayOf("PIXEL-LOG", "S-LOG3", "APPLE LOG")
        textSettingsCurve.text = curveNames[currentLogCurveType.coerceIn(0, 2)]

        val activeLutName = lutCatalog.getOrNull(currentLutIndex)?.displayName ?: if (isLutActive) "REC.709" else "CLEAN"
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

        textSettingsTorch.text = if (cameraController.isTorchEnabled) "ON" else "OFF"
        textSettingsMic.text = if (audioInputManager.isAutoRouting) {
            "AUTO"
        } else {
            audioInputManager.selectedDevice?.typeLabel?.uppercase(Locale.US) ?: "INTERNAL"
        }

        textSettingsSidecar.text = if (isSidecarEnabled) "ON" else "OFF"
        textSettingsSidecar.setTextColor(
            if (isSidecarEnabled) ContextCompat.getColor(this, R.color.cyan_accent) else Color.WHITE
        )

        textSettingsStab.text = cameraController.stabilizationMode.label
        textSettingsStab.setTextColor(
            when (cameraController.stabilizationMode) {
                CameraController.StabilizationMode.OFF -> Color.parseColor("#9E9E9E")
                CameraController.StabilizationMode.OIS -> ContextCompat.getColor(this, R.color.cyan_accent)
                CameraController.StabilizationMode.EIS -> Color.parseColor("#FFD54F")
                CameraController.StabilizationMode.FULL -> Color.parseColor("#81C784")
            }
        )
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
                        prefs.isShutterAuto = false
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
                        prefs.isIsoAuto = false
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
                        prefs.isWbAuto = false
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
                        currentShutterIndex = index.coerceIn(0, shutterSpeeds.size - 1)
                        prefs.shutterIndex = currentShutterIndex
                        prefs.isShutterAuto = false
                        applyShutterFromDial()
                    }
                    ParamTab.ISO -> {
                        currentIsoIndex = index.coerceIn(0, isoValues.size - 1)
                        prefs.isoIndex = currentIsoIndex
                        prefs.isIsoAuto = false
                        applyIsoFromDial()
                    }
                    ParamTab.EV -> {
                        currentEvIndex = index.coerceIn(0, evValues.size - 1)
                        prefs.evIndex = currentEvIndex
                        cameraController.setEvCompensation((evValues[currentEvIndex] * 2).roundToInt())
                    }
                    ParamTab.WB -> {
                        currentWbIndex = index.coerceIn(0, wbValues.size - 1)
                        prefs.wbIndex = currentWbIndex
                        prefs.isWbAuto = false
                        applyWbFromDial()
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
                textDialValue.text = "1/${shutterSpeeds[currentShutterIndex.coerceIn(0, shutterSpeeds.size - 1)]}"
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
                textDialValue.text = if (ev >= 0) "EV +${ev}" else "EV ${ev}"
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
        val initialFocusIndex = ((prefs.focusDiopter / 10f) * 100).roundToInt().coerceIn(0, 100)
        focusDialStrip.setValue(initialFocusIndex)
        btnFocusMode.text = if (isFocusAuto) getString(R.string.label_auto) else getString(R.string.label_manual)

        focusDialStrip.onUserDragStarted = {
            if (isFocusAuto) {
                isFocusAuto = false
                cameraController.isFocusAuto = false
                prefs.isFocusAuto = false
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
                    prefs.isFocusAuto = false
                    btnFocusMode.text = getString(R.string.label_manual)
                }
                val diopter = (index / 100f) * 10f  // 0..10 diopters
                cameraController.setFocusDiopter(diopter)
                prefs.focusDiopter = diopter
            }
        }

        btnFocusMode.setOnClickListener {
            isFocusAuto = !isFocusAuto
            cameraController.isFocusAuto = isFocusAuto
            prefs.isFocusAuto = isFocusAuto
            btnFocusMode.text = if (isFocusAuto) getString(R.string.label_auto) else getString(R.string.label_manual)
            if (!isFocusAuto) {
                val diopter = (focusDialStrip.getCurrentIndex() / 100f) * 10f
                cameraController.setFocusDiopter(diopter)
                prefs.focusDiopter = diopter
            }
        }
    }

    // ── Auto / Manual Toggle ──

    private fun updateAutoManualToggleForActiveTab() {
        val isAuto = when (activeParamTab) {
            ParamTab.SHUTTER -> cameraController.isShutterAuto
            ParamTab.ISO -> cameraController.isIsoAuto
            ParamTab.WB -> cameraController.isWbAuto
            ParamTab.EV -> cameraController.targetEvCompensation == 0
        }
        btnAutoManualToggle.text = if (isAuto) getString(R.string.label_auto) else getString(R.string.label_manual)
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
                        prefs.shutterIndex = currentShutterIndex
                        val speed = shutterSpeeds[currentShutterIndex]
                        val shutterNs = (1_000_000_000L / speed).coerceAtLeast(100_000L)
                        cameraController.setShutter(shutterNs, isAuto = false)
                        prefs.isShutterAuto = false
                        dialStrip.setValue(currentShutterIndex)
                    } else {
                        cameraController.isShutterAuto = true
                        prefs.isShutterAuto = true
                        cameraController.applySettings()
                    }
                }
                ParamTab.ISO -> {
                    if (cameraController.isIsoAuto) {
                        // Switch to manual: seed target ISO from live ISO
                        val iso = isoValues[currentIsoIndex.coerceIn(0, isoValues.size - 1)]
                        cameraController.setIso(iso, isAuto = false)
                        prefs.isIsoAuto = false
                        prefs.isoIndex = currentIsoIndex
                    } else {
                        cameraController.isIsoAuto = true
                        prefs.isIsoAuto = true
                        cameraController.applySettings()
                    }
                }
                ParamTab.WB -> {
                    if (cameraController.isWbAuto) {
                        // Switch to manual: seed target Kelvin from live estimated Kelvin
                        val kelvin = wbValues[currentWbIndex.coerceIn(0, wbValues.size - 1)]
                        cameraController.setWhiteBalance(kelvin, isAuto = false)
                        prefs.isWbAuto = false
                        prefs.wbIndex = currentWbIndex
                    } else {
                        cameraController.isWbAuto = true
                        prefs.isWbAuto = true
                        cameraController.applySettings()
                    }
                }
                ParamTab.EV -> {
                    // Reset EV compensation to 0 (Auto neutral)
                    cameraController.setEvCompensation(0)
                    val zeroIdx = evValues.indexOfFirst { it == 0f }.coerceAtLeast(0)
                    currentEvIndex = zeroIdx
                    prefs.evIndex = zeroIdx
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
                    val closestIdx = shutterSpeeds.indices.minByOrNull { kotlin.math.abs(shutterSpeeds[it] - speed) } ?: currentShutterIndex
                    currentShutterIndex = closestIdx
                    if (activeParamTab == ParamTab.SHUTTER) {
                        textDialValue.text = "1/$speed"
                        dialStrip.setCurrentIndex(closestIdx)
                    }
                }

                // 2. Live ISO tracking
                if (cameraController.isIsoAuto) {
                    val closestIdx = isoValues.indices.minByOrNull { kotlin.math.abs(isoValues[it] - iso) } ?: currentIsoIndex
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
                    val closestIdx = wbValues.indices.minByOrNull { kotlin.math.abs(wbValues[it] - kelvin) } ?: currentWbIndex
                    currentWbIndex = closestIdx
                    if (activeParamTab == ParamTab.WB) {
                        textDialValue.text = "${kelvin}K"
                        dialStrip.setCurrentIndex(closestIdx)
                    }
                }

                // 4. EV tracking when at neutral compensation
                if (cameraController.targetEvCompensation == 0 && activeParamTab == ParamTab.EV) {
                    val evFormatted = if (ev >= 0f) "+%.1f".format(Locale.US, ev) else "%.1f".format(Locale.US, ev)
                    textDialValue.text = "EV $evFormatted"
                }

                // 5. Focus tracking
                if (isFocusAuto) {
                    val focusPercent = ((focusDiopter / 10f) * 100f).roundToInt().coerceIn(0, 100)
                    focusDialStrip.setValue(focusPercent)
                }
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
            prefs.isFocusAuto = true
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

        // Long press (hold) → AE spot metering + lock
        focusExposureOverlay.onHoldExposure = { normX, normY ->
            cameraController.triggerHoldToSetExposure(normX, normY, lock = true) { aeState ->
                runOnUiThread {
                    when (aeState) {
                        CameraController.AeState.CONVERGED,
                        CameraController.AeState.LOCKED -> {
                            focusExposureOverlay.setExposureLocked(locked = true)
                            // Sync live ISO/shutter dial label to reflect locked exposure
                            updateDialValueLabel()
                        }
                        else -> { /* METERING or IDLE */ }
                    }
                }
            }
        }

        // Double tap → reset both AF and AE to full-scene continuous mode
        focusExposureOverlay.onResetAfAe = {
            cameraController.resetFocusAndExposure()
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
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val stat = StatFs(moviesDir.absolutePath)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            val bytesPerSecond = currentBitratePreset.targetBps / 8L
            val remainingSeconds = if (bytesPerSecond > 0) availableBytes / bytesPerSecond else 0L
            val remainingMinutes = (remainingSeconds / 60).toInt()
            textRemainingMinutes.text = remainingMinutes.toString()
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

    private fun generateSidecarJson(videoFile: File, durationMs: Long): File? {
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

            val vidObj = JSONObject().apply {
                put("codec", currentCodec.displayName)
                put("mime_type", currentCodec.mimeType)
                put("width", encoderPipeline?.width ?: cameraController.activeWidth)
                put("height", encoderPipeline?.height ?: cameraController.activeHeight)
                put("frame_rate", cameraController.currentFramerate.fps)
                put("bitrate_preset", currentBitratePreset.label)
                put("target_bitrate_bps", currentBitratePreset.targetBps)
                put("color_standard", "BT.2020")
                put("color_range", "Limited (64-940 / 64-960)")
                put("color_transfer", if (currentColorTransfer == PixelLogEncoderPipeline.ColorTransferMode.HLG) "ARIB STD-B67 / ITU-R BT.2100 HLG" else LogParams.CURVE_NAME)
                put("color_transfer_mode", currentColorTransfer.label)
                put("lut_baked", isBakeLutActive)
                put("active_lut", lutCatalog.getOrNull(currentLutIndex)?.displayName ?: if (isLutActive) "REC.709" else "CLEAN")
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
        performanceMonitor?.destroy()
        performanceMonitor = null
        audioCapturePipeline?.stopCapture()
        audioCapturePipeline = null
        audioInputManager.release()
        cameraController.stop()
        engine.destroy()
    }
}
