package com.pixellog.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.ColorSpaceTransform
import android.os.Build
import android.util.Log
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * CameraDump queries and logs complete HAL characterization data for all
 * physical and logical cameras on the device (Main, Ultrawide, Telephoto).
 *
 * Implements Phase 0, Task 2 of the custom log camera pipeline plan.
 */
object CameraDump {
    private const val TAG = "CameraDump"

    data class CameraSummary(
        val cameraId: String,
        val facing: String,
        val focalLengths: List<Float>,
        val rawSizes: List<Size>,
        val whiteLevel: Int,
        val cfaPattern: Int,
        val hasForwardMatrices: Boolean,
        val shadingMapSize: Size?
    )

    fun dumpAllCameras(context: Context): String {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val rootJson = JSONObject()
        val camerasArray = JSONArray()

        rootJson.put("device_model", Build.MODEL)
        rootJson.put("device_product", Build.PRODUCT)
        rootJson.put("device_hardware", Build.HARDWARE)
        rootJson.put("android_version", Build.VERSION.RELEASE)
        rootJson.put("sdk_int", Build.VERSION.SDK_INT)

        val processedIds = mutableSetOf<String>()

        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val camObj = dumpCameraCharacteristics(id, chars, cameraManager, processedIds)
            camerasArray.put(camObj)
            processedIds.add(id)
        }

        rootJson.put("cameras", camerasArray)
        val jsonString = rootJson.toString(2)

        // Write dump to application files directory
        try {
            val dumpFile = File(context.filesDir, "camera_dump.json")
            dumpFile.writeText(jsonString)
            Log.i(TAG, "Successfully wrote camera dump to: ${dumpFile.absolutePath}")

            // Also attempt to save to external Movies/PixelLog directory if writable
            val extDir = File(context.getExternalFilesDir(null), "dumps")
            extDir.mkdirs()
            File(extDir, "camera_dump.json").writeText(jsonString)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save camera dump file: ${e.message}")
        }

        return jsonString
    }

    private fun dumpCameraCharacteristics(
        id: String,
        chars: CameraCharacteristics,
        cameraManager: CameraManager,
        processedIds: MutableSet<String>
    ): JSONObject {
        val obj = JSONObject()
        obj.put("camera_id", id)

        val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> "BACK"
            CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
            else -> "EXTERNAL"
        }
        obj.put("facing", facing)

        // Available capabilities
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val capsArray = JSONArray()
        for (c in caps) {
            val name = when (c) {
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
                else -> "CAP_$c"
            }
            capsArray.put(name)
        }
        obj.put("capabilities", capsArray)

        // Focal Lengths
        val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: floatArrayOf()
        val focalArray = JSONArray()
        for (f in focals) focalArray.put(f.toDouble())
        obj.put("focal_lengths_mm", focalArray)

        // RAW_SENSOR stream sizes and min durations
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawSizesArray = JSONArray()
        if (map != null) {
            val sizes = map.getOutputSizes(ImageFormat.RAW_SENSOR)
            if (sizes != null) {
                for (s in sizes) {
                    val sizeObj = JSONObject()
                    sizeObj.put("width", s.width)
                    sizeObj.put("height", s.height)
                    val minDurNs = map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, s)
                    sizeObj.put("min_frame_duration_ns", minDurNs)
                    val maxFps = if (minDurNs > 0) 1_000_000_000.0 / minDurNs else 0.0
                    sizeObj.put("max_fps", maxFps)
                    rawSizesArray.put(sizeObj)
                }
            }
        }
        obj.put("raw_sensor_sizes", rawSizesArray)

        // Sensor Info: White level, Black level pattern, CFA
        val whiteLevel = chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 4095
        obj.put("sensor_info_white_level", whiteLevel)

        val blackPattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        if (blackPattern != null) {
            val blArr = JSONArray()
            for (r in 0 until 2) {
                for (c in 0 until 2) {
                    blArr.put(blackPattern.getOffsetForIndex(c, r))
                }
            }
            obj.put("sensor_black_level_pattern", blArr)
        }

        val cfa = chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        val cfaStr = when (cfa) {
            0 -> "RGGB"
            1 -> "GRBG"
            2 -> "GBRG"
            3 -> "BGGR"
            4 -> "RGB"
            else -> "UNKNOWN_$cfa"
        }
        obj.put("cfa_pattern", cfaStr)

        // Color Calibration Matrices
        obj.put("forward_matrix_1", colorSpaceTransformToJson(chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)))
        obj.put("forward_matrix_2", colorSpaceTransformToJson(chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)))
        obj.put("color_transform_1", colorSpaceTransformToJson(chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)))
        obj.put("color_transform_2", colorSpaceTransformToJson(chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)))
        obj.put("calibration_transform_1", colorSpaceTransformToJson(chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)))
        obj.put("calibration_transform_2", colorSpaceTransformToJson(chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)))

        obj.put("reference_illuminant_1", chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) ?: 0)
        obj.put("reference_illuminant_2", chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2) ?: 0)

        // Lens shading map modes
        val shadingModes = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)
        if (shadingModes != null) {
            val modesArray = JSONArray()
            for (m in shadingModes) modesArray.put(m)
            obj.put("lens_shading_map_modes", modesArray)
        }

        // Physical Sub-Cameras if Logical Multi-Camera
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val physicalIds = chars.physicalCameraIds
            if (physicalIds.isNotEmpty()) {
                val physicalArray = JSONArray()
                for (physId in physicalIds) {
                    if (!processedIds.contains(physId)) {
                        try {
                            val physChars = cameraManager.getCameraCharacteristics(physId)
                            val physObj = dumpCameraCharacteristics(physId, physChars, cameraManager, processedIds)
                            physicalArray.put(physObj)
                            processedIds.add(physId)
                        } catch (e: Exception) {
                            Log.w(TAG, "Error querying physical camera $physId: ${e.message}")
                        }
                    }
                }
                obj.put("physical_sub_cameras", physicalArray)
            }
        }

        return obj
    }

    private fun colorSpaceTransformToJson(cst: ColorSpaceTransform?): JSONArray? {
        if (cst == null) return null
        val arr = JSONArray()
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val r = cst.getElement(col, row)
                arr.put(r.toDouble())
            }
        }
        return arr
    }
}
