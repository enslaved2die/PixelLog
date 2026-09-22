package com.pixellog.ui

import android.content.SharedPreferences
import com.pixellog.camera.CameraController
import com.pixellog.recording.PixelLogEncoderPipeline
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CameraPreferencesTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var cameraPreferences: CameraPreferences

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        cameraPreferences = CameraPreferences(fakePrefs)
    }

    @Test
    fun testDefaultValuesWhenUnset() {
        assertEquals(CameraController.LensZoom.WIDE_1X, cameraPreferences.lens)
        assertEquals(CameraController.FramerateConfig.FPS_30, cameraPreferences.framerate)
        assertEquals(PixelLogEncoderPipeline.VideoCodec.HEVC, cameraPreferences.codec)
        assertEquals(PixelLogEncoderPipeline.BitratePreset.MBPS_140, cameraPreferences.bitratePreset)
        assertEquals(PixelLogEncoderPipeline.ColorTransferMode.SDR_LOG, cameraPreferences.colorTransfer)
        assertFalse(cameraPreferences.isBakeLutActive)
        assertEquals(0, cameraPreferences.logCurveType)
        assertEquals("rec709", cameraPreferences.selectedLutId)

        assertTrue(cameraPreferences.isShutterAuto)
        assertTrue(cameraPreferences.isIsoAuto)
        assertTrue(cameraPreferences.isWbAuto)
        assertTrue(cameraPreferences.isFocusAuto)

        assertEquals(10, cameraPreferences.shutterIndex)
        assertEquals(2, cameraPreferences.isoIndex)
        assertEquals(8, cameraPreferences.evIndex)
        assertEquals(8, cameraPreferences.wbIndex)
        assertEquals(0.0f, cameraPreferences.focusDiopter, 1e-4f)
        assertEquals("SHUTTER", cameraPreferences.activeParamTabName)
    }

    @Test
    fun testSavingAndRestoringAllParameters() {
        cameraPreferences.lens = CameraController.LensZoom.TELE_5X
        cameraPreferences.framerate = CameraController.FramerateConfig.FPS_24
        cameraPreferences.codec = PixelLogEncoderPipeline.VideoCodec.AV1
        cameraPreferences.bitratePreset = PixelLogEncoderPipeline.BitratePreset.MBPS_180
        cameraPreferences.colorTransfer = PixelLogEncoderPipeline.ColorTransferMode.HLG
        cameraPreferences.isBakeLutActive = true
        cameraPreferences.logCurveType = 1
        cameraPreferences.selectedLutId = "dwg"

        cameraPreferences.isShutterAuto = false
        cameraPreferences.isIsoAuto = false
        cameraPreferences.isWbAuto = false
        cameraPreferences.isFocusAuto = false

        cameraPreferences.shutterIndex = 10
        cameraPreferences.isoIndex = 5
        cameraPreferences.evIndex = 4
        cameraPreferences.wbIndex = 12
        cameraPreferences.focusDiopter = 3.5f
        cameraPreferences.activeParamTabName = "ISO"

        // Create new instance over the same SharedPreferences to simulate cold restart
        val restoredPrefs = CameraPreferences(fakePrefs)

        assertEquals(CameraController.LensZoom.TELE_5X, restoredPrefs.lens)
        assertEquals(CameraController.FramerateConfig.FPS_24, restoredPrefs.framerate)
        assertEquals(PixelLogEncoderPipeline.VideoCodec.AV1, restoredPrefs.codec)
        assertEquals(PixelLogEncoderPipeline.BitratePreset.MBPS_180, restoredPrefs.bitratePreset)
        assertEquals(PixelLogEncoderPipeline.ColorTransferMode.HLG, restoredPrefs.colorTransfer)
        assertTrue(restoredPrefs.isBakeLutActive)
        assertEquals(1, restoredPrefs.logCurveType)
        assertEquals("dwg", restoredPrefs.selectedLutId)

        assertFalse(restoredPrefs.isShutterAuto)
        assertFalse(restoredPrefs.isIsoAuto)
        assertFalse(restoredPrefs.isWbAuto)
        assertFalse(restoredPrefs.isFocusAuto)

        assertEquals(10, restoredPrefs.shutterIndex)
        assertEquals(5, restoredPrefs.isoIndex)
        assertEquals(4, restoredPrefs.evIndex)
        assertEquals(12, restoredPrefs.wbIndex)
        assertEquals(3.5f, restoredPrefs.focusDiopter, 1e-4f)
        assertEquals("ISO", restoredPrefs.activeParamTabName)
    }

    @Test
    fun testCorruptedOrInvalidEnumValuesFallbackGracefully() {
        fakePrefs.edit()
            .putString(CameraPreferences.KEY_LENS, "NON_EXISTENT_LENS")
            .putString(CameraPreferences.KEY_FRAMERATE, "INVALID_FPS")
            .putString(CameraPreferences.KEY_CODEC, "UNKNOWN_CODEC")
            .putString(CameraPreferences.KEY_BITRATE_PRESET, "CORRUPT_BITRATE")
            .putString(CameraPreferences.KEY_TRANSFER_MODE, "BAD_TRANSFER")
            .apply()

        val restoredPrefs = CameraPreferences(fakePrefs)

        assertEquals(CameraController.LensZoom.WIDE_1X, restoredPrefs.lens)
        assertEquals(CameraController.FramerateConfig.FPS_30, restoredPrefs.framerate)
        assertEquals(PixelLogEncoderPipeline.VideoCodec.HEVC, restoredPrefs.codec)
        assertEquals(PixelLogEncoderPipeline.BitratePreset.MBPS_140, restoredPrefs.bitratePreset)
        assertEquals(PixelLogEncoderPipeline.ColorTransferMode.SDR_LOG, restoredPrefs.colorTransfer)
    }

    @Test
    fun testFramerate180ShutterRule() {
        assertEquals(48, CameraController.FramerateConfig.FPS_24.shutter180Speed)
        assertEquals(50, CameraController.FramerateConfig.FPS_25.shutter180Speed)
        assertEquals(60, CameraController.FramerateConfig.FPS_30.shutter180Speed)
        assertEquals(60, CameraController.FramerateConfig.FPS_29_97.shutter180Speed)
        assertEquals(96, CameraController.FramerateConfig.FPS_48.shutter180Speed)
        assertEquals(100, CameraController.FramerateConfig.FPS_50.shutter180Speed)
        assertEquals(120, CameraController.FramerateConfig.FPS_60.shutter180Speed)

        // Verify FPS_48 timing
        assertEquals(48.0, CameraController.FramerateConfig.FPS_48.fps, 1e-4)
        assertEquals(20_833_333L, CameraController.FramerateConfig.FPS_48.frameDurationNs)
        assertEquals(10_416_667L, CameraController.FramerateConfig.FPS_48.shutter180Ns)
        assertEquals("48", CameraController.FramerateConfig.FPS_48.label)
    }

    @Test
    fun testLensDcgSupport() {
        // Main sensor (ISOCELL GNK) has Dual Conversion Gain (DCG)
        assertTrue(CameraController.LensZoom.WIDE_1X.hasDcg)

        // Ultra-wide and Telephoto (Sony IMX858) are Single Conversion Gain (SCG)
        assertFalse(CameraController.LensZoom.UW_05X.hasDcg)
        assertFalse(CameraController.LensZoom.TELE_5X.hasDcg)
    }

    @Test
    fun testLensOisSupport() {
        // Main and Telephoto lenses have hardware OIS voice coils
        assertTrue(CameraController.LensZoom.WIDE_1X.hasOis)
        assertTrue(CameraController.LensZoom.TELE_5X.hasOis)

        // Ultra-wide lens lacks hardware OIS
        assertFalse(CameraController.LensZoom.UW_05X.hasOis)
    }

    @Test
    fun testStabilizationModeProperties() {
        // OFF: Zero crop (4080x3064), no OIS, no EIS
        assertFalse(CameraController.StabilizationMode.OFF.isCrop)
        assertEquals(android.hardware.camera2.CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF, CameraController.StabilizationMode.OFF.oisMode)
        assertEquals(android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF, CameraController.StabilizationMode.OFF.eisMode)

        // OIS: Zero crop (4080x3064), hardware OIS only, no EIS
        assertFalse(CameraController.StabilizationMode.OIS.isCrop)
        assertEquals(android.hardware.camera2.CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON, CameraController.StabilizationMode.OIS.oisMode)
        assertEquals(android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF, CameraController.StabilizationMode.OIS.eisMode)

        // EIS: 15% center crop (3468x2600 native raw un-upscaled), no OIS, EIS active
        assertTrue(CameraController.StabilizationMode.EIS.isCrop)
        assertEquals(android.hardware.camera2.CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF, CameraController.StabilizationMode.EIS.oisMode)
        assertEquals(android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON, CameraController.StabilizationMode.EIS.eisMode)

        // FULL: 15% center crop (3468x2600 native raw un-upscaled), hybrid OIS + EIS
        assertTrue(CameraController.StabilizationMode.FULL.isCrop)
        assertEquals(android.hardware.camera2.CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON, CameraController.StabilizationMode.FULL.oisMode)
        assertEquals(android.hardware.camera2.CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON, CameraController.StabilizationMode.FULL.eisMode)
    }

    @Test
    fun testStabilizationModePersistence() {
        // Default is OIS
        assertEquals(CameraController.StabilizationMode.OIS, cameraPreferences.stabilizationMode)

        // OFF mode persists
        cameraPreferences.stabilizationMode = CameraController.StabilizationMode.OFF
        val restoredPrefsOff = CameraPreferences(fakePrefs)
        assertEquals(CameraController.StabilizationMode.OFF, restoredPrefsOff.stabilizationMode)

        // OIS mode persists
        cameraPreferences.stabilizationMode = CameraController.StabilizationMode.OIS
        val restoredPrefsOis = CameraPreferences(fakePrefs)
        assertEquals(CameraController.StabilizationMode.OIS, restoredPrefsOis.stabilizationMode)

        // While EIS and FULL are hidden, they safely clamp/fallback to OIS
        fakePrefs.edit().putString(CameraPreferences.KEY_STABILIZATION_MODE, "EIS").apply()
        val restoredPrefsEis = CameraPreferences(fakePrefs)
        assertEquals(CameraController.StabilizationMode.OIS, restoredPrefsEis.stabilizationMode)

        fakePrefs.edit().putString(CameraPreferences.KEY_STABILIZATION_MODE, "FULL").apply()
        val restoredPrefsFull = CameraPreferences(fakePrefs)
        assertEquals(CameraController.StabilizationMode.OIS, restoredPrefsFull.stabilizationMode)

        // Invalid / corrupted value fallback to OIS
        fakePrefs.edit().putString(CameraPreferences.KEY_STABILIZATION_MODE, "UNKNOWN_MODE").apply()
        val restoredPrefsCorrupt = CameraPreferences(fakePrefs)
        assertEquals(CameraController.StabilizationMode.OIS, restoredPrefsCorrupt.stabilizationMode)
    }

    /**
     * Minimal in-memory implementation of SharedPreferences for JVM unit testing.
     */
    class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(data)

        override fun getString(key: String?, defValue: String?): String? {
            return (data[key] as? String) ?: defValue
        }

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return (data[key] as? MutableSet<String>) ?: defValues
        }

        override fun getInt(key: String?, defValue: Int): Int {
            return (data[key] as? Int) ?: defValue
        }

        override fun getLong(key: String?, defValue: Long): Long {
            return (data[key] as? Long) ?: defValue
        }

        override fun getFloat(key: String?, defValue: Float): Float {
            return (data[key] as? Float) ?: defValue
        }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            return (data[key] as? Boolean) ?: defValue
        }

        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(this)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class FakeEditor(private val sharedPreferences: FakeSharedPreferences) : SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) temp[key] = values
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) temp[key] = null
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearFlag = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearFlag) {
                    sharedPreferences.data.clear()
                }
                for ((k, v) in temp) {
                    if (v == null) {
                        sharedPreferences.data.remove(k)
                    } else {
                        sharedPreferences.data[k] = v
                    }
                }
            }
        }
    }
}
