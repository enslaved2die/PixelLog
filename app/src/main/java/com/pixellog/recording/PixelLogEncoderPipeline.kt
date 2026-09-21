package com.pixellog.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.os.SystemClock
import com.pixellog.audio.AudioCapturePipeline
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * PixelLogEncoderPipeline manages 10-bit HEVC (Main 10) and AV1 (Main 10) hardware encoders,
 * BT.2020 bitstream metadata signaling, pre-start sample queuing,
 * monotonic presentation timestamps, dynamic bitrate adjustment, and thread-safe MP4 container muxing.
 */
class PixelLogEncoderPipeline(
    val codec: VideoCodec = VideoCodec.HEVC,
    val bitratePreset: BitratePreset = BitratePreset.MBPS_140,
    val width: Int = 4080,
    val height: Int = 3072,
    val frameRate: Int = 30,
    val targetBitrate: Int = bitratePreset.targetBps,
    val peakBitrate: Int = bitratePreset.peakBps,
    val outputFile: File,
    val audioCapturePipeline: AudioCapturePipeline? = null
) {
    companion object {
        private const val TAG = "PixelLogEncoder"
        private const val TIMEOUT_USEC = 10_000L

        // Maximum AV1 Macroblocks (64x64) supported by Tensor G6 c2.google.av1.encoder (2040 blocks)
        const val AV1_MAX_MB_64 = 2040

        /**
         * Negotiates optimal resolution based on codec hardware capabilities:
         * - HEVC: Full open-gate 4080x3064 / 3840x2880
         * - AV1: Mod-64 4:3 Open-Gate 3328x2496 (52x39 = 2028 blocks <= 2040)
         */
        fun getOptimalResolution(codec: VideoCodec, sensorWidth: Int, sensorHeight: Int): Pair<Int, Int> {
            return if (codec == VideoCodec.AV1) {
                // If sensor is in binned 2032x1532 mode (e.g. 50/60fps)
                if (sensorWidth <= 2048) {
                    val w = (sensorWidth / 64) * 64
                    val h = (sensorHeight / 64) * 64
                    Pair(w, h)
                } else {
                    // Full sensor: Mod-64 3328x2496 complies with Tensor G6 2040 block limit
                    Pair(3328, 2496)
                }
            } else {
                // HEVC supports full sensor open gate
                val w = (sensorWidth / 2) * 2
                val h = (sensorHeight / 2) * 2
                Pair(w, h)
            }
        }
    }

    enum class VideoCodec(val mimeType: String, val displayName: String) {
        HEVC(MediaFormat.MIMETYPE_VIDEO_HEVC, "HEVC 10-bit"),
        AV1(MediaFormat.MIMETYPE_VIDEO_AV1, "AV1 10-bit")
    }

    enum class BitratePreset(val targetBps: Int, val peakBps: Int, val label: String) {
        MBPS_50(50_000_000, 65_000_000, "50 Mbps"),
        MBPS_100(100_000_000, 120_000_000, "100 Mbps"),
        MBPS_140(140_000_000, 180_000_000, "140 Mbps"),
        MBPS_180(180_000_000, 220_000_000, "180 Mbps")
    }

    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var mediaMuxer: MediaMuxer? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private val isAudioEnabled: Boolean
        get() = audioCapturePipeline != null
    private var isMuxerStarted = false
    private val muxerLock = Any()

    private val isRecording = AtomicBoolean(false)
    private var encodingThread: HandlerThread? = null
    private var encodingHandler: Handler? = null

    // Presentation Timestamp Baseline and Monotonic Guard
    private var lastSubmittedPtsUs: Long = -1L

    // Pending buffer queue for frames emitted before MediaMuxer starts
    private data class QueuedSample(
        val trackIndex: Int,
        val buffer: ByteBuffer,
        val info: MediaCodec.BufferInfo
    )
    private val preStartQueue = ConcurrentLinkedQueue<QueuedSample>()

    /**
     * Initializes the 10-bit hardware video encoder and prepares the input surface.
     */
    fun prepare(): Surface {
        val codecName = selectHardwareCodec(codec.mimeType)
            ?: throw IllegalStateException("No hardware ${codec.displayName} encoder available on this SoC")
        Log.i(TAG, "Selected Hardware ${codec.displayName} Encoder: $codecName")

        mediaCodec = MediaCodec.createByCodecName(codecName)

        // Effective bitrate calculation with AV1 limit clamp (Tensor G6 AV1 encoder max ~120 Mbps)
        val effectiveTargetBitrate = if (codec == VideoCodec.AV1) {
            min(targetBitrate, 120_000_000)
        } else {
            targetBitrate
        }
        val effectivePeakBitrate = if (codec == VideoCodec.AV1) {
            min(peakBitrate, 140_000_000)
        } else {
            peakBitrate
        }

        val format = MediaFormat.createVideoFormat(codec.mimeType, width, height).apply {
            if (codec == VideoCodec.AV1) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10)
            } else {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            }
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)

            // Bitstream Color Space Signaling (BT.2020 Primaries, Limited Range, SDR Transfer for Log)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)

            // Bitrate & Rate Control
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            setInteger(MediaFormat.KEY_BIT_RATE, effectiveTargetBitrate)
            setInteger("max-bitrate", effectivePeakBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1.0s GOP for responsive NLE scrubbing

            // Zero B-Frames for strictly monotonic PTS == DTS ordering
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)

            // Real-time encoding priority
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_COMPLEXITY, 2)
        }

        mediaCodec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = mediaCodec!!.createInputSurface()

        // Create MediaMuxer
        outputFile.parentFile?.mkdirs()
        mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        encodingThread = HandlerThread("PixelLog-Encoder-Drain").apply { start() }
        encodingHandler = Handler(encodingThread!!.looper)

        return inputSurface!!
    }

    /**
     * Finds hardware-accelerated Codec2 component, bypassing AOSP CPU fallback.
     */
    private fun selectHardwareCodec(mimeType: String): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            for (type in info.supportedTypes) {
                if (type.equals(mimeType, ignoreCase = true)) {
                    val name = info.name
                    if (info.isHardwareAccelerated && !name.startsWith("c2.android.")) {
                        return name
                    }
                }
            }
        }
        return null
    }

    /**
     * Starts recording and begins draining the encoder.
     */
    fun startRecording() {
        if (isRecording.getAndSet(true)) return
        mediaCodec?.start()
        lastSubmittedPtsUs = -1L

        val baselinePtsUs = SystemClock.elapsedRealtimeNanos() / 1000L

        if (audioCapturePipeline != null) {
            audioCapturePipeline.onAudioFormatChanged = { format ->
                synchronized(muxerLock) {
                    if (audioTrackIndex == -1 && mediaMuxer != null) {
                        audioTrackIndex = mediaMuxer!!.addTrack(format)
                        Log.i(TAG, "Added Audio Track at index $audioTrackIndex: $format")
                        startMuxerIfReady()
                    }
                }
            }

            audioCapturePipeline.onAudioSampleEmitted = { buffer, info ->
                synchronized(muxerLock) {
                    if (!isRecording.get() && !isMuxerStarted) return@synchronized

                    if (!isMuxerStarted) {
                        if (audioTrackIndex != -1) {
                            val clone = ByteBuffer.allocateDirect(info.size)
                            clone.put(buffer)
                            clone.flip()
                            val clonedInfo = MediaCodec.BufferInfo().apply {
                                set(0, info.size, info.presentationTimeUs, info.flags)
                            }
                            preStartQueue.add(QueuedSample(audioTrackIndex, clone, clonedInfo))
                        }
                    } else {
                        try {
                            mediaMuxer?.writeSampleData(audioTrackIndex, buffer, info)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error writing audio sample data", e)
                        }
                    }
                }
            }

            audioCapturePipeline.startEncoding(baselinePtsUs)
        }

        encodingHandler?.post { drainEncoderLoop() }
        Log.i(TAG, "Recording started ($width x $height @ ${codec.displayName}, audio=$isAudioEnabled) -> ${outputFile.absolutePath}")
    }

    fun getInputSurface(): Surface? = inputSurface

    /**
     * Dynamically updates encoder target bitrate on the fly.
     */
    fun setDynamicBitrate(bitrateBps: Int) {
        val effectiveBitrate = if (codec == VideoCodec.AV1) min(bitrateBps, 120_000_000) else bitrateBps
        val params = Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, effectiveBitrate)
        }
        try {
            mediaCodec?.setParameters(params)
            Log.i(TAG, "Updated dynamic bitrate to $effectiveBitrate bps")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update dynamic bitrate: ${e.message}")
        }
    }

    /**
     * Asynchronous drain loop processing encoded 10-bit NAL / OBU units.
     */
    private fun drainEncoderLoop() {
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRecording.get() || mediaCodec != null) {
            val encoder = mediaCodec ?: break
            val outputBufferIndex = try {
                encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
            } catch (e: Exception) {
                Log.e(TAG, "dequeueOutputBuffer error", e)
                break
            }

            when (outputBufferIndex) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!isRecording.get()) break
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        if (isMuxerStarted) {
                            Log.w(TAG, "MediaCodec output format changed twice!")
                        } else {
                            val newFormat = encoder.outputFormat
                            Log.i(TAG, "${codec.displayName} Encoder Output Format Defined: $newFormat")

                            // Audit CSD-0 bitstream headers
                            val csd0 = newFormat.getByteBuffer("csd-0")
                            if (csd0 != null) {
                                val bytes = ByteArray(csd0.remaining())
                                csd0.get(bytes)
                                csd0.rewind()
                                if (codec == VideoCodec.AV1) {
                                    Av1BitstreamAuditor.auditCsd0(bytes)
                                } else {
                                    HevcBitstreamAuditor.auditSpsVui(bytes)
                                }
                            }

                            videoTrackIndex = mediaMuxer!!.addTrack(newFormat)
                            startMuxerIfReady()
                        }
                    }
                }
                else -> {
                    if (outputBufferIndex >= 0) {
                        val encodedData = encoder.getOutputBuffer(outputBufferIndex)
                        if (encodedData != null) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            }

                            if (bufferInfo.size > 0) {
                                // Monotonic PTS guard
                                if (bufferInfo.presentationTimeUs <= lastSubmittedPtsUs) {
                                    bufferInfo.presentationTimeUs = lastSubmittedPtsUs + 1000L
                                }
                                lastSubmittedPtsUs = bufferInfo.presentationTimeUs

                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)

                                synchronized(muxerLock) {
                                    if (!isMuxerStarted) {
                                        val clone = ByteBuffer.allocateDirect(bufferInfo.size)
                                        clone.put(encodedData)
                                        clone.flip()
                                        val clonedInfo = MediaCodec.BufferInfo().apply {
                                            set(0, bufferInfo.size, bufferInfo.presentationTimeUs, bufferInfo.flags)
                                        }
                                        preStartQueue.add(QueuedSample(videoTrackIndex, clone, clonedInfo))
                                    } else {
                                        mediaMuxer!!.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                                    }
                                }
                            }

                            encoder.releaseOutputBuffer(outputBufferIndex, false)
                        }

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.i(TAG, "Encountered BUFFER_FLAG_END_OF_STREAM. Encoding complete.")
                            break
                        }
                    }
                }
            }
        }
    }

    private fun startMuxerIfReady() {
        val videoReady = videoTrackIndex != -1
        val audioReady = !isAudioEnabled || audioTrackIndex != -1

        if (videoReady && audioReady && !isMuxerStarted) {
            try {
                mediaMuxer?.start()
                isMuxerStarted = true
                Log.i(TAG, "MediaMuxer started (videoTrack=$videoTrackIndex, audioTrack=$audioTrackIndex). Flushing pre-start sample queue (${preStartQueue.size} samples).")

                while (!preStartQueue.isEmpty()) {
                    val sample = preStartQueue.poll() ?: break
                    try {
                        mediaMuxer?.writeSampleData(sample.trackIndex, sample.buffer, sample.info)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error writing queued sample to track ${sample.trackIndex}", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting MediaMuxer", e)
            }
        }
    }

    /**
     * Signals End-of-Stream and safely closes the recording pipeline.
     */
    fun stopRecording() {
        if (!isRecording.getAndSet(false)) return
        Log.i(TAG, "Signaling End-of-Stream to recording pipeline...")

        // 1. Finalize audio encoding first
        try {
            audioCapturePipeline?.stopEncoding()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audioCapturePipeline", e)
        }

        // 2. Signal EOS to video encoder
        try {
            mediaCodec?.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.e(TAG, "Error signaling EOS", e)
        }

        encodingThread?.quitSafely()
        try {
            encodingThread?.join(3000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaCodec", e)
        }

        synchronized(muxerLock) {
            if (isMuxerStarted) {
                try {
                    mediaMuxer?.stop()
                    mediaMuxer?.release()
                    mediaMuxer = null
                    Log.i(TAG, "MediaMuxer finalized cleanly: ${outputFile.absolutePath}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error finalizing MediaMuxer", e)
                }
            }
        }
    }
}
