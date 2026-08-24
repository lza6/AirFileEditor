package com.example.tfgwj.performance

import com.example.tfgwj.utils.AppLogger
import com.example.tfgwj.utils.FileHasher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 统一高性能 IO 引擎 (V14 引擎融合)
 *
 * 融合 IoOptimizer 与 HighPerformanceIoEngine 的核心能力，
 * 根据文件大小自动选择最优复制策略：
 *
 * - 大文件 (>= 16MB): mmap 零拷贝，受 Semaphore(16) 限流保护
 * - 小文件 (< 32KB): 直接缓冲区复制，避免 mmap 页表开销
 * - 常规文件: 自适应缓冲区流，配合 AdaptiveBufferManager 动态调优
 *
 * 增量检测策略：
 * - 文件不存在/大小不同 → 需要更新
 * - 修改时间一致 → 跳过（大概率相同）
 * - 小文件 (< 5MB) → 全量 MD5 比对
 * - 大文件 (>= 5MB) → 三段抽样哈希 (首/中/尾)
 */
object IoEngine {
    private const val TAG = "IoEngine"

    // 策略阈值
    const val MMAP_THRESHOLD = 16L * 1024 * 1024 // 16MB
    const val SMALL_FILE_THRESHOLD = 32L * 1024   // 32KB
    const val SAMPLING_THRESHOLD = 1024 * 1024     // 1MB (抽样哈希门槛)
    const val FULL_MD5_THRESHOLD = 5L * 1024 * 1024 // 5MB (全量 MD5 门槛)

    // mmap 并发限流
    private val mmapLimiter = Semaphore(16)

    // 自适应缓冲区管理器
    val bufferManager: AdaptiveBufferManager = AdaptiveBufferManagerImpl()

    // 缓冲区对象池 (减少 GC 压力)
    private val bufferPool = ConcurrentLinkedQueue<ByteArray>()
    private const val POOL_SIZE = 8

    // 并发处理默认值
    private val coreCount = Runtime.getRuntime().availableProcessors()
    val defaultConcurrency = coreCount.coerceAtLeast(4)

    // ==================== 复制策略 ====================

    /**
     * 智能复制入口：根据文件大小自动选择最优策略
     * @return 复制的字节数，失败返回 0
     */
    fun fastCopy(source: File, target: File): Long {
        if (!source.exists()) return 0L
        target.parentFile?.mkdirs()

        val size = source.length()
        return when {
            // 策略 1: 极小文件 — 直接缓冲区复制
            size in 1..SMALL_FILE_THRESHOLD -> smallFileCopy(source, target)
            // 策略 2: 大文件 — mmap 零拷贝
            size >= MMAP_THRESHOLD -> mmapCopy(source, target)
            // 策略 3: 常规文件 — 自适应缓冲区流
            else -> adaptiveBufferCopy(source, target)
        }
    }

    /**
     * 小文件直接缓冲区复制
     */
    private fun smallFileCopy(source: File, target: File): Long {
        val startTime = System.nanoTime()
        return try {
            val buffer = acquireBuffer()
            try {
                FileInputStream(source).use { ins ->
                    FileOutputStream(target).use { out ->
                        var bytesRead: Int
                        var total = 0L
                        while (ins.read(buffer).also { bytesRead = it } != -1) {
                            out.write(buffer, 0, bytesRead)
                            total += bytesRead
                        }
                        out.flush()
                        total
                    }
                }
            } finally {
                releaseBuffer(buffer)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "小文件复制失败: ${source.name}: ${e.message}")
            0L
        }
    }

    /**
     * mmap 零拷贝（大文件受 Semaphore 限流）
     */
    private fun mmapCopy(source: File, target: File): Long {
        if (!mmapLimiter.tryAcquire()) {
            // 超过并发限制，降级到自适应缓冲区流
            return adaptiveBufferCopy(source, target)
        }
        return try {
            RandomAccessFile(source, "r").use { srcRaf ->
                RandomAccessFile(target, "rw").use { dstRaf ->
                    val srcCh = srcRaf.channel
                    val dstCh = dstRaf.channel
                    val size = srcCh.size()
                    dstRaf.setLength(size)

                    val srcBuf = srcCh.map(FileChannel.MapMode.READ_ONLY, 0, size)
                    val dstBuf = dstCh.map(FileChannel.MapMode.READ_WRITE, 0, size)
                    dstBuf.put(srcBuf)
                    dstBuf.force()
                    size
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "mmap 复制失败，降级至自适应流: ${e.message}")
            adaptiveBufferCopy(source, target)
        } finally {
            mmapLimiter.release()
        }
    }

    /**
     * 自适应缓冲区流复制（配合 AdaptiveBufferManager 动态调优）
     */
    private fun adaptiveBufferCopy(source: File, target: File): Long {
        val buffer = bufferManager.acquireBuffer()
        var totalBytes = 0L
        val startNano = System.nanoTime()

        try {
            FileInputStream(source).use { fis ->
                FileOutputStream(target).use { fos ->
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        fos.write(buffer, 0, read)
                        totalBytes += read
                    }
                    fos.flush()
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "自适应流复制失败: ${source.name}: ${e.message}")
            return 0L
        } finally {
            val durationNanos = System.nanoTime() - startNano
            bufferManager.recordIoDuration(totalBytes, durationNanos)
            bufferManager.releaseBuffer(buffer)
        }

        return totalBytes
    }

    // ==================== 增量检测 ====================

    /**
     * 判断文件是否需要更新
     * 优先使用修改时间 + 大小组合判定，快速路径下无需读文件内容
     */
    fun needsUpdate(source: File, target: File): Boolean {
        if (!target.exists()) return true
        if (source.length() != target.length()) return true
        // 修改时间一致 → 大概率相同
        if (source.lastModified() == target.lastModified()) return false

        val size = source.length()
        return when {
            // 小文件全量 MD5
            size < FULL_MD5_THRESHOLD -> !FileHasher.areFilesEqual(source, target)
            // 大文件三段抽样哈希
            else -> !FileHasher.areFilesEqualWithSampling(source, target)
        }
    }

    // ==================== 抽样哈希 ====================

    /**
     * 三段抽样指纹：头/中/尾各 64KB + 文件大小 → MD5 特征值
     * 用于 >= 1MB 文件的快速预检
     */
    fun generateSamplingFingerprint(file: File): String {
        if (!file.exists()) return ""
        val length = file.length()
        if (length < SAMPLING_THRESHOLD) {
            return FileHasher.calculateMD5(file) ?: ""
        }

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val segmentSize = 64 * 1024
                val buffer = ByteArray(segmentSize * 3)

                // 头部
                raf.seek(0)
                val headRead = raf.read(buffer, 0, segmentSize).coerceAtLeast(0)

                // 中部
                val midPos = (length / 2 - segmentSize / 2).coerceAtLeast(0)
                raf.seek(midPos)
                val midRead = raf.read(buffer, headRead, segmentSize).coerceAtLeast(0)

                // 尾部
                val tailPos = (length - segmentSize).coerceAtLeast(0)
                raf.seek(tailPos)
                val tailRead = raf.read(buffer, headRead + midRead, segmentSize).coerceAtLeast(0)

                val totalSampleBytes = headRead + midRead + tailRead
                val md5 = MessageDigest.getInstance("MD5")
                md5.update(buffer, 0, totalSampleBytes)
                md5.update(length.toString().toByteArray())
                md5.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            FileHasher.calculateMD5(file) ?: ""
        }
    }

    // ==================== 并发处理 ====================

    /**
     * 并行处理文件列表
     * @param items 要处理的项
     * @param concurrency 最大并发数（默认自动）
     * @param action 处理逻辑
     * @param progressCallback 进度回调 (已完成, 总数, 当前项名称)
     */
    suspend fun <T> parallelProcess(
        items: List<T>,
        concurrency: Int = defaultConcurrency,
        action: suspend (T) -> Boolean,
        progressCallback: ((Int, Int, String) -> Unit)? = null,
    ): ProcessResult = coroutineScope {
        val total = items.size
        val successCount = AtomicInteger(0)
        val failedCount = AtomicInteger(0)
        val semaphore = Semaphore(concurrency)

        val deferreds = items.map { item ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val success = action(item)
                    if (success) successCount.incrementAndGet() else failedCount.incrementAndGet()

                    val itemName = when (item) {
                        is File -> item.name
                        is String -> item
                        else -> item.toString()
                    }
                    progressCallback?.invoke(successCount.get() + failedCount.get(), total, itemName)
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

    // ==================== 缓冲区池 ====================

    private fun acquireBuffer(): ByteArray {
        return bufferPool.poll() ?: ByteArray(bufferManager.getCurrentBufferSize())
    }

    private fun releaseBuffer(buffer: ByteArray) {
        if (bufferPool.size < POOL_SIZE) {
            bufferPool.offer(buffer)
        }
    }

    // ==================== 结果类型 ====================

    data class ProcessResult(
        val success: Boolean,
        val successCount: Int,
        val failedCount: Int,
        val total: Int,
    )
}