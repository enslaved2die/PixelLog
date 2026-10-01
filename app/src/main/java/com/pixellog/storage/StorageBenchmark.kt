package com.pixellog.storage

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.Random

/**
 * StorageBenchmark conducts an fsync-validated sequential write throughput benchmark
 * on the target recording destination to determine whether it can sustain high-bitrate video capture.
 */
object StorageBenchmark {

    private const val TAG = "StorageBenchmark"
    private const val DEFAULT_TEST_SIZE_BYTES = 32 * 1024 * 1024 // 32 MB
    private const val CHUNK_SIZE_BYTES = 1024 * 1024 // 1 MB chunk

    enum class SpeedStatus {
        GOOD,     // >= 1.4x target bitrate
        WARN,     // 1.0x to 1.4x target bitrate (marginal throughput)
        CRITICAL  // < 1.0x target bitrate (dropped frames guaranteed)
    }

    data class BenchmarkResult(
        val writeSpeedMBps: Double,
        val writeSpeedMbps: Double,
        val targetBitrateMbps: Double,
        val status: SpeedStatus,
        val message: String
    )

    /**
     * Executes an fsync-enforced sequential write benchmark on the specified directory.
     *
     * @param directory The directory on internal or external storage to test.
     * @param targetBitrateBps The expected recording bitrate in bits per second (e.g. 220_000_000).
     * @param testSizeBytes The amount of data to write (default 32 MB).
     * @return BenchmarkResult containing measured speeds and pass/warn/fail status.
     */
    fun runBenchmark(
        directory: File,
        targetBitrateBps: Int,
        testSizeBytes: Int = DEFAULT_TEST_SIZE_BYTES
    ): BenchmarkResult {
        directory.mkdirs()
        val tempFile = File(directory, ".pixellog_speedtest_${System.currentTimeMillis()}.tmp")
        val targetBitrateMBps = targetBitrateBps.toDouble() / (8.0 * 1024.0 * 1024.0)
        val targetBitrateMbps = targetBitrateBps.toDouble() / 1_000_000.0

        // Prepare 1MB deterministic random buffer to prevent flash controller compression illusions
        val chunk = ByteArray(CHUNK_SIZE_BYTES)
        Random(42).nextBytes(chunk)

        var totalBytesWritten = 0L
        val startTimeNs: Long
        val endTimeNs: Long

        try {
            FileOutputStream(tempFile).use { fos ->
                val fd = fos.fd
                val chunks = testSizeBytes / CHUNK_SIZE_BYTES
                startTimeNs = System.nanoTime()

                for (i in 0 until chunks) {
                    fos.write(chunk)
                    totalBytesWritten += CHUNK_SIZE_BYTES
                }

                // CRITICAL: Flush kernel dirty page cache directly to physical NAND controller
                fd.sync()
                endTimeNs = System.nanoTime()
            }
        } catch (e: Exception) {
            try {
                Log.e(TAG, "Benchmark failed on ${directory.absolutePath}", e)
            } catch (_: Throwable) {
                println("[$TAG] Benchmark failed: ${e.message}")
            }
            return BenchmarkResult(
                writeSpeedMBps = 0.0,
                writeSpeedMbps = 0.0,
                targetBitrateMbps = targetBitrateMbps,
                status = SpeedStatus.CRITICAL,
                message = "Write error: ${e.localizedMessage ?: "I/O failure"}"
            )
        } finally {
            try {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            } catch (_: Exception) {}
        }

        val durationSec = (endTimeNs - startTimeNs).toDouble() / 1_000_000_000.0
        if (durationSec <= 0.0 || totalBytesWritten <= 0L) {
            return BenchmarkResult(
                writeSpeedMBps = 0.0,
                writeSpeedMbps = 0.0,
                targetBitrateMbps = targetBitrateMbps,
                status = SpeedStatus.CRITICAL,
                message = "Benchmark timing error"
            )
        }

        val writeSpeedMBps = (totalBytesWritten.toDouble() / (1024.0 * 1024.0)) / durationSec
        val writeSpeedMbps = (totalBytesWritten.toDouble() * 8.0 / 1_000_000.0) / durationSec

        val status = when {
            writeSpeedMBps >= targetBitrateMBps * 1.4 -> SpeedStatus.GOOD
            writeSpeedMBps >= targetBitrateMBps -> SpeedStatus.WARN
            else -> SpeedStatus.CRITICAL
        }

        val message = when (status) {
            SpeedStatus.GOOD -> String.format(java.util.Locale.US, "%.0f MB/s (Fast)", writeSpeedMBps)
            SpeedStatus.WARN -> String.format(java.util.Locale.US, "%.0f MB/s (Marginal for %.0f Mbps)", writeSpeedMBps, targetBitrateMbps)
            SpeedStatus.CRITICAL -> String.format(java.util.Locale.US, "%.0f MB/s (TOO SLOW for %.0f Mbps)", writeSpeedMBps, targetBitrateMbps)
        }

        try {
            Log.i(TAG, "Benchmark on ${directory.name}: $message (target: ${targetBitrateMBps.toInt()} MB/s, duration: ${String.format(java.util.Locale.US, "%.2f", durationSec)}s)")
        } catch (_: Throwable) {
            println("[$TAG] Benchmark on ${directory.name}: $message")
        }
        return BenchmarkResult(
            writeSpeedMBps = writeSpeedMBps,
            writeSpeedMbps = writeSpeedMbps,
            targetBitrateMbps = targetBitrateMbps,
            status = status,
            message = message
        )
    }
}
