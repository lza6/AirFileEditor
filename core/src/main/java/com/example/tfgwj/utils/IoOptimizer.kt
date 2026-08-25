package com.example.tfgwj.utils

import android.util.Log
import com.example.tfgwj.performance.MetricCollector
import com.example.tfgwj.performance.MetricNames
import com.example.tfgwj.performance.MetricUnits
import com.example.tfgwj.performance.PerformanceMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicInteger

/**
 * IoOptimizer - V7.0.0 Storage Type Detection Upgrade
 *
 * Integrated with StorageTypeDetector for intelligent buffer optimization:
 * - SSD/UFS: 1MB buffer for high-speed storage
 * - eMMC/HDD: 512KB buffer for slower storage
 * - Dynamic memory-aware buffer sizing
 *
 * Features:
 * - NIO Zero-Copy
 * - Dynamic concurrency control
 * - Incremental update detection
 */
@Deprecated("已由 IoEngine 替代，请使用 com.example.tfgwj.performance.IoEngine", ReplaceWith("IoEngine", "com.example.tfgwj.performance.IoEngine"))
object IoOptimizer {
    private const val TAG = "IoOptimizer"

    // Dynamic concurrency: CPU core count
    private val CORE_COUNT = Runtime.getRuntime().availableProcessors()
    private val MAX_CONCURRENCY = CORE_COUNT.coerceAtLeast(4)

    // Buffer pool (object pool) - reduce GC pressure during intensive IO
    private val BUFFER_POOL = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private const val POOL_SIZE = 8

    /**
     * V7.0.0: Get optimal buffer size based on storage type and available memory
     *
     * Integration with StorageTypeDetector:
     * - Detects storage type (SSD/UFS vs eMMC/HDD)
     * - Adjusts buffer size accordingly
     * - Falls back to memory-based calculation if detection fails
     */
    fun getOptimalBufferSize(context: android.content.Context? = null): Int {
        // V7.0.0: Use StorageTypeDetector for intelligent buffer sizing
        return try {
            StorageTypeDetector.getOptimalBufferSize(context)
        } catch (e: Exception) {
            // Fallback to original memory-based logic
            Log.w(TAG, "StorageTypeDetector failed, using fallback: ${e.message}")
            getOptimalBufferSizeLegacy(context)
        }
    }

    /**
     * Legacy buffer size calculation (fallback)
     */
    private fun getOptimalBufferSizeLegacy(context: android.content.Context? = null): Int {
        val runtime = Runtime.getRuntime()
        val maxMemoryMB = runtime.maxMemory() / (1024 * 1024)

        // If context available, get ActivityManager.MemoryInfo
        val availablePercent =
            context?.let {
                val am = it.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                val mi = android.app.ActivityManager.MemoryInfo()
                am?.getMemoryInfo(mi)
                if (mi.totalMem > 0) (mi.availMem.toFloat() / mi.totalMem * 100).toInt() else -1
            } ?: -1

        return when {
            availablePercent in 0..10 || maxMemoryMB < 128 -> 128 * 1024 // Very low memory: 128KB
            availablePercent in 11..25 || maxMemoryMB < 256 -> 256 * 1024 // Low memory: 256KB
            maxMemoryMB < 512 -> 512 * 1024 // Mid-range: 512KB
            else -> 1024 * 1024 // High-end: 1MB
        }
    }

    /**
     * 从池中获取一个缓冲区
     */
    fun acquireBuffer(): ByteArray {
        return BUFFER_POOL.poll() ?: ByteArray(getOptimalBufferSize())
    }

    /**
     * 将缓冲区归还池中
     */
    fun releaseBuffer(buffer: ByteArray) {
        if (BUFFER_POOL.size < POOL_SIZE) {
            BUFFER_POOL.offer(buffer)
        }
    }

    private fun unmap(buffer: java.nio.MappedByteBuffer?) {
        if (buffer == null) return
        try {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
            theUnsafeField.isAccessible = true
            val unsafe = theUnsafeField.get(null)
            val invokeCleanerMethod = unsafeClass.getMethod("invokeCleaner", java.nio.ByteBuffer::class.java)
            invokeCleanerMethod.invoke(unsafe, buffer)
        } catch (e: Exception) {
            try {
                val cleanerMethod = buffer.javaClass.getMethod("cleaner")
                cleanerMethod.isAccessible = true
                val cleaner = cleanerMethod.invoke(buffer)
                if (cleaner != null) {
                    val cleanMethod = cleaner.javaClass.getMethod("clean")
                    cleanMethod.isAccessible = true
                    cleanMethod.invoke(cleaner)
                }
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * 极速复制文件 (智能调度：mmap 原生映射 / NIO Zero-Copy)
     * V6.1.0 引入 Chunked mmap，支持大文件分片映射
     */
    fun fastCopy(
        source: File,
        target: File,
    ): Boolean {
        val size = source.length()
        // V10: 针对极小文件 (< 32KB)，跳过 mmap 这种昂贵的系统调用，直接使用 Buffer 读写
        if (size > 0 && size < 32 * 1024) {
            return fastCopySmallFile(source, target)
        }

        val startTime = System.currentTimeMillis()

        return try {
            val parent = target.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }

            if (size <= 0) return false

            if (!target.exists()) {
                target.createNewFile()
            }

            // V6.1.0 引入分片 mmap (对全量文件生效，除非映射失败)
            java.io.RandomAccessFile(source, "r").use { rafSrc ->
                java.io.RandomAccessFile(target, "rw").use { rafDest ->
                    rafDest.setLength(size)
                    val inChannel = rafSrc.channel
                    val outChannel = rafDest.channel

                    // 单片映射阈值：8MB
                    val chunkSize = 8L * 1024 * 1024
                    var position = 0L

                    while (position < size) {
                        val remaining = size - position
                        val currentChunk = if (remaining < chunkSize) remaining else chunkSize

                        var inBuffer: java.nio.MappedByteBuffer? = null
                        var outBuffer: java.nio.MappedByteBuffer? = null
                        try {
                            inBuffer = inChannel.map(FileChannel.MapMode.READ_ONLY, position, currentChunk)
                            outBuffer = outChannel.map(FileChannel.MapMode.READ_WRITE, position, currentChunk)

                            val writeStartTime = System.currentTimeMillis()
                            outBuffer.put(inBuffer)
                            val writeDuration = System.currentTimeMillis() - writeStartTime
                            position += currentChunk

                            // V10: 记录物理写入延迟
                            PerformanceMonitor.recordIOWriteLatency(writeDuration, currentChunk)
                        } finally {
                            unmap(inBuffer)
                            unmap(outBuffer)
                        }
                    }
                    outChannel.force(true) // 硬件层物理落盘
                }
            }

            target.setLastModified(source.lastModified())

            // 记录性能指标
            val durationMs = System.currentTimeMillis() - startTime
            PerformanceMonitor.recordIOCopy(
                bytesCopied = size,
                durationMs = durationMs,
                usedMmap = true,
                wasIncrementalSkip = false,
            )

            true
        } catch (e: Exception) {
            Log.e(TAG, "FastCopy (mmap) 失败: ${source.name}, 尝试 NIO Fallback", e)

            // Fallback to NIO transferTo
            try {
                FileInputStream(source).channel.use { sourceChannel ->
                    FileOutputStream(target).channel.use { targetChannel ->
                        val channelSize = sourceChannel.size()
                        var transferred: Long = 0
                        while (transferred < channelSize) {
                            transferred += sourceChannel.transferTo(transferred, channelSize - transferred, targetChannel)
                        }
                    }
                }
                target.setLastModified(source.lastModified())

                // 记录性能指标（NIO Fallback）
                val durationMs = System.currentTimeMillis() - startTime
                PerformanceMonitor.recordIOCopy(
                    bytesCopied = size,
                    durationMs = durationMs,
                    usedMmap = false,
                    wasIncrementalSkip = false,
                )

                true
            } catch (fallbackE: Exception) {
                Log.e(TAG, "NIO Fallback 亦失败: ${source.name}", fallbackE)
                false
            }
        }
    }

    /**
     * V10: 针对小文件的优化复制
     * 避免 mmap 的页表映射开销和 context switch
     */
    private fun fastCopySmallFile(source: File, target: File): Boolean {
        val startTime = System.currentTimeMillis()
        val size = source.length()
        return try {
            val parent = target.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }

            val buffer = acquireBuffer()
            try {
                FileInputStream(source).use { ins ->
                    FileOutputStream(target).use { out ->
                        var bytesRead: Int
                        while (ins.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                        }
                    }
                }
            } finally {
                releaseBuffer(buffer)
            }

            target.setLastModified(source.lastModified())
            val durationMs = System.currentTimeMillis() - startTime
            PerformanceMonitor.recordIOCopy(
                bytesCopied = size,
                durationMs = durationMs,
                usedMmap = false,
                wasIncrementalSkip = false
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Small file copy failed: ${source.name}", e)
            false
        }
    }

    /**
     * 增量检测：判断文件是否需要更新
     */
    fun needsUpdate(
        source: File,
        target: File,
    ): Boolean {
        if (!target.exists()) {
            MetricCollector.recordIO(
                name = MetricNames.IO_INCREMENTAL_HIT_RATE,
                value = 0.0,
                unit = MetricUnits.PERCENT,
                tags = mapOf("reason" to "target_not_exists"),
            )
            return true
        }
        if (source.length() != target.length()) {
            MetricCollector.recordIO(
                name = MetricNames.IO_INCREMENTAL_HIT_RATE,
                value = 0.0,
                unit = MetricUnits.PERCENT,
                tags = mapOf("reason" to "size_mismatch"),
            )
            return true
        }

        // 如果文件大小相同且修改时间完全一致，大概率是同一个文件
        if (source.lastModified() == target.lastModified()) return false

        // 小文件 (< 5MB) 执行全量 MD5 极速校验
        if (source.length() < 5 * 1024 * 1024) {
            return !FileHasher.areFilesEqual(source, target)
        }

        // 5MB 以上大文件利用 V4.0.0 最新实装的抽样哈希 (首/中/尾 比对)
        // 消除全量读取大文件的几秒甚至几十秒耗时，把比对压缩到毫秒级
        return !FileHasher.areFilesEqualWithSampling(source, target)
    }

    /**
     * 并行处理文件列表
     * @param items 要处理的项
     * @param action 处理逻辑
     * @param progressCallback 进度回调 (已完成, 总数, 当前项名称)
     */
    suspend fun <T> parallelProcess(
        items: List<T>,
        action: suspend (T) -> Boolean,
        progressCallback: ((Int, Int, String) -> Unit)? = null,
    ): ProcessResult =
        coroutineScope {
            val total = items.size
            val successCount = AtomicInteger(0)
            val failedCount = AtomicInteger(0)
            val semaphore = Semaphore(MAX_CONCURRENCY)

            val deferreds =
                items.map { item ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val success = action(item)
                            val currentSuccess = if (success) successCount.incrementAndGet() else successCount.get()
                            val currentFailed = if (!success) failedCount.incrementAndGet() else failedCount.get()

                            val itemName =
                                when (item) {
                                    is File -> item.name
                                    is String -> item
                                    else -> item.toString()
                                }

                            progressCallback?.invoke(currentSuccess + currentFailed, total, itemName)
                            success
                        }
                    }
                }

            deferreds.awaitAll()

            ProcessResult(
                success = failedCount.get() == 0,
                successCount = successCount.get(),
                failedCount = failedCount.get(),
                total = total,
            )
        }

    /**
     * 处理结果数据类
     */
    data class ProcessResult(
        val success: Boolean,
        val successCount: Int,
        val failedCount: Int,
        val total: Int,
    )
}
