package com.pixellog.ui

import android.content.Context
import android.content.SharedPreferences
import com.pixellog.camera.CameraController
import com.pixellog.recording.PixelLogEncoderPipeline

/**
 * CameraPreferences manages persistent storage of camera, encoding, color science,
 * and UI settings across application cold starts and restarts via Android SharedPreferences.
 */
class CameraPreferences(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    companion object {
        const val PREFS_NAME = "pixellog_preferences"

        const val KEY_LENS = "pref_lens"
        const val KEY_FRAMERATE = "pref_framerate"
        const val KEY_CODEC = "pref_codec"
        const val KEY_BITRATE_PRESET = "pref_bitrate_preset"
        const val KEY_TRANSFER_MODE = "pref_transfer_mode"
        const val KEY_BAKE_LUT = "pref_bake_lut"
        const val KEY_GENERATE_SIDECAR = "pref_generate_sidecar"
        const val KEY_STABILIZATION_MODE = "pref_stabilization_mode"
        const val KEY_LOG_CURVE_TYPE = "pref_log_curve_type"
        const val KEY_SELECTED_LUT_ID = "pref_selected_lut_id"

        const val KEY_SHUTTER_AUTO = "pref_shutter_auto"
        const val KEY_ISO_AUTO = "pref_iso_auto"
        const val KEY_WB_AUTO = "pref_wb_auto"
        const val KEY_FOCUS_AUTO = "pref_focus_auto"

        const val KEY_SHUTTER_INDEX = "pref_shutter_index"
        const val KEY_ISO_INDEX = "pref_iso_index"
        const val KEY_EV_INDEX = "pref_ev_index"
        const val KEY_WB_INDEX = "pref_wb_index"
        const val KEY_FOCUS_DIOPTER = "pref_focus_diopter"

        const val KEY_ACTIVE_PARAM_TAB = "pref_active_param_tab"
    }

    var lens: CameraController.LensZoom
        get() {
            val name = prefs.getString(KEY_LENS, CameraController.LensZoom.WIDE_1X.name)
            return try {
                CameraController.LensZoom.valueOf(name ?: CameraController.LensZoom.WIDE_1X.name)
            } catch (_: Exception) {
                CameraController.LensZoom.WIDE_1X
            }
        }
        set(value) = prefs.edit().putString(KEY_LENS, value.name).apply()

    var framerate: CameraController.FramerateConfig
        get() {
            val name = prefs.getString(KEY_FRAMERATE, CameraController.FramerateConfig.FPS_30.name)
            return try {
                CameraController.FramerateConfig.valueOf(name ?: CameraController.FramerateConfig.FPS_30.name)
            } catch (_: Exception) {
                CameraController.FramerateConfig.FPS_30
            }
        }
        set(value) = prefs.edit().putString(KEY_FRAMERATE, value.name).apply()

    var codec: PixelLogEncoderPipeline.VideoCodec
        get() {
            val name = prefs.getString(KEY_CODEC, PixelLogEncoderPipeline.VideoCodec.HEVC.name)
            return try {
                PixelLogEncoderPipeline.VideoCodec.valueOf(name ?: PixelLogEncoderPipeline.VideoCodec.HEVC.name)
            } catch (_: Exception) {
                PixelLogEncoderPipeline.VideoCodec.HEVC
            }
        }
        set(value) = prefs.edit().putString(KEY_CODEC, value.name).apply()

    var bitratePreset: PixelLogEncoderPipeline.BitratePreset
        get() {
            val name = prefs.getString(KEY_BITRATE_PRESET, PixelLogEncoderPipeline.BitratePreset.MBPS_140.name)
            return try {
                PixelLogEncoderPipeline.BitratePreset.valueOf(name ?: PixelLogEncoderPipeline.BitratePreset.MBPS_140.name)
            } catch (_: Exception) {
                PixelLogEncoderPipeline.BitratePreset.MBPS_140
            }
        }
        set(value) = prefs.edit().putString(KEY_BITRATE_PRESET, value.name).apply()

    var colorTransfer: PixelLogEncoderPipeline.ColorTransferMode
        get() {
            val name = prefs.getString(KEY_TRANSFER_MODE, PixelLogEncoderPipeline.ColorTransferMode.SDR_LOG.name)
            return try {
                PixelLogEncoderPipeline.ColorTransferMode.valueOf(name ?: PixelLogEncoderPipeline.ColorTransferMode.SDR_LOG.name)
            } catch (_: Exception) {
                PixelLogEncoderPipeline.ColorTransferMode.SDR_LOG
            }
        }
        set(value) = prefs.edit().putString(KEY_TRANSFER_MODE, value.name).apply()

    var isBakeLutActive: Boolean
        get() = prefs.getBoolean(KEY_BAKE_LUT, false)
        set(value) = prefs.edit().putBoolean(KEY_BAKE_LUT, value).apply()

    var isSidecarEnabled: Boolean
        get() = prefs.getBoolean(KEY_GENERATE_SIDECAR, false)
        set(value) = prefs.edit().putBoolean(KEY_GENERATE_SIDECAR, value).apply()

    var stabilizationMode: CameraController.StabilizationMode
        get() {
            val name = prefs.getString(KEY_STABILIZATION_MODE, CameraController.StabilizationMode.OIS.name)
            val parsed = try {
                CameraController.StabilizationMode.valueOf(name ?: CameraController.StabilizationMode.OIS.name)
            } catch (_: Exception) {
                CameraController.StabilizationMode.OIS
            }
            // Temporarily clamp EIS / FULL to OIS while they are hidden
            return if (parsed == CameraController.StabilizationMode.EIS || parsed == CameraController.StabilizationMode.FULL) {
                CameraController.StabilizationMode.OIS
            } else {
                parsed
            }
        }
        set(value) = prefs.edit().putString(KEY_STABILIZATION_MODE, value.name).apply()

    var logCurveType: Int
        get() = prefs.getInt(KEY_LOG_CURVE_TYPE, 0).coerceIn(0, 2)
        set(value) = prefs.edit().putInt(KEY_LOG_CURVE_TYPE, value.coerceIn(0, 2)).apply()

    var selectedLutId: String
        get() = prefs.getString(KEY_SELECTED_LUT_ID, "rec709") ?: "rec709"
        set(value) = prefs.edit().putString(KEY_SELECTED_LUT_ID, value).apply()

    var isShutterAuto: Boolean
        get() = prefs.getBoolean(KEY_SHUTTER_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_SHUTTER_AUTO, value).apply()

    var isIsoAuto: Boolean
        get() = prefs.getBoolean(KEY_ISO_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_ISO_AUTO, value).apply()

    var isWbAuto: Boolean
        get() = prefs.getBoolean(KEY_WB_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_WB_AUTO, value).apply()

    var isFocusAuto: Boolean
        get() = prefs.getBoolean(KEY_FOCUS_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_FOCUS_AUTO, value).apply()

    var shutterIndex: Int
        get() = prefs.getInt(KEY_SHUTTER_INDEX, 10)
        set(value) = prefs.edit().putInt(KEY_SHUTTER_INDEX, value).apply()

    var isoIndex: Int
        get() = prefs.getInt(KEY_ISO_INDEX, 2)
        set(value) = prefs.edit().putInt(KEY_ISO_INDEX, value).apply()

    var evIndex: Int
        get() = prefs.getInt(KEY_EV_INDEX, 8)
        set(value) = prefs.edit().putInt(KEY_EV_INDEX, value).apply()

    var wbIndex: Int
        get() = prefs.getInt(KEY_WB_INDEX, 8)
        set(value) = prefs.edit().putInt(KEY_WB_INDEX, value).apply()

    var focusDiopter: Float
        get() = prefs.getFloat(KEY_FOCUS_DIOPTER, 0.0f).coerceIn(0.0f, 10.0f)
        set(value) = prefs.edit().putFloat(KEY_FOCUS_DIOPTER, value.coerceIn(0.0f, 10.0f)).apply()

    var activeParamTabName: String
        get() = prefs.getString(KEY_ACTIVE_PARAM_TAB, "SHUTTER") ?: "SHUTTER"
        set(value) = prefs.edit().putString(KEY_ACTIVE_PARAM_TAB, value).apply()

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
