package com.pixellog.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.Log
import java.io.File

/**
 * StorageTarget represents a physical or emulated storage destination for video recordings.
 */
data class StorageTarget(
    val id: String,
    val name: String,
    val isRemovable: Boolean,
    val isPrimary: Boolean,
    val rootDirectory: File,
    val recordingDirectory: File
) {
    /**
     * Queries available free bytes on this storage target.
     */
    val availableBytes: Long
        get() = try {
            val dir = if (recordingDirectory.exists()) {
                recordingDirectory
            } else if (recordingDirectory.mkdirs()) {
                recordingDirectory
            } else {
                rootDirectory
            }
            val stat = StatFs(dir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) {
            0L
        }

    /**
     * Queries total bytes on this storage target.
     */
    val totalBytes: Long
        get() = try {
            val dir = if (recordingDirectory.exists()) {
                recordingDirectory
            } else if (recordingDirectory.mkdirs()) {
                recordingDirectory
            } else {
                rootDirectory
            }
            val stat = StatFs(dir.absolutePath)
            stat.blockCountLong * stat.blockSizeLong
        } catch (_: Exception) {
            0L
        }

    /**
     * Formatted string of available storage (e.g. "45.2 GB").
     */
    val availableFormatted: String
        get() {
            val gb = availableBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            return String.format(java.util.Locale.US, "%.1f GB", gb)
        }
}

/**
 * StorageTargetManager discovers and manages internal and external (USB-C OTG / SD)
 * storage volumes for recording in PixelLog.
 */
class StorageTargetManager(private val context: Context) {

    companion object {
        private const val TAG = "StorageTargetManager"
        const val TARGET_INTERNAL_ID = "internal_primary"
    }

    private val storageManager: StorageManager? =
        context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager

    /**
     * Resolves the primary internal storage target (always available).
     */
    fun getInternalTarget(): StorageTarget {
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val recordingDir = File(moviesDir, "PixelLog")
        return StorageTarget(
            id = TARGET_INTERNAL_ID,
            name = "INTERNAL",
            isRemovable = false,
            isPrimary = true,
            rootDirectory = moviesDir,
            recordingDirectory = recordingDir
        )
    }

    /**
     * Discovers all available mounted storage targets (Internal + any connected USB SSDs / SD cards).
     */
    fun getAvailableTargets(): List<StorageTarget> {
        val targets = mutableListOf<StorageTarget>()
        targets.add(getInternalTarget())

        try {
            val externalDirs = context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES)
            val volumes: List<StorageVolume> = storageManager?.storageVolumes ?: emptyList()

            // Match externalDirs with StorageVolumes
            for (i in 1 until externalDirs.size) {
                val appExtDir = externalDirs[i] ?: continue
                val vol = storageManager?.getStorageVolume(appExtDir) ?: volumes.getOrNull(i)
                val isRemovable = vol?.isRemovable ?: true
                val desc = vol?.getDescription(context)?.trim()
                val volName = if (!desc.isNullOrEmpty()) desc else "USB Drive $i"
                val volId = vol?.uuid ?: "usb_$i"

                // App-specific external media path requires ZERO runtime permissions and provides direct POSIX access
                val recordingDir = File(appExtDir, "PixelLog")

                // Determine root directory if accessible
                val rootDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vol?.directory != null) {
                    vol.directory ?: appExtDir
                } else {
                    appExtDir
                }

                targets.add(
                    StorageTarget(
                        id = volId,
                        name = "USB: $volName",
                        isRemovable = isRemovable,
                        isPrimary = false,
                        rootDirectory = rootDir,
                        recordingDirectory = recordingDir
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating external storage volumes", e)
        }

        return targets
    }

    /**
     * Resolves target by saved ID or defaults to internal storage.
     */
    fun resolveTarget(targetId: String?): StorageTarget {
        if (targetId == null || targetId == TARGET_INTERNAL_ID) {
            return getInternalTarget()
        }
        val targets = getAvailableTargets()
        return targets.find { it.id == targetId } ?: getInternalTarget()
    }
}
