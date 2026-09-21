package com.pixellog.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.LensShadingMap
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

/**
 * CameraController coordinates the Camera2 HAL state machine for the Pixel 11 Pro:
 * - Multi-Lens switching across 0.5x, 1x, 2x, 5x, 10x (12mm, 24mm, 48mm, 120mm, 240mm)
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
    }

    enum class LensZoom(
        val label: String,
        val focalLengthEquivMm: Int,
        val isCrop: Boolean,
        val cropFactor: Float
    ) {
        UW_05X("0.5x", 12, false, 1.0f),
        WIDE_1X("1x", 24, false, 1.0f),
        CROP_2X("2x", 48, true, 2.0f),
        TELE_5X("5x", 120, false, 1.0f),
        CROP_10X("10x", 240, true, 2.0f);

        val displayName: String get() = "$label (${focalLengthEquivMm}mm)"
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
    var currentMode: ControlMode = ControlMode.AUTO
        private set

    var currentFramerate: FramerateConfig = FramerateConfig.FPS_30
        private set

    var currentLens: LensZoom = LensZoom.WIDE_1X
        private set

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

    // Callback for UI updates
    var onFrameMetadataListener: ((dynamicBlackLevel: FloatArray, whiteLevel: Float) -> Unit)? = null

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
    }

    private fun extractCalibration(id: String, chars: CameraCharacteristics): CameraCalibration {
        val fm1 = ColorScienceUtils.colorSpaceTransformToFloatArray(chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1))
            ?: floatArrayOf(0.648f, 0.174f, 0.129f, 0.242f, 0.718f, 0.040f, -0.015f, -0.083f, 1.187f)
        val fm2 = ColorScienceUtils.colorSpaceTransformToFloatArray(chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2))
            ?: floatArrayOf(0.602f, 0.185f, 0.155f, 0.230f, 0.725f, 0.045f, -0.012f, -0.075f, 1.120f)
        val cc1 = ColorScienceUtils.colorSpaceTransformToFloatArray(chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1))
            ?: floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val cc2 = ColorScienceUtils.colorSpaceTransformToFloatArray(chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2))
            ?: floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val ill1 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.toInt() ?: 17
        val ill2 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt() ?: 21
        val wl = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.toFloat() ?: 4095.0f
        val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0

        return CameraCalibration(
            cameraId = id,
            forwardMatrix1 = fm1,
            forwardMatrix2 = fm2,
            calibrationTransform1 = cc1,
            calibrationTransform2 = cc2,
            illuminant1 = ill1,
            illuminant2 = ill2,
            whiteLevel = wl,
            bayerPattern = cfa
        )
    }

    @SuppressLint("MissingPermission")
    fun openCamera(preferredWidth: Int = 4080, preferredHeight: Int = 3064, cameraId: String? = null) {
        val targetPhysId = selectCameraForLens(currentLens)
        currentPhysicalCameraId = targetPhysId
        val targetLogicalId = if (cameraId != null && cameraManager.cameraIdList.contains(cameraId)) {
            cameraId
        } else {
            "0"
        }
        currentCameraId = targetLogicalId

        val chars = try {
            cameraManager.getCameraCharacteristics(targetPhysId)
        } catch (e: Exception) {
            cameraManager.getCameraCharacteristics(targetLogicalId)
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
            if (currentFramerate.isBinned) {
                activeWidth = BINNED_RES_WIDTH
                activeHeight = BINNED_RES_HEIGHT
            } else {
                activeWidth = matchedSize.width
                activeHeight = matchedSize.height
            }
            Log.i(TAG, "Selected Camera2 RAW_SENSOR size: ${activeWidth}x${activeHeight} for PhysCam $targetPhysId (Logical $targetLogicalId)")
        } else {
            fullSensorWidth = preferredWidth
            fullSensorHeight = preferredHeight
            activeWidth = preferredWidth
            activeHeight = preferredHeight
            Log.w(TAG, "No RAW_SENSOR sizes reported for PhysCam $targetPhysId, using ${activeWidth}x${activeHeight}")
        }

        Log.i(TAG, "Opening sensor: ID $targetLogicalId [PhysCam $targetPhysId] (${currentLens.displayName}), Open-Gate $activeWidth x $activeHeight, CFA $bayerPattern")

        // Initialize native GPU pipeline with open-gate resolution and CFA pattern
        engine.initialize(activeWidth, activeHeight, bayerPattern)

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
            LensZoom.WIDE_1X, LensZoom.CROP_2X -> wideCameraId
            LensZoom.TELE_5X, LensZoom.CROP_10X -> teleCameraId ?: "4"
        }
    }

    /**
     * Switches optical lens or in-sensor crop (0.5x, 1x, 2x, 5x, 10x).
     */
    fun setLensZoom(lens: LensZoom) {
        val targetPhysId = selectCameraForLens(lens)
        currentLens = lens

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

                if (resolutionChanged) {
                    fullSensorWidth = newW
                    fullSensorHeight = newH
                    activeWidth = newW
                    activeHeight = newH
                    val cfa = chars?.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: bayerPattern
                    bayerPattern = cfa
                    engine.initialize(activeWidth, activeHeight, bayerPattern)
                }

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

    private fun createOpenGateSession() {
        val camera = cameraDevice ?: return
        val rawSurface = engine.getCameraSurface()
            ?: throw IllegalStateException("PixelLogEngine native camera surface is null")

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

        // In-Sensor Crop Region for 2x and 10x
        val activeArray = cameraCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        if (activeArray != null && currentLens.isCrop) {
            val cropW = (activeArray.width() / currentLens.cropFactor).toInt()
            val cropH = (activeArray.height() / currentLens.cropFactor).toInt()
            val cropX = activeArray.left + (activeArray.width() - cropW) / 2
            val cropY = activeArray.top + (activeArray.height() - cropH) / 2
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(cropX, cropY, cropX + cropW, cropY + cropH))
        } else if (activeArray != null) {
            builder.set(CaptureRequest.SCALER_CROP_REGION, activeArray)
        }

        // Torch / Flash Mode
        if (isTorchEnabled) {
            builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
        } else {
            builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
        }

        if (currentMode == ControlMode.AUTO) {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        } else {
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
        }

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
                lastIso = iso
                lastExposureNs = exposureNs

                // 1. Dynamic Black Level
                val dynBlack = physResult.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
                    ?: currentCalibration?.dynamicBlackLevel
                    ?: floatArrayOf(256.0f, 256.0f, 256.0f, 256.0f)

                val whiteLevel = currentCalibration?.whiteLevel ?: 4095.0f

                // 2. SENSOR_NEUTRAL_COLOR_POINT (Phase 2.3: RAW white balance reference)
                val neutralRational = physResult.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
                val neutralPoint = if (neutralRational != null && neutralRational.size >= 3) {
                    floatArrayOf(
                        neutralRational[0].toFloat(),
                        neutralRational[1].toFloat(),
                        neutralRational[2].toFloat()
                    )
                } else {
                    floatArrayOf(0.55f, 1.0f, 0.70f)
                }

                // 3. Composite Matrix (Sensor -> Bradford -> Rec.2020 Linear + Exposure Gain)
                val compMatrix = ColorScienceUtils.computeCompositeColorMatrix(
                    neutralPoint = neutralPoint,
                    calibration = currentCalibration,
                    exposureGain = LogParams.XMAX
                )

                lastDynamicBlackLevel = dynBlack
                lastWhiteLevel = whiteLevel
                lastNeutralColorPoint = neutralPoint
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
                    neutralColorPoint = neutralPoint,
                    compositeMatrix = compMatrix,
                    exposureGain = LogParams.XMAX,
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
