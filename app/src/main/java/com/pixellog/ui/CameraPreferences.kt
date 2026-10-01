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

        const val KEY_FRAMERATE = "pref_framerate"
        const val KEY_CODEC = "pref_codec"
        const val KEY_BITRATE_PRESET = "pref_bitrate_preset"
        const val KEY_TRANSFER_MODE = "pref_transfer_mode"
        const val KEY_BAKE_LUT = "pref_bake_lut"
        const val KEY_GENERATE_SIDECAR = "pref_generate_sidecar"
        const val KEY_STABILIZATION_MODE = "pref_stabilization_mode"
        const val KEY_SELECTED_LUT_ID = "pref_selected_lut_id"

        const val KEY_ACTIVE_PARAM_TAB = "pref_active_param_tab"
        const val KEY_STORAGE_TARGET_ID = "pref_storage_target_id"

        val LEGACY_CONTROL_KEYS = listOf(
            "pref_lens",
            "pref_shutter_auto",
            "pref_iso_auto",
            "pref_wb_auto",
            "pref_focus_auto",
            "pref_shutter_index",
            "pref_iso_index",
            "pref_ev_index",
            "pref_wb_index",
            "pref_focus_diopter"
        )
    }

    init {
        // Camera controls (Focus, Shutter, WB, EV, ISO) and sensor/lens selection
        // are session-only and must never be persisted across app restarts (app always
        // defaults to the main 1x sensor on launch). Purge legacy keys if present.
        val editor = prefs.edit()
        var hasLegacy = false
        for (key in LEGACY_CONTROL_KEYS) {
            if (prefs.contains(key)) {
                editor.remove(key)
                hasLegacy = true
            }
        }
        if (hasLegacy) {
            editor.apply()
        }
    }

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

    var selectedLutId: String
        get() = prefs.getString(KEY_SELECTED_LUT_ID, "rec709") ?: "rec709"
        set(value) = prefs.edit().putString(KEY_SELECTED_LUT_ID, value).apply()

    var activeParamTabName: String
        get() = prefs.getString(KEY_ACTIVE_PARAM_TAB, "SHUTTER") ?: "SHUTTER"
        set(value) = prefs.edit().putString(KEY_ACTIVE_PARAM_TAB, value).apply()

    var storageTargetId: String
        get() = prefs.getString(KEY_STORAGE_TARGET_ID, com.pixellog.storage.StorageTargetManager.TARGET_INTERNAL_ID)
            ?: com.pixellog.storage.StorageTargetManager.TARGET_INTERNAL_ID
        set(value) = prefs.edit().putString(KEY_STORAGE_TARGET_ID, value).apply()

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
