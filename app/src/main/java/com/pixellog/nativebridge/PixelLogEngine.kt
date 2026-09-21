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
    private var pendingLogCurveType: Int = 0
    private var pendingExposureGain: Float = 1.0f

    fun initialize(width: Int, height: Int, bayerPattern: Int): Boolean {
        if (nativeHandle != 0L) {
            destroy()
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
            nativeSetLogCurveType(nativeHandle, pendingLogCurveType)
            nativeSetExposureGain(nativeHandle, pendingExposureGain)
        }
        return nativeHandle != 0L
    }

    fun destroy() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
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

    fun setLogCurveType(curveType: Int) {
        pendingLogCurveType = curveType
        if (nativeHandle != 0L) {
            nativeSetLogCurveType(nativeHandle, curveType)
        }
    }

    fun setExposureGain(gain: Float) {
        pendingExposureGain = gain
        if (nativeHandle != 0L) {
            nativeSetExposureGain(nativeHandle, gain)
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

    // Native JNI functions
    private external fun nativeCreate(width: Int, height: Int, bayerPattern: Int): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeGetCameraSurface(handle: Long): Surface?
    private external fun nativeSetEncoderSurface(handle: Long, surface: Surface?)
    private external fun nativeSetDisplaySurface(handle: Long, surface: Surface?)
    private external fun nativeLoadDisplayLut(handle: Long, lutBytes: ByteArray): Boolean
    private external fun nativeSetLutEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetLogCurveType(handle: Long, curveType: Int)
    private external fun nativeSetExposureGain(handle: Long, gain: Float)
    private external fun nativeUpdateMetadata(
        handle: Long,
        blackLevel: FloatArray,
        whiteLevel: Float,
        gains: FloatArray,
        colorMatrix: FloatArray
    )
}
