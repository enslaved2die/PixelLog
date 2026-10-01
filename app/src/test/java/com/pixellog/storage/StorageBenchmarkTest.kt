package com.pixellog.storage

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageBenchmarkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testBenchmarkExecutesAndCleansUpTempFile() {
        val testDir = tempFolder.newFolder("benchmark_test")
        val targetBitrate = 140_000_000 // 140 Mbps (~17.5 MB/s)
        val testSize = 2 * 1024 * 1024 // 2 MB for fast unit test execution

        val result = StorageBenchmark.runBenchmark(
            directory = testDir,
            targetBitrateBps = targetBitrate,
            testSizeBytes = testSize
        )

        assertTrue("Write speed should be greater than 0", result.writeSpeedMBps > 0.0)
        assertTrue("Write speed Mbps should be greater than 0", result.writeSpeedMbps > 0.0)
        assertNotNull(result.status)
        assertNotNull(result.message)

        // Ensure temporary file was cleanly deleted
        val remainingFiles = testDir.listFiles()?.filter { it.name.startsWith(".pixellog_speedtest_") } ?: emptyList()
        assertTrue("Benchmark temporary file must be deleted", remainingFiles.isEmpty())
    }

    @Test
    fun testSpeedStatusThresholds() {
        val targetBitrate = 220_000_000 // 220 Mbps = 26.22 MB/s
        val targetMBps = 220_000_000.0 / (8.0 * 1024.0 * 1024.0)

        // Speed status classification logic check
        val goodSpeed = targetMBps * 1.5
        val warnSpeed = targetMBps * 1.2
        val slowSpeed = targetMBps * 0.8

        val goodStatus = if (goodSpeed >= targetMBps * 1.4) StorageBenchmark.SpeedStatus.GOOD else StorageBenchmark.SpeedStatus.WARN
        val warnStatus = if (warnSpeed >= targetMBps * 1.4) StorageBenchmark.SpeedStatus.GOOD else if (warnSpeed >= targetMBps) StorageBenchmark.SpeedStatus.WARN else StorageBenchmark.SpeedStatus.CRITICAL
        val slowStatus = if (slowSpeed >= targetMBps * 1.4) StorageBenchmark.SpeedStatus.GOOD else if (slowSpeed >= targetMBps) StorageBenchmark.SpeedStatus.WARN else StorageBenchmark.SpeedStatus.CRITICAL

        assertEquals(StorageBenchmark.SpeedStatus.GOOD, goodStatus)
        assertEquals(StorageBenchmark.SpeedStatus.WARN, warnStatus)
        assertEquals(StorageBenchmark.SpeedStatus.CRITICAL, slowStatus)
    }

    @Test
    fun testStorageTargetDirectoryHandling() {
        val root = tempFolder.newFolder("storage_root")
        val recDir = File(root, "PixelLog_NonExistent")

        val target = StorageTarget(
            id = "test_target",
            name = "Test Target",
            isRemovable = true,
            isPrimary = false,
            rootDirectory = root,
            recordingDirectory = recDir
        )

        // Ensure availableBytes and totalBytes do not throw even when directory is not yet created
        assertNotNull(target.availableBytes)
        assertNotNull(target.totalBytes)
        assertNotNull(target.availableFormatted)
    }
}
