package com.pixellog.nativebridge

import android.view.Surface

/**
 * PixelLogEngine bridges Android Camera2 and Kotlin UI with the native
 * C++ zero-copy GPU debayering and encoding pipeline (libpixellog.so).
 */
class PixelLogEngine {

    companion object {
        init {
            System.loadLibrary("pixellog")
        }
    }

    private var nativeHandle: Long = 0L
    private var pendingDisplaySurface: Surface? = null
    private var pendingEncoderSurface: Surface? = null
    private var pendingLutBytes: ByteArray? = null
    private var pendingLutEnabled: Boolean = true
    private var pendingBakeLutToEncoder: Boolean = false
    private var pendingExposureGain: Float = 1.0f
    private var pendingEdrEnabled: Boolean = false
    private var pendingShadowRecoveryMode: Int = 0
    private var pendingShadowRecoveryEnabled: Boolean = false
    private var pendingHighlightRecoveryEnabled: Boolean = false

    private fun releaseNativeHandle() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    fun initialize(width: Int, height: Int, bayerPattern: Int): Boolean {
        if (nativeHandle != 0L) {
            releaseNativeHandle()
        }
        nativeHandle = nativeCreate(width, height, bayerPattern)
        if (nativeHandle != 0L) {
            // Apply any cached surfaces or LUT configured before native initialization
            pendingDisplaySurface?.let {
                nativeSetDisplaySurface(nativeHandle, it)
            }
            pendingEncoderSurface?.let {
                nativeSetEncoderSurface(nativeHandle, it)
            }
            pendingLutBytes?.let {
                nativeLoadDisplayLut(nativeHandle, it)
            }
            nativeSetLutEnabled(nativeHandle, pendingLutEnabled)
            nativeSetBakeLutToEncoder(nativeHandle, pendingBakeLutToEncoder)
            nativeSetExposureGain(nativeHandle, pendingExposureGain)
            nativeSetEdrEnabled(nativeHandle, pendingEdrEnabled)
            nativeSetShadowRecoveryMode(nativeHandle, pendingShadowRecoveryMode)
            nativeSetShadowRecoveryEnabled(nativeHandle, pendingShadowRecoveryEnabled)
            nativeSetHighlightRecoveryEnabled(nativeHandle, pendingHighlightRecoveryEnabled)
        }
        return nativeHandle != 0L
    }

    fun destroy() {
        releaseNativeHandle()
        pendingDisplaySurface = null
        pendingEncoderSurface = null
        pendingLutBytes = null
    }

    fun getCameraSurface(): Surface? {
        if (nativeHandle == 0L) return null
        return nativeGetCameraSurface(nativeHandle)
    }

    fun setEncoderSurface(surface: Surface?) {
        pendingEncoderSurface = surface
        if (nativeHandle != 0L) {
            nativeSetEncoderSurface(nativeHandle, surface)
        }
    }

    fun setDisplaySurface(surface: Surface?) {
        pendingDisplaySurface = surface
        if (nativeHandle != 0L) {
            nativeSetDisplaySurface(nativeHandle, surface)
        }
    }

    fun loadDisplayLut(lutBytes: ByteArray): Boolean {
        pendingLutBytes = lutBytes
        if (nativeHandle == 0L) return false
        return nativeLoadDisplayLut(nativeHandle, lutBytes)
    }

    fun setLutEnabled(enabled: Boolean) {
        pendingLutEnabled = enabled
        if (nativeHandle != 0L) {
            nativeSetLutEnabled(nativeHandle, enabled)
        }
    }

    fun setBakeLutToEncoder(enabled: Boolean) {
        pendingBakeLutToEncoder = enabled
        if (nativeHandle != 0L) {
            nativeSetBakeLutToEncoder(nativeHandle, enabled)
        }
    }

    fun setExposureGain(gain: Float) {
        pendingExposureGain = gain
        if (nativeHandle != 0L) {
            nativeSetExposureGain(nativeHandle, gain)
        }
    }

    fun setEdrEnabled(enabled: Boolean) {
        pendingEdrEnabled = enabled
        setShadowRecoveryMode(if (enabled) 1 else 0)
        setHighlightRecoveryEnabled(enabled)
        if (nativeHandle != 0L) {
            nativeSetEdrEnabled(nativeHandle, enabled)
        }
    }

    fun setShadowRecoveryMode(mode: Int) {
        pendingShadowRecoveryMode = mode
        pendingShadowRecoveryEnabled = mode > 0
        if (nativeHandle != 0L) {
            nativeSetShadowRecoveryMode(nativeHandle, mode)
            nativeSetShadowRecoveryEnabled(nativeHandle, mode > 0)
        }
    }

    fun setShadowRecoveryEnabled(enabled: Boolean) {
        setShadowRecoveryMode(if (enabled) 1 else 0)
    }

    fun setHighlightRecoveryEnabled(enabled: Boolean) {
        pendingHighlightRecoveryEnabled = enabled
        if (nativeHandle != 0L) {
            nativeSetHighlightRecoveryEnabled(nativeHandle, enabled)
        }
    }

    fun updateFrameMetadata(
        timestampNs: Long,
        blackLevel: FloatArray,
        whiteLevel: Float,
        neutralColorPoint: FloatArray,
        compositeMatrix: FloatArray,
        exposureGain: Float,
        shadingMap: FloatArray?,
        shadingWidth: Int,
        shadingHeight: Int
    ) {
        if (nativeHandle != 0L) {
            nativeUpdateFrameMetadata(
                nativeHandle,
                timestampNs,
                blackLevel,
                whiteLevel,
                neutralColorPoint,
                compositeMatrix,
                exposureGain,
                shadingMap,
                shadingWidth,
                shadingHeight
            )
        }
    }

    fun updateMetadata(
        blackLevel: FloatArray,
        whiteLevel: Float,
        gains: FloatArray,
        colorMatrix: FloatArray
    ) {
        if (nativeHandle != 0L) {
            nativeUpdateMetadata(nativeHandle, blackLevel, whiteLevel, gains, colorMatrix)
        }
    }

    fun getSceneExposureDelta(): Float {
        if (nativeHandle == 0L) return 0.0f
        return nativeGetSceneExposureDelta(nativeHandle)
    }

    // Native JNI functions
    private external fun nativeGetSceneExposureDelta(handle: Long): Float
    private external fun nativeCreate(width: Int, height: Int, bayerPattern: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeGetCameraSurface(handle: Long): Surface?
    private external fun nativeSetEncoderSurface(handle: Long, surface: Surface?)
    private external fun nativeSetDisplaySurface(handle: Long, surface: Surface?)
    private external fun nativeLoadDisplayLut(handle: Long, lutBytes: ByteArray): Boolean
    private external fun nativeSetLutEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetBakeLutToEncoder(handle: Long, enabled: Boolean)
    private external fun nativeSetExposureGain(handle: Long, gain: Float)
    private external fun nativeSetEdrEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetShadowRecoveryMode(handle: Long, mode: Int)
    private external fun nativeSetShadowRecoveryEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetHighlightRecoveryEnabled(handle: Long, enabled: Boolean)
    private external fun nativeUpdateFrameMetadata(
        handle: Long,
        timestampNs: Long,
        blackLevel: FloatArray,
        whiteLevel: Float,
        neutralPoint: FloatArray,
        compositeMatrix: FloatArray,
        exposureGain: Float,
        shadingMap: FloatArray?,
        shadingWidth: Int,
        shadingHeight: Int
    )
    private external fun nativeUpdateMetadata(
        handle: Long,
        blackLevel: FloatArray,
        whiteLevel: Float,
        gains: FloatArray,
        colorMatrix: FloatArray
    )
}
