package com.example.tfgwj.utils

import android.os.Build
import android.util.Log
import java.io.File

object StorageTypeDetector {
    private const val TAG = "StorageTypeDetector"

    enum class StorageType {
        SSD_UFS,
        EMMC,
        UNKNOWN,
    }

    object BufferSizes {
        const val SSD_UFS = 1024 * 1024
        const val EMMC = 512 * 1024
        const val DEFAULT = 512 * 1024
        const val MINIMUM = 128 * 1024
    }

    @Volatile
    private var cachedStorageType: StorageType? = null

    fun detectStorageType(path: String? = null): StorageType {
        cachedStorageType?.let { return it }
        val detectedType = detectStorageTypeInternal(path)
        cachedStorageType = detectedType
        Log.i(TAG, "Storage type detected: $detectedType")
        return detectedType
    }

    private fun detectStorageTypeInternal(path: String?): StorageType {
        val rotational = checkRotationalFlag()
        if (rotational != null) {
            return if (rotational) StorageType.EMMC else StorageType.SSD_UFS
        }
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> StorageType.SSD_UFS
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> StorageType.SSD_UFS
            else -> StorageType.EMMC
        }
    }

    private fun checkRotationalFlag(): Boolean? {
        try {
            val rootDir = File("/sys/block")
            if (!rootDir.exists()) return null
            val devices = rootDir.listFiles() ?: return null
            for (device in devices) {
                val name = device.name
                if (name.startsWith("loop") || name.startsWith("ram") || name.startsWith("dm-")) {
                    continue
                }
                val rotationalFile = File(device, "queue/rotational")
                if (rotationalFile.exists()) {
                    try {
                        val rotational = rotationalFile.readText().trim()
                        Log.d(TAG, "Device $name rotational: $rotational")
                        return rotational == "1"
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to read rotational flag for $name", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect storage type from sysfs", e)
        }
        return null
    }

    fun getOptimalBufferSize(
        context: android.content.Context? = null,
        storageType: StorageType? = null,
    ): Int {
        val detectedType = storageType ?: detectStorageType()
        val runtime = Runtime.getRuntime()
        val maxMemoryMB = runtime.maxMemory() / (1024 * 1024)
        val availablePercent =
            context?.let {
                try {
                    val am = it.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                    val mi = android.app.ActivityManager.MemoryInfo()
                    am?.getMemoryInfo(mi)
                    if (mi.totalMem > 0) (mi.availMem.toFloat() / mi.totalMem * 100).toInt() else -1
                } catch (e: Exception) {
                    -1
                }
            } ?: -1
        val baseBuffer =
            when (detectedType) {
                StorageType.SSD_UFS -> BufferSizes.SSD_UFS
                StorageType.EMMC -> BufferSizes.EMMC
                StorageType.UNKNOWN -> BufferSizes.DEFAULT
            }
        return when {
            availablePercent in 0..10 || maxMemoryMB < 128 -> BufferSizes.MINIMUM
            availablePercent in 11..25 || maxMemoryMB < 256 -> baseBuffer / 2
            availablePercent in 26..50 || maxMemoryMB < 512 -> baseBuffer / 2
            else -> baseBuffer
        }
    }

    fun benchmarkStorageSpeed(sampleSize: Long = 1024 * 1024): Float {
        val tempFile = File(android.content.Context::class.java.getResource("/")?.path ?: "/tmp", ".storage_benchmark")
        try {
            val sampleData = ByteArray(minOf(sampleSize.toInt(), 1024 * 1024).coerceAtLeast(4096))
            java.util.Arrays.fill(sampleData, 0)
            java.io.RandomAccessFile(tempFile, "rw").use { raf ->
                raf.write(sampleData)
                raf.fd.sync()
            }
            val startTime = System.nanoTime()
            java.io.RandomAccessFile(tempFile, "r").use { raf ->
                val buffer = ByteArray(4096)
                while (raf.read(buffer) != -1) {
                    // Read through
                }
            }
            val endTime = System.nanoTime()
            val durationSec = (endTime - startTime) / 1_000_000_000.0
            val speedMBps = (sampleData.size / durationSec) / (1024 * 1024)
            Log.i(TAG, "Storage benchmark: ${"%.2f".format(speedMBps)} MB/s")
            return speedMBps.toFloat()
        } catch (e: Exception) {
            Log.w(TAG, "Storage benchmark failed", e)
            return -1f
        } finally {
            tempFile.delete()
        }
    }

    fun getStorageInfo(): String {
        val type = detectStorageType()
        val bufferSize = getOptimalBufferSize()
        return buildString {
            appendLine("Storage Info:")
            appendLine("  Type: $type")
            appendLine("  Recommended Buffer: ${bufferSize / 1024}KB")
            appendLine("  SDK: ${Build.VERSION.SDK_INT}")
            appendLine("  Brand: ${Build.BRAND}")
            appendLine("  Model: ${Build.MODEL}")
        }
    }

    fun clearCache() {
        cachedStorageType = null
    }
}
