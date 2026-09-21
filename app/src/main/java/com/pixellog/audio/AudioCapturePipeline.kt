package com.pixellog.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * AudioCapturePipeline manages:
 * 1. High-fidelity 48 kHz stereo 16-bit PCM recording via AudioRecord.
 * 2. Real-time dual-channel (Left/Right) VU / peak / RMS dBFS metering.
 * 3. Broadcast-quality 320 kbps AAC-LC hardware encoding with sample-accurate PTS timestamps.
 */
class AudioCapturePipeline(
    private val audioInputManager: AudioInputManager,
    val sampleRate: Int = 48000,
    val channelCount: Int = 2,
    val audioBitrate: Int = 320_000
) {
    companion object {
        private const val TAG = "AudioCapturePipeline"
        private const val TIMEOUT_USEC = 10_000L
        private const val FRAMES_PER_CHUNK = 1024 // 1024 stereo frames = 4096 bytes (~21.3 ms)
    }

    private var audioRecord: AudioRecord? = null
    private var audioCodec: MediaCodec? = null

    private val isCapturing = AtomicBoolean(false)
    private val isEncoding = AtomicBoolean(false)

    private var captureThread: Thread? = null
    private var encoderDrainThread: Thread? = null

    // Callbacks
    var onAudioLevels: ((leftPeakDbfs: Float, rightPeakDbfs: Float) -> Unit)? = null
    var onAudioFormatChanged: ((MediaFormat) -> Unit)? = null
    var onAudioSampleEmitted: ((ByteBuffer, MediaCodec.BufferInfo) -> Unit)? = null

    // Presentation timestamp tracking
    private var baselinePtsUs: Long = 0L
    private var totalFramesEncoded: Long = 0L
    private var lastEmittedAudioPtsUs: Long = -1L

    /**
     * Starts background PCM capture and live VU metering.
     */
    @SuppressLint("MissingPermission")
    fun startCapture() {
        if (isCapturing.getAndSet(true)) return

        val channelConfig = if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = (minBufferSize * 4).coerceAtLeast(16384)

        // Try CAMCORDER audio source first (tuned for video stereo), fallback to MIC
        var record: AudioRecord? = try {
            AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create AudioRecord with CAMCORDER source, trying MIC", e)
            null
        }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            isCapturing.set(false)
            Log.e(TAG, "AudioRecord failed to initialize")
            return
        }

        audioRecord = record
        audioInputManager.bindAudioRecord(record)

        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord.startRecording failed", e)
            record.release()
            audioRecord = null
            isCapturing.set(false)
            return
        }

        captureThread = Thread({ audioCaptureLoop(record) }, "PixelLog-AudioCapture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        Log.i(TAG, "Audio capture started ($sampleRate Hz, $channelCount ch, source=${record.audioSource})")
    }

    /**
     * Starts AAC-LC hardware encoder and synchronizes baseline PTS.
     */
    fun startEncoding(ptsBaselineUs: Long) {
        if (isEncoding.getAndSet(true)) return

        baselinePtsUs = ptsBaselineUs
        totalFramesEncoded = 0L
        lastEmittedAudioPtsUs = -1L

        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAMES_PER_CHUNK * channelCount * 2 * 2)
            }

            audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioCodec!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioCodec!!.start()

            encoderDrainThread = Thread({ drainAudioEncoderLoop() }, "PixelLog-AudioDrain").apply {
                start()
            }
            Log.i(TAG, "AAC-LC Audio Encoder started @ $audioBitrate bps (baseline: $baselinePtsUs us)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio encoder", e)
            isEncoding.set(false)
        }
    }

    /**
     * Main audio capture loop running at THREAD_PRIORITY_URGENT_AUDIO.
     */
    private fun audioCaptureLoop(record: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val shortsPerChunk = FRAMES_PER_CHUNK * channelCount
        val audioShorts = ShortArray(shortsPerChunk)
        val pcmByteBuffer = ByteBuffer.allocateDirect(shortsPerChunk * 2).order(ByteOrder.nativeOrder())
        var lastMeterUpdateMs = 0L

        while (isCapturing.get()) {
            val shortsRead = record.read(audioShorts, 0, shortsPerChunk)
            if (shortsRead <= 0) {
                if (shortsRead == AudioRecord.ERROR_INVALID_OPERATION || shortsRead == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "AudioRecord error: $shortsRead")
                    break
                }
                continue
            }

            // Real-time VU / dBFS calculation
            val nowMs = SystemClock.uptimeMillis()
            if (nowMs - lastMeterUpdateMs >= 40) { // ~25 fps update rate for UI
                lastMeterUpdateMs = nowMs
                computeLevels(audioShorts, shortsRead)
            }

            // Feed to AAC encoder if currently recording
            if (isEncoding.get()) {
                pcmByteBuffer.clear()
                val shortView = pcmByteBuffer.asShortBuffer()
                shortView.put(audioShorts, 0, shortsRead)
                val bytesToSubmit = shortsRead * 2
                feedEncoder(pcmByteBuffer, bytesToSubmit, shortsRead / channelCount)
            }
        }

        Log.i(TAG, "Exiting AudioCaptureLoop.")
    }

    /**
     * Computes peak and RMS dBFS for Left and Right channels.
     */
    private fun computeLevels(shorts: ShortArray, count: Int) {
        var leftPeak = 0
        var rightPeak = 0
        var leftSumSq = 0.0
        var rightSumSq = 0.0
        var pairs = 0

        var i = 0
        while (i < count - 1) {
            val left = shorts[i].toInt()
            val right = shorts[i + 1].toInt()

            val leftAbs = kotlin.math.abs(left)
            val rightAbs = kotlin.math.abs(right)

            if (leftAbs > leftPeak) leftPeak = leftAbs
            if (rightAbs > rightPeak) rightPeak = rightAbs

            leftSumSq += (left * left).toDouble()
            rightSumSq += (right * right).toDouble()
            pairs++
            i += 2
        }

        if (pairs == 0) return

        val normLeftPeak = (leftPeak.toFloat() / 32767.0f).coerceIn(0.0001f, 1.0f)
        val normRightPeak = (rightPeak.toFloat() / 32767.0f).coerceIn(0.0001f, 1.0f)

        val leftDbfs = (20.0f * log10(normLeftPeak)).coerceIn(-80.0f, 0.0f)
        val rightDbfs = (20.0f * log10(normRightPeak)).coerceIn(-80.0f, 0.0f)

        onAudioLevels?.invoke(leftDbfs, rightDbfs)
    }

    /**
     * Submits uncompressed PCM chunk to MediaCodec.
     */
    private fun feedEncoder(buffer: ByteBuffer, byteSize: Int, frameCount: Int) {
        val codec = audioCodec ?: return
        try {
            val inputIndex = codec.dequeueInputBuffer(TIMEOUT_USEC)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    buffer.position(0)
                    buffer.limit(byteSize)
                    inputBuffer.put(buffer)

                    // Presentation timestamp in microseconds (sample-accurate)
                    val ptsUs = baselinePtsUs + (totalFramesEncoded * 1_000_000L / sampleRate)
                    totalFramesEncoded += frameCount

                    codec.queueInputBuffer(inputIndex, 0, byteSize, ptsUs, 0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "feedEncoder exception", e)
        }
    }

    /**
     * Drains AAC packets from MediaCodec.
     */
    private fun drainAudioEncoderLoop() {
        val bufferInfo = MediaCodec.BufferInfo()

        while (isEncoding.get() || audioCodec != null) {
            val codec = audioCodec ?: break
            val outputIndex = try {
                codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
            } catch (e: Exception) {
                Log.e(TAG, "audioCodec dequeueOutputBuffer error", e)
                break
            }

            when (outputIndex) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!isEncoding.get()) break
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val newFormat = codec.outputFormat
                    Log.i(TAG, "AAC Audio Encoder Output Format Defined: $newFormat")
                    onAudioFormatChanged?.invoke(newFormat)
                }
                else -> {
                    if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            }

                            if (bufferInfo.size > 0) {
                                // Strictly monotonic PTS guard
                                if (bufferInfo.presentationTimeUs <= lastEmittedAudioPtsUs) {
                                    bufferInfo.presentationTimeUs = lastEmittedAudioPtsUs + 1000L
                                }
                                lastEmittedAudioPtsUs = bufferInfo.presentationTimeUs

                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                                onAudioSampleEmitted?.invoke(outputBuffer, bufferInfo)
                            }

                            codec.releaseOutputBuffer(outputIndex, false)
                        }

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.i(TAG, "AAC Audio Encoder reached END_OF_STREAM")
                            break
                        }
                    }
                }
            }
        }
        Log.i(TAG, "Exiting drainAudioEncoderLoop.")
    }

    /**
     * Signals EOS and stops audio encoding.
     */
    fun stopEncoding() {
        if (!isEncoding.getAndSet(false)) return
        Log.i(TAG, "Stopping audio encoder...")

        // Send EOS to encoder input
        try {
            audioCodec?.let { codec ->
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_USEC)
                if (inputIndex >= 0) {
                    val ptsUs = baselinePtsUs + (totalFramesEncoded * 1_000_000L / sampleRate)
                    codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error signaling audio EOS", e)
        }

        try {
            encoderDrainThread?.join(1500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        try {
            audioCodec?.stop()
            audioCodec?.release()
            audioCodec = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audioCodec", e)
        }
        Log.i(TAG, "Audio encoder stopped.")
    }

    /**
     * Stops audio capture completely and releases AudioRecord.
     */
    fun stopCapture() {
        if (isEncoding.get()) {
            stopEncoding()
        }

        if (!isCapturing.getAndSet(false)) return
        Log.i(TAG, "Stopping audio capture...")

        audioInputManager.unbindAudioRecord()

        try {
            captureThread?.join(1500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
        Log.i(TAG, "Audio capture stopped.")
    }
}
