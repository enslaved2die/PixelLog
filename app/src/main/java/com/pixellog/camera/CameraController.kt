package com.pixellog.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.LensShadingMap
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.pixellog.nativebridge.PixelLogEngine
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * CameraController coordinates the Camera2 HAL state machine for the Pixel 11 Pro:
 * - Multi-Lens switching across 0.5x, 1x, 5x (12mm, 24mm, 120mm)
 * - 4:3 Open-Gate RAW_SENSOR session with per-camera calibration caching
 * - Auto Mode: Continuous AE/AF with linear tone curves
 * - Full Manual Mode: 180° shutter lock, manual ISO, focus diopters, Kelvin/Tint WB
 * - Real-time per-frame metadata synchronization (timestamp, neutral color point, lens shading map, black level)
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

        // Android 16+ Hybrid Auto-Exposure Keys (API 36+ / Tensor G6)
        val KEY_AE_PRIORITY_MODE: CaptureRequest.Key<Int>? = try {
            CaptureRequest.Key("android.control.aePriorityMode", Int::class.javaPrimitiveType ?: java.lang.Integer.TYPE)
        } catch (_: Throwable) { null }

        val CHAR_AE_AVAILABLE_PRIORITY_MODES_BYTE: CameraCharacteristics.Key<ByteArray>? = try {
            CameraCharacteristics.Key("android.control.aeAvailablePriorityModes", ByteArray::class.java)
        } catch (_: Throwable) { null }

        val CHAR_AE_AVAILABLE_PRIORITY_MODES_INT: CameraCharacteristics.Key<IntArray>? = try {
            CameraCharacteristics.Key("android.control.aeAvailablePriorityModes", IntArray::class.java)
        } catch (_: Throwable) { null }

        const val AE_PRIORITY_OFF = 0
        const val AE_PRIORITY_ISO = 1
        const val AE_PRIORITY_SHUTTER = 2
    }

    enum class LensZoom(
        val label: String,
        val focalLengthEquivMm: Int,
        val isCrop: Boolean = false,
        val cropFactor: Float = 1.0f,
        val hasDcg: Boolean = false,
        val hasOis: Boolean = false
    ) {
        UW_05X("0.5x", 12, false, 1.0f, false, false),
        WIDE_1X("1x", 24, false, 1.0f, true, true),
        TELE_5X("5x", 120, false, 1.0f, false, true);

        val displayName: String get() = "$label (${focalLengthEquivMm}mm)"
    }

    val currentLensHasDcg: Boolean
        get() = currentLens.hasDcg

    val currentLensHasOis: Boolean
        get() = currentLens.hasOis

    enum class StabilizationMode(
        val label: String,
        val oisMode: Int,
        val eisMode: Int,
        val isCrop: Boolean,
        val description: String
    ) {
        OFF(
            "OFF",
            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            false,
            "Stabilization OFF (4080x3064, zero crop, tripod/gimbal)"
        ),
        OIS(
            "OIS",
            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            false,
            "Optical Image Stabilization (4080x3064, zero crop)"
        ),
        EIS(
            "EIS",
            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON,
            true,
            "Electronic Video Stabilization (3468x2600 raw un-upscaled)"
        ),
        FULL(
            "FULL",
            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON,
            true,
            "Full Hybrid Stabilization (OIS + EIS, 3468x2600 raw un-upscaled)"
        )
    }

    var stabilizationMode: StabilizationMode = StabilizationMode.OIS
        private set

    fun setStabilization(mode: StabilizationMode) {
        stabilizationMode = mode
        if (mode.isCrop) {
            activeWidth = 3468
            activeHeight = 2600
        } else {
            activeWidth = fullSensorWidth
            activeHeight = fullSensorHeight
        }
        applyStateAndRepeat()
    }

    enum class FramerateConfig(
        val label: String,
        val fps: Double,
        val frameDurationNs: Long,
        val shutter180Ns: Long,
        val shutter180Speed: Int,
        val fpsRange: Range<Int>,
        val isBinned: Boolean
    ) {
        FPS_24("24", 24.0, 41_666_667L, 20_833_333L, 48, Range(24, 24), false),
        FPS_25("25", 25.0, 40_000_000L, 20_000_000L, 50, Range(24, 30), false),
        FPS_29_97("29.97", 29.970029, 33_366_667L, 16_683_333L, 60, Range(30, 30), false),
        FPS_30("30", 30.0, 33_333_333L, 16_666_666L, 60, Range(30, 30), false),
        FPS_48("48", 48.0, 20_833_333L, 10_416_667L, 96, Range(30, 60), false),
        FPS_50("50", 50.0, 20_000_000L, 10_000_000L, 100, Range(15, 60), false),
        FPS_60("60", 60.0, 16_666_666L, 8_333_333L, 120, Range(30, 60), false);

        val targetFps: Double get() = fps
        val binned: Boolean get() = isBinned
    }

    enum class ControlMode {
        AUTO,
        FULL_MANUAL
    }

    enum class AfState {
        IDLE,
        SCANNING,
        FOCUSED_LOCKED,
        NOT_FOCUSED_LOCKED
    }

    enum class AeState {
        IDLE,
        METERING,
        CONVERGED,
        LOCKED
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraCharacteristics: CameraCharacteristics? = null

    // Multi-Lens Physical Camera Map
    private var ultrawideCameraId: String? = null
    private var wideCameraId: String = "2"
    private var teleCameraId: String? = null
    var currentPhysicalCameraId: String = "2"
        private set
    private val calibrationCache = mutableMapOf<String, CameraCalibration>()

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraExecutor: Executor? = null

    // State Variables
    var isShutterAuto: Boolean = true
        set(value) {
            if (field != value) {
                Log.i(TAG, "isShutterAuto changed: $field -> $value")
                field = value
            }
        }
    var isIsoAuto: Boolean = true
        set(value) {
            if (field != value) {
                Log.i(TAG, "isIsoAuto changed: $field -> $value")
                field = value
            }
        }
    var isWbAuto: Boolean = true
        set(value) {
            if (field != value) {
                Log.i(TAG, "isWbAuto changed: $field -> $value")
                field = value
            }
        }
    var isFocusAuto: Boolean = true
        set(value) {
            if (field != value) {
                Log.i(TAG, "isFocusAuto changed: $field -> $value")
                field = value
            }
            applyStateAndRepeat()
        }

    val currentMode: ControlMode
        get() = if (isShutterAuto && isIsoAuto && isWbAuto) ControlMode.AUTO else ControlMode.FULL_MANUAL

    var lastLiveNeutralPoint: FloatArray? = null
        private set
    var lastLiveKelvin: Int = 5600
        private set

    var currentFramerate: FramerateConfig = FramerateConfig.FPS_30
        private set

    var currentLens: LensZoom = LensZoom.WIDE_1X
        private set

    fun setInitialLensAndFramerate(lens: LensZoom, framerate: FramerateConfig) {
        currentLens = lens
        currentFramerate = framerate
        targetShutterNs = framerate.shutter180Ns
    }

    var currentCameraId: String = "0"
        private set

    var currentCalibration: CameraCalibration? = null
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

    var targetEvCompensation: Int = 0
        set(value) {
            field = value
            applyStateAndRepeat()
        }

    // Metering Regions & AE/AF Lock States
    var afMeteringRegion: MeteringRectangle? = null
        private set
    var aeMeteringRegion: MeteringRectangle? = null
        private set
    var isAeLocked: Boolean = false
        private set
    var currentAfState: AfState = AfState.IDLE
        private set
    var currentAeState: AeState = AeState.IDLE
        private set

    var onAfStateChanged: ((AfState) -> Unit)? = null
    var onAeStateChanged: ((AeState) -> Unit)? = null
    private var shouldLockAeAfterPrecapture = false

    fun setFocusDiopter(diopter: Float) {
        targetFocusDiopter = diopter.coerceIn(0.0f, 10.0f)
        if (!isFocusAuto) {
            afMeteringRegion = null
            currentAfState = AfState.IDLE
            applyStateAndRepeat()
        }
    }

    var fullSensorWidth: Int = 4080
        private set
    var fullSensorHeight: Int = 3064
        private set

    var activeWidth: Int = 4080
    var activeHeight: Int = 3072
    var bayerPattern: Int = 0 // 0: RGGB

    // Latest Frame Metadata for Sidecar JSON export
    var lastDynamicBlackLevel: FloatArray = floatArrayOf(256.0f, 256.0f, 256.0f, 256.0f)
        private set
    var lastWhiteLevel: Float = 4095.0f
        private set
    var lastNeutralColorPoint: FloatArray = floatArrayOf(0.55f, 1.0f, 0.70f)
        private set
    var lastCompositeMatrix: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        private set
    var lastIso: Int = 100
        private set
    var lastExposureNs: Long = SHUTTER_180_NS
        private set
    var lastAutoIso: Int = 100
        private set
    var lastAutoExposureNs: Long = SHUTTER_180_NS
        private set
    var lastFocusDiopter: Float = 0.0f
        private set
    var lastKelvin: Int = 5600
        private set
    var lastEv: Float = 0.0f
        private set

    fun supportsAePriority(priorityMode: Int): Boolean {
        if (KEY_AE_PRIORITY_MODE == null) return false
        val chars = cameraCharacteristics ?: return false
        return try {
            if (CHAR_AE_AVAILABLE_PRIORITY_MODES_BYTE != null) {
                val byteModes = chars.get(CHAR_AE_AVAILABLE_PRIORITY_MODES_BYTE)
                if (byteModes != null && byteModes.any { it.toInt() == priorityMode }) return true
            }
            if (CHAR_AE_AVAILABLE_PRIORITY_MODES_INT != null) {
                val intModes = chars.get(CHAR_AE_AVAILABLE_PRIORITY_MODES_INT)
                if (intModes != null && intModes.contains(priorityMode)) return true
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    // Callback for UI updates
    var onFrameMetadataListener: ((dynamicBlackLevel: FloatArray, whiteLevel: Float) -> Unit)? = null
    var onLiveTelemetry: ((iso: Int, shutterNs: Long, focusDiopter: Float, kelvin: Int, ev: Float) -> Unit)? = null

    init {
        discoverPhysicalCameras()
    }

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

    /**
     * Discovers all physical rear cameras and caches their calibration profiles.
     */
    private fun discoverPhysicalCameras() {
        val allIds = mutableSetOf<String>()
        allIds.addAll(cameraManager.cameraIdList)

        // Query physical cameras from logical cameras (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            for (id in cameraManager.cameraIdList) {
                try {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    allIds.addAll(chars.physicalCameraIds)
                } catch (e: Exception) {
                    Log.w(TAG, "Error querying physical IDs for $id: ${e.message}")
                }
            }
        }

        for (id in allIds) {
            try {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                val hasRaw = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW)

                if (facing == CameraCharacteristics.LENS_FACING_BACK && hasRaw) {
                    val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf()
                    val minFocal = focals.minOrNull() ?: 6.9f

                    if (minFocal < 3.5f) {
                        ultrawideCameraId = id
                        Log.i(TAG, "Discovered Ultrawide Camera: ID $id (${minFocal}mm)")
                    } else if (minFocal > 12.0f) {
                        teleCameraId = id
                        Log.i(TAG, "Discovered Telephoto Camera: ID $id (${minFocal}mm)")
                    } else {
                        if (id != "0" || wideCameraId == "0") {
                            wideCameraId = id
                        }
                        Log.i(TAG, "Discovered Wide Camera: ID $id (${minFocal}mm)")
                    }

                    // Cache calibration for this camera
                    calibrationCache[id] = extractCalibration(id, chars)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error inspecting camera $id: ${e.message}")
            }
        }

        // Fallback wide camera if "0" is not present or has no RAW (e.g. emulator)
        if (!cameraManager.cameraIdList.contains(wideCameraId)) {
            wideCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                try {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                } catch (_: Exception) { false }
            } ?: cameraManager.cameraIdList.firstOrNull() ?: "0"
        }
    }

    private fun extractCalibration(id: String, chars: CameraCharacteristics): CameraCalibration {
        val defaultCalib = ColorScienceUtils.getDefaultCalibration(id)
        val fm1 = ColorScienceUtils.colorSpaceTransformToFloatArray(chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1))
            ?: defaultCalib.forwardMatrix1
        val fm2 = ColorScienceUtils.colorSpaceTransformToFloatArray(chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2))
            ?: defaultCalib.forwardMatrix2
        val cc1 = ColorScienceUtils.colorSpaceTransformToFloatArray(chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1))
            ?: defaultCalib.calibrationTransform1
        val cc2 = ColorScienceUtils.colorSpaceTransformToFloatArray(chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2))
            ?: defaultCalib.calibrationTransform2
        val ill1 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.toInt() ?: defaultCalib.illuminant1
        val ill2 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt() ?: defaultCalib.illuminant2
        val wl = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.toFloat() ?: defaultCalib.whiteLevel
        val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: defaultCalib.bayerPattern

        val blPattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val dynBlack = if (blPattern != null) {
            floatArrayOf(
                blPattern.getOffsetForIndex(0, 0).toFloat(), // top-left (0,0)
                blPattern.getOffsetForIndex(1, 0).toFloat(), // top-right (1,0)
                blPattern.getOffsetForIndex(0, 1).toFloat(), // bottom-left (0,1)
                blPattern.getOffsetForIndex(1, 1).toFloat()  // bottom-right (1,1)
            )
        } else {
            defaultCalib.dynamicBlackLevel
        }

        Log.i(TAG, "Calibration for Cam $id: WL=$wl, BL=[${dynBlack.joinToString()}], CFA=$cfa")

        return CameraCalibration(
            cameraId = id,
            forwardMatrix1 = fm1,
            forwardMatrix2 = fm2,
            calibrationTransform1 = cc1,
            calibrationTransform2 = cc2,
            illuminant1 = ill1,
            illuminant2 = ill2,
            whiteLevel = wl,
            dynamicBlackLevel = dynBlack,
            bayerPattern = cfa
        )
    }

    @SuppressLint("MissingPermission")
    fun openCamera(preferredWidth: Int = 4080, preferredHeight: Int = 3064, cameraId: String? = null) {
        val targetPhysId = selectCameraForLens(currentLens).let { id ->
            if (cameraManager.cameraIdList.contains(id)) id
            else cameraManager.cameraIdList.firstOrNull() ?: id
        }
        currentPhysicalCameraId = targetPhysId
        val targetLogicalId = if (cameraId != null && cameraManager.cameraIdList.contains(cameraId)) {
            cameraId
        } else if (cameraManager.cameraIdList.contains(targetPhysId)) {
            targetPhysId
        } else if (cameraManager.cameraIdList.contains("0")) {
            "0"
        } else {
            cameraManager.cameraIdList.firstOrNull() ?: "0"
        }
        currentCameraId = targetLogicalId

        val chars = try {
            cameraManager.getCameraCharacteristics(targetPhysId)
        } catch (e: Exception) {
            try {
                cameraManager.getCameraCharacteristics(targetLogicalId)
            } catch (e2: Exception) {
                cameraManager.getCameraCharacteristics(cameraManager.cameraIdList.first())
            }
        }
        cameraCharacteristics = chars
        currentCalibration = calibrationCache[targetPhysId] ?: extractCalibration(targetPhysId, chars)

        // Bayer filter arrangement
        val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        bayerPattern = cfa

        // Discover supported RAW_SENSOR output sizes from HAL
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
            val streamW: Int
            val streamH: Int
            if (currentFramerate.isBinned) {
                streamW = BINNED_RES_WIDTH
                streamH = BINNED_RES_HEIGHT
                activeWidth = BINNED_RES_WIDTH
                activeHeight = BINNED_RES_HEIGHT
            } else {
                streamW = matchedSize.width
                streamH = matchedSize.height
                if (stabilizationMode.isCrop) {
                    activeWidth = 3468
                    activeHeight = 2600
                } else {
                    activeWidth = matchedSize.width
                    activeHeight = matchedSize.height
                }
            }
            Log.i(TAG, "Selected Camera2 RAW_SENSOR stream size: ${streamW}x${streamH}, active size: ${activeWidth}x${activeHeight} (Stab: ${stabilizationMode.label}) for PhysCam $targetPhysId (Logical $targetLogicalId)")
        } else {
            fullSensorWidth = preferredWidth
            fullSensorHeight = preferredHeight
            activeWidth = if (stabilizationMode.isCrop) 3468 else preferredWidth
            activeHeight = if (stabilizationMode.isCrop) 2600 else preferredHeight
            Log.w(TAG, "No RAW_SENSOR sizes reported for PhysCam $targetPhysId, using ${activeWidth}x${activeHeight}")
        }

        val rawInitW = if (currentFramerate.isBinned) BINNED_RES_WIDTH else fullSensorWidth
        val rawInitH = if (currentFramerate.isBinned) BINNED_RES_HEIGHT else fullSensorHeight
        Log.i(TAG, "Opening sensor: ID $targetLogicalId [PhysCam $targetPhysId] (${currentLens.displayName}), Stream $rawInitW x $rawInitH, Active $activeWidth x $activeHeight, CFA $bayerPattern")

        // Initialize native GPU pipeline with sensor stream resolution and CFA pattern
        engine.initialize(rawInitW, rawInitH, bayerPattern)

        cameraManager.openCamera(targetLogicalId, cameraExecutor!!, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createOpenGateSession()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "Camera device error ($targetLogicalId): $error")
                camera.close()
                cameraDevice = null
            }
        })
    }

    private fun selectCameraForLens(lens: LensZoom): String {
        return when (lens) {
            LensZoom.UW_05X -> ultrawideCameraId ?: "3"
            LensZoom.WIDE_1X -> wideCameraId
            LensZoom.TELE_5X -> teleCameraId ?: "4"
        }
    }

    /**
     * Switches optical lens (0.5x, 1x, 5x).
     */
    fun setLensZoom(lens: LensZoom) {
        val targetPhysId = selectCameraForLens(lens)
        currentLens = lens
        afMeteringRegion = null
        aeMeteringRegion = null
        isAeLocked = false
        currentAfState = AfState.IDLE
        currentAeState = AeState.IDLE

        if (targetPhysId == currentPhysicalCameraId) {
            // Same physical camera (e.g. 1x <-> 2x crop, or 5x <-> 10x crop)
            // Instant in-sensor crop switch without dropping camera session
            applyStateAndRepeat()
            Log.i(TAG, "Applied in-sensor crop for ${lens.displayName} on PhysCam $currentPhysicalCameraId")
        } else {
            // Switch physical sensor (UW "3" <-> Wide "2" <-> Tele "4")
            currentPhysicalCameraId = targetPhysId
            val chars = try {
                cameraManager.getCameraCharacteristics(targetPhysId)
            } catch (e: Exception) {
                cameraCharacteristics
            }
            cameraCharacteristics = chars
            currentCalibration = calibrationCache[targetPhysId]
                ?: chars?.let { extractCalibration(targetPhysId, it) }
                ?: ColorScienceUtils.getDefaultCalibration(targetPhysId)

            // Inspect target sensor's native open-gate RAW resolution
            val map = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
            val matchedSize = rawSizes?.filter { s ->
                val ratio = s.width.toFloat() / s.height.toFloat()
                Math.abs(ratio - (4.0f / 3.0f)) < 0.05f
            }?.maxByOrNull { it.width * it.height } ?: rawSizes?.firstOrNull()

            val newW = matchedSize?.width ?: fullSensorWidth
            val newH = matchedSize?.height ?: fullSensorHeight
            val resolutionChanged = (newW != activeWidth || newH != activeHeight)

            Log.i(TAG, "Switching physical sensor to ID $targetPhysId (${lens.displayName}), target res: ${newW}x${newH} (resChanged=$resolutionChanged)")

            val task = Runnable {
                try {
                    captureSession?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing capture session: ${e.message}")
                }
                captureSession = null

                val cfa = chars?.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: bayerPattern
                val cfaChanged = (cfa != bayerPattern)
                if (resolutionChanged || cfaChanged) {
                    fullSensorWidth = newW
                    fullSensorHeight = newH
                    val rawInitW = if (currentFramerate.isBinned) BINNED_RES_WIDTH else newW
                    val rawInitH = if (currentFramerate.isBinned) BINNED_RES_HEIGHT else newH
                    if (stabilizationMode.isCrop) {
                        activeWidth = 3468
                        activeHeight = 2600
                    } else {
                        activeWidth = rawInitW
                        activeHeight = rawInitH
                    }
                    bayerPattern = cfa
                    engine.initialize(rawInitW, rawInitH, bayerPattern)
                    Log.i(TAG, "Reinitialized GPU pipeline for PhysCam $targetPhysId: ${rawInitW}x${rawInitH} (active ${activeWidth}x${activeHeight}), CFA $bayerPattern")
                }

                createOpenGateSession()
                Log.i(TAG, "Switched lens to ${lens.displayName}, PhysCam=$targetPhysId: WL=${currentCalibration?.whiteLevel}, BL=[${currentCalibration?.dynamicBlackLevel?.joinToString()}], CFA=$bayerPattern")
            }
            val executor = cameraExecutor
            if (executor != null) {
                executor.execute(task)
            } else {
                task.run()
            }
        }
    }

    private fun createOpenGateSession() {
        val camera = cameraDevice ?: return
        val rawSurface = engine.getCameraSurface()
        if (rawSurface == null) {
            Log.w(TAG, "Native camera surface is null (emulator or HAL without RAW), skipping session creation")
            return
        }

        val rawOutput = OutputConfiguration(rawSurface)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && currentPhysicalCameraId.isNotEmpty() && currentPhysicalCameraId != currentCameraId) {
            try {
                rawOutput.setPhysicalCameraId(currentPhysicalCameraId)
                Log.i(TAG, "Assigned physical camera ID $currentPhysicalCameraId to RAW output")
            } catch (e: Exception) {
                Log.w(TAG, "Could not set physical camera ID $currentPhysicalCameraId: ${e.message}")
            }
        }

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
                    Log.e(TAG, "Failed to configure open-gate capture session for PhysCam $currentPhysicalCameraId")
                }
            }
        )

        camera.createCaptureSession(sessionConfig)
    }

    fun setMode(mode: ControlMode) {
        when (mode) {
            ControlMode.AUTO -> {
                isShutterAuto = true
                isIsoAuto = true
                isWbAuto = true
            }
            ControlMode.FULL_MANUAL -> {
                isShutterAuto = false
                isIsoAuto = false
                isWbAuto = false
            }
        }
        applyStateAndRepeat()
    }

    fun setShutter(shutterNs: Long, isAuto: Boolean = false) {
        targetShutterNs = shutterNs
        isShutterAuto = isAuto
        applyStateAndRepeat()
    }

    fun setIso(iso: Int, isAuto: Boolean = false) {
        targetIso = iso
        isIsoAuto = isAuto
        applyStateAndRepeat()
    }

    fun setWhiteBalance(kelvin: Int, isAuto: Boolean = false) {
        targetKelvin = kelvin
        isWbAuto = isAuto
        applyStateAndRepeat()
    }

    fun setEvCompensation(compensationSteps: Int) {
        targetEvCompensation = compensationSteps
        applyStateAndRepeat()
    }

    fun applySettings() {
        applyStateAndRepeat()
    }

    fun setFramerate(config: FramerateConfig) {
        val targetWidth = if (config.isBinned) BINNED_RES_WIDTH else fullSensorWidth
        val targetHeight = if (config.isBinned) BINNED_RES_HEIGHT else fullSensorHeight
        val crossResolution = (targetWidth != activeWidth || targetHeight != activeHeight)

        currentFramerate = config
        targetShutterNs = config.shutter180Ns

        if (!crossResolution) {
            applyStateAndRepeat()
        } else {
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

        applyStateAndRepeat()
    }

    fun getCurrentCropRegion(): Rect {
        val activeArray = cameraCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            ?: Rect(0, 0, activeWidth, activeHeight)
        if (stabilizationMode.isCrop) {
            val cropW = (activeArray.width() * 0.85f).toInt()
            val cropH = (activeArray.height() * 0.85f).toInt()
            var cropX = activeArray.left + (activeArray.width() - cropW) / 2
            var cropY = activeArray.top + (activeArray.height() - cropH) / 2
            cropX = cropX and 1.inv()
            cropY = cropY and 1.inv()
            val alignedW = cropW and 1.inv()
            val alignedH = cropH and 1.inv()
            return Rect(cropX, cropY, cropX + alignedW, cropY + alignedH)
        } else if (currentLens.isCrop) {
            val cropW = (activeArray.width() / currentLens.cropFactor).toInt()
            val cropH = (activeArray.height() / currentLens.cropFactor).toInt()
            var cropX = activeArray.left + (activeArray.width() - cropW) / 2
            var cropY = activeArray.top + (activeArray.height() - cropH) / 2
            cropX = cropX and 1.inv()
            cropY = cropY and 1.inv()
            val alignedW = cropW and 1.inv()
            val alignedH = cropH and 1.inv()
            return Rect(cropX, cropY, cropX + alignedW, cropY + alignedH)
        }
        return activeArray
    }

    fun mapNormalizedToSensorCoords(u: Float, v: Float, regionFraction: Float = 0.12f): MeteringRectangle {
        val crop = getCurrentCropRegion()
        val activeArray = cameraCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: crop

        val clampedU = u.coerceIn(0f, 1f)
        val clampedV = v.coerceIn(0f, 1f)

        val centerX = (crop.left + clampedU * crop.width()).roundToInt()
        val centerY = (crop.top + clampedV * crop.height()).roundToInt()

        val regionW = (crop.width() * regionFraction).roundToInt().coerceAtLeast(100)
        val regionH = (crop.height() * regionFraction).roundToInt().coerceAtLeast(100)

        val halfW = regionW / 2
        val halfH = regionH / 2

        val left = (centerX - halfW).coerceIn(activeArray.left, activeArray.right - 1)
        val top = (centerY - halfH).coerceIn(activeArray.top, activeArray.bottom - 1)
        val right = (centerX + halfW).coerceIn(left + 1, activeArray.right)
        val bottom = (centerY + halfH).coerceIn(top + 1, activeArray.bottom)

        return MeteringRectangle(Rect(left, top, right, bottom), MeteringRectangle.METERING_WEIGHT_MAX)
    }

    private fun populateCommonSettings(builder: CaptureRequest.Builder) {
        // Clamp frame rate to configured cadence
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, currentFramerate.fpsRange)
        builder.set(CaptureRequest.SENSOR_FRAME_DURATION, currentFramerate.frameDurationNs)

        // Prevent digital gain highlight clipping
        builder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, 100)

        // Suppress ISP sharpening and denoising artifacts
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
        builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
        builder.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_OFF)
        builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF)
        builder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraMetadata.DISTORTION_CORRECTION_MODE_OFF)

        // PHASE 1 & 2: Enable Lens Shading Map generation for GPU pipeline
        builder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
        builder.set(CaptureRequest.SHADING_MODE, CameraMetadata.SHADING_MODE_OFF)

        // Freeze ISP tone-mapping to linear identity
        builder.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
        val linearCurve = floatArrayOf(0.0f, 0.0f, 1.0f, 1.0f)
        builder.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(linearCurve, linearCurve, linearCurve))

        // Camera Stabilization (OFF / OIS / EIS / FULL)
        val targetOis = if (currentLens.hasOis && stabilizationMode.oisMode == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) {
            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
        } else {
            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
        }
        builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, targetOis)

        val targetEis = if (stabilizationMode.eisMode == CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) {
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
        } else {
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        }
        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, targetEis)

        // In-Sensor Crop Region for 2x/10x and EIS/FULL un-upscaled raw crop
        builder.set(CaptureRequest.SCALER_CROP_REGION, getCurrentCropRegion())

        // Torch / Flash Mode
        if (isTorchEnabled) {
            builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
        } else {
            builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        }

        val hasShutterPriority = supportsAePriority(AE_PRIORITY_SHUTTER)
        val hasIsoPriority = supportsAePriority(AE_PRIORITY_ISO)

        // Exposure controls (Independent Shutter & ISO)
        if (isShutterAuto && isIsoAuto) {
            // 1. Full Auto Exposure (AE)
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            if (KEY_AE_PRIORITY_MODE != null) {
                try { builder.set(KEY_AE_PRIORITY_MODE, AE_PRIORITY_OFF) } catch (_: Throwable) {}
            }
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, targetEvCompensation)

        } else if (!isShutterAuto && isIsoAuto && hasShutterPriority) {
            // 2. Hardware Shutter Priority (Android 16+ Native on Tensor G6)
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            try { builder.set(KEY_AE_PRIORITY_MODE!!, AE_PRIORITY_SHUTTER) } catch (_: Throwable) {}
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, targetShutterNs)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, targetEvCompensation)

        } else if (isShutterAuto && !isIsoAuto && hasIsoPriority) {
            // 3. Hardware ISO Priority (Android 16+ Native on Tensor G6)
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            try { builder.set(KEY_AE_PRIORITY_MODE!!, AE_PRIORITY_ISO) } catch (_: Throwable) {}
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, targetIso)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, targetEvCompensation)

        } else {
            // 4. Pure Manual or Non-Compounding Anchor Fallback
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)

            val evStep = cameraCharacteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            val evScale = Math.pow(2.0, targetEvCompensation * (evStep?.toDouble() ?: 0.5))

            if (!isShutterAuto && !isIsoAuto) {
                // Full Manual
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, targetIso)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, targetShutterNs)
            } else if (!isShutterAuto && isIsoAuto) {
                // Shutter Priority Fallback: Use fixed lastAuto anchor
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, targetShutterNs)
                val baseIso = if (lastAutoIso > 0) lastAutoIso else 100
                val baseShutter = if (lastAutoExposureNs > 0) lastAutoExposureNs else SHUTTER_180_NS
                val computedIso = (baseIso * (baseShutter.toDouble() / targetShutterNs) * evScale).roundToInt().coerceIn(50, 6400)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, computedIso)
            } else {
                // ISO Priority Fallback: Use fixed lastAuto anchor
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, targetIso)
                val baseIso = if (lastAutoIso > 0) lastAutoIso else 100
                val baseShutter = if (lastAutoExposureNs > 0) lastAutoExposureNs else SHUTTER_180_NS
                val computedShutter = (baseShutter * (baseIso.toDouble() / targetIso) * evScale).toLong().coerceIn(100_000L, 1_000_000_000L)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, computedShutter)
            }
        }

        // Auto-Exposure Metering Regions & Lock
        val maxAe = cameraCharacteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        if (maxAe > 0 && aeMeteringRegion != null) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(aeMeteringRegion))
        }
        if (isAeLocked) {
            builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
        }

        // White Balance (Independent WB & Auto-Tint)
        if (isWbAuto) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)

            val gains = ColorScienceUtils.calculateColorGains(targetKelvin, 0, currentCalibration)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, gains.toRggbChannelVector())
        }

        // Autofocus Metering Regions & Mode
        val maxAf = cameraCharacteristics?.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        if (maxAf > 0 && afMeteringRegion != null) {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(afMeteringRegion))
        }

        // Focus Control (independent of exposure mode)
        if (isFocusAuto) {
            if (afMeteringRegion != null) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, targetFocusDiopter)
        }
    }

    fun triggerTapToFocus(u: Float, v: Float, onStateChanged: ((AfState) -> Unit)? = null) {
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val rawSurface = engine.getCameraSurface() ?: return

        val region = mapNormalizedToSensorCoords(u, v)
        afMeteringRegion = region
        isFocusAuto = true
        currentAfState = AfState.SCANNING
        onAfStateChanged = onStateChanged
        onAfStateChanged?.invoke(AfState.SCANNING)

        val task = Runnable {
            try {
                // Submit one-shot trigger request with CONTROL_AF_TRIGGER_START
                val triggerBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(rawSurface)
                    populateCommonSettings(this)
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                }
                session.capture(triggerBuilder.build(), null, cameraHandler)

                applyStateAndRepeat()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to trigger tap-to-focus: ${e.message}", e)
            }
        }

        val executor = cameraExecutor
        if (executor != null) {
            executor.execute(task)
        } else {
            task.run()
        }
    }

    fun triggerHoldToSetExposure(u: Float, v: Float, lock: Boolean = true, onStateChanged: ((AeState) -> Unit)? = null) {
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val rawSurface = engine.getCameraSurface() ?: return

        val region = mapNormalizedToSensorCoords(u, v)
        aeMeteringRegion = region
        currentAeState = AeState.METERING
        onAeStateChanged = onStateChanged
        onAeStateChanged?.invoke(AeState.METERING)
        shouldLockAeAfterPrecapture = lock

        // If both shutter and ISO were manual, switch ISO to auto to allow AE metering
        if (!isShutterAuto && !isIsoAuto) {
            isIsoAuto = true
        }

        val task = Runnable {
            try {
                if (lock) {
                    val precaptureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(rawSurface)
                        populateCommonSettings(this)
                        set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
                        set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                    }
                    session.capture(precaptureBuilder.build(), null, cameraHandler)
                }

                applyStateAndRepeat()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to trigger hold-to-set-exposure: ${e.message}", e)
            }
        }

        val executor = cameraExecutor
        if (executor != null) {
            executor.execute(task)
        } else {
            task.run()
        }
    }

    fun unlockExposure() {
        isAeLocked = false
        shouldLockAeAfterPrecapture = false
        currentAeState = if (aeMeteringRegion != null) AeState.METERING else AeState.IDLE
        onAeStateChanged?.invoke(currentAeState)
        applyStateAndRepeat()
    }

    fun resetFocusAndExposure() {
        afMeteringRegion = null
        aeMeteringRegion = null
        isAeLocked = false
        shouldLockAeAfterPrecapture = false
        currentAfState = AfState.IDLE
        currentAeState = AeState.IDLE
        onAfStateChanged?.invoke(AfState.IDLE)
        onAeStateChanged?.invoke(AeState.IDLE)

        val session = captureSession
        val camera = cameraDevice
        val rawSurface = engine.getCameraSurface()

        val task = Runnable {
            if (session != null && camera != null && rawSurface != null) {
                try {
                    val cancelBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(rawSurface)
                        populateCommonSettings(this)
                        set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                        set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL)
                    }
                    session.capture(cancelBuilder.build(), null, cameraHandler)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to cancel AF/AE triggers: ${e.message}")
                }
            }
            applyStateAndRepeat()
        }

        val executor = cameraExecutor
        if (executor != null) {
            executor.execute(task)
        } else {
            task.run()
        }
    }

    private fun applyStateAndRepeat() {
        val session = captureSession ?: return
        val camera = cameraDevice ?: return
        val rawSurface = engine.getCameraSurface() ?: return

        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        builder.addTarget(rawSurface)
        populateCommonSettings(builder)

        // Repeating Capture Request Callback: Metadata synchronization
        session.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                val physResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && currentPhysicalCameraId.isNotEmpty()) {
                    result.physicalCameraResults[currentPhysicalCameraId] ?: result
                } else {
                    result
                }

                val timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L

                val iso = physResult.get(CaptureResult.SENSOR_SENSITIVITY) ?: targetIso
                val exposureNs = physResult.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: targetShutterNs
                val focusDiopter = physResult.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: targetFocusDiopter
                val evComp = physResult.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION) ?: targetEvCompensation
                val evStep = cameraCharacteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                val evFloat = evComp * (evStep?.toFloat() ?: 0.5f)

                // AF State monitoring
                // physResult is correct here: AF is per-physical-camera on Pixel HAL.
                val afState = physResult.get(CaptureResult.CONTROL_AF_STATE)
                if (afState != null && currentAfState == AfState.SCANNING) {
                    when (afState) {
                        CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> {
                            currentAfState = AfState.FOCUSED_LOCKED
                            onAfStateChanged?.invoke(AfState.FOCUSED_LOCKED)
                        }
                        CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> {
                            currentAfState = AfState.NOT_FOCUSED_LOCKED
                            onAfStateChanged?.invoke(AfState.NOT_FOCUSED_LOCKED)
                        }
                    }
                }

                // AE State monitoring
                // IMPORTANT: CONTROL_AE_STATE is a LOGICAL camera result on Pixel HAL.
                // Physical camera sub-results do not carry it → always null from physResult.
                // Read from the top-level TotalCaptureResult and fall back to physResult
                // only as a last resort (e.g. single-camera devices where physResult == result).
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    ?: physResult.get(CaptureResult.CONTROL_AE_STATE)
                if (aeState != null && currentAeState == AeState.METERING) {
                    when (aeState) {
                        // Lock as soon as the HAL reports a stable exposure.
                        // CONVERGED  : AE has settled to the metering target.
                        // LOCKED     : HAL accepted a prior lock command (fast path on some devices).
                        // FLASH_REQUIRED: AE converged but needs flash; honour lock in either case.
                        CaptureResult.CONTROL_AE_STATE_CONVERGED,
                        CaptureResult.CONTROL_AE_STATE_LOCKED,
                        CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> {
                            currentAeState = AeState.CONVERGED
                            onAeStateChanged?.invoke(AeState.CONVERGED)
                            if (shouldLockAeAfterPrecapture) {
                                isAeLocked = true
                                currentAeState = AeState.LOCKED
                                shouldLockAeAfterPrecapture = false
                                onAeStateChanged?.invoke(AeState.LOCKED)
                                cameraHandler?.post { applyStateAndRepeat() }
                            }
                        }
                        // While actively searching, keep waiting — don't lock prematurely.
                        CaptureResult.CONTROL_AE_STATE_SEARCHING,
                        CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> { /* still metering */ }
                    }
                }

                // 1. SENSOR_NEUTRAL_COLOR_POINT & Live AWB Estimation
                val neutralRational = physResult.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
                val awbGains = physResult.get(CaptureResult.COLOR_CORRECTION_GAINS)

                val liveNeutralPoint = if (neutralRational != null && neutralRational.size >= 3) {
                    floatArrayOf(
                        neutralRational[0].toFloat(),
                        neutralRational[1].toFloat(),
                        neutralRational[2].toFloat()
                    )
                } else if (awbGains != null && awbGains.red > 0.01f && awbGains.blue > 0.01f) {
                    floatArrayOf(1.0f / awbGains.red, 1.0f, 1.0f / awbGains.blue)
                } else {
                    lastLiveNeutralPoint ?: floatArrayOf(1.06f, 1.0f, 0.92f)
                }

                if (isWbAuto) {
                    lastLiveNeutralPoint = liveNeutralPoint
                    lastLiveKelvin = ColorScienceUtils.estimateTemperatureFromNeutral(liveNeutralPoint, currentCalibration).roundToInt()
                }

                val effectiveKelvin = if (isWbAuto) lastLiveKelvin else targetKelvin

                // Effective neutral point passed to GPU debayer shader
                val effectiveNeutralPoint = if (isWbAuto) {
                    liveNeutralPoint
                } else {
                    ColorScienceUtils.calculateNeutralColorPoint(
                        kelvin = targetKelvin,
                        liveNeutral = lastLiveNeutralPoint,
                        liveKelvin = lastLiveKelvin,
                        calibration = currentCalibration
                    )
                }

                if (isShutterAuto && isIsoAuto) {
                    lastAutoExposureNs = exposureNs
                    lastAutoIso = iso
                }
                lastExposureNs = exposureNs
                lastIso = iso
                lastFocusDiopter = focusDiopter
                lastKelvin = effectiveKelvin
                lastEv = evFloat

                onLiveTelemetry?.invoke(iso, exposureNs, focusDiopter, effectiveKelvin, evFloat)

                // 2. Dynamic Black Level with range sanitization
                val whiteLevel = currentCalibration?.whiteLevel ?: 4095.0f

                val rawDynBlack = physResult.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
                    ?: result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)

                val dynBlack = if (rawDynBlack != null && rawDynBlack.size == 4) {
                    // Reject leaked Camera 0 12-bit black level (> 128.0) when current sensor is 10-bit (whiteLevel <= 1023.0)
                    if (whiteLevel <= 1023.0f && rawDynBlack[0] > 128.0f) {
                        currentCalibration?.dynamicBlackLevel ?: floatArrayOf(64.0f, 64.0f, 64.0f, 64.0f)
                    } else {
                        rawDynBlack
                    }
                } else {
                    currentCalibration?.dynamicBlackLevel ?: (if (whiteLevel <= 1023.0f) floatArrayOf(64.0f, 64.0f, 64.0f, 64.0f) else floatArrayOf(256.0f, 256.0f, 256.0f, 256.0f))
                }

                // 3. Composite Matrix (Sensor -> Bradford -> Rec.2020 Linear scaled by LOG_XMAX)
                val compMatrix = ColorScienceUtils.computeCompositeColorMatrix(
                    neutralPoint = effectiveNeutralPoint,
                    calibration = currentCalibration,
                    exposureGain = ColorScienceUtils.LOG_XMAX
                )

                lastDynamicBlackLevel = dynBlack
                lastWhiteLevel = whiteLevel
                lastNeutralColorPoint = effectiveNeutralPoint
                lastCompositeMatrix = compMatrix

                // 4. Lens Shading Map (Phase 2.2)
                val shadingMap = physResult.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
                var shadingData: FloatArray? = null
                var shadingW = 0
                var shadingH = 0
                if (shadingMap != null) {
                    shadingW = shadingMap.columnCount
                    shadingH = shadingMap.rowCount
                    val totalFloats = shadingW * shadingH * 4
                    val arr = FloatArray(totalFloats)
                    shadingMap.copyGainFactors(arr, 0)
                    shadingData = arr
                }

                // Dispatch timestamp-synchronized metadata to C++ GPU debayering pipeline
                engine.updateFrameMetadata(
                    timestampNs = timestampNs,
                    blackLevel = dynBlack,
                    whiteLevel = whiteLevel,
                    neutralColorPoint = effectiveNeutralPoint,
                    compositeMatrix = compMatrix,
                    exposureGain = ColorScienceUtils.LOG_XMAX,
                    shadingMap = shadingData,
                    shadingWidth = shadingW,
                    shadingHeight = shadingH
                )

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
