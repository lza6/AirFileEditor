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
 * 统一高性能复制引擎（融合 IoOptimizer 与旧 HighPerformanceIoEngine 的核心能力），
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

    // V18 分块 mmap：单块映射上限，防止大文件一次性映射导致 OOM / TransactionTooLarge
    const val MMAP_CHUNK_SIZE = 64L * 1024 * 1024 // 64MB/块
    const val MMAP_MAX_FILE_SIZE = 2L * 1024 * 1024 * 1024 // 2GB 上限，超限走流式写

    // mmap 并发限流
    private val mmapLimiter = Semaphore(16)

    // V18 内存水位：由外部线程安全地刷新（setMemoryPressure），fastCopy/mmapCopy 读取
    @Volatile
    private var memoryPressure: MemoryPressureLevel = MemoryPressureLevel.LOW

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

        // V18 内存水位：高压力下优先小文件直写，避免 mmap 映射过多虚存
        if (memoryPressure == MemoryPressureLevel.HIGH && source.length() >= MMAP_THRESHOLD) {
            return adaptiveBufferCopy(source, target)
        }

        val size = source.length()
        return when {
            // 策略 1: 极小文件 — 直接缓冲区复制
            size in 1..SMALL_FILE_THRESHOLD -> smallFileCopy(source, target)
            // 策略 2: 大文件 — 分块 mmap 零拷贝（防 OOM）
            size >= MMAP_THRESHOLD -> mmapCopy(source, target)
            // 策略 3: 常规文件 — 自适应缓冲区流
            else -> adaptiveBufferCopy(source, target)
        }
    }

    /**
     * 刷新内存水位（由 Worker/Manager 在任务前调用，注入当前 ActivityManager 快照）
     * @param activityManager Android ActivityManager（读取失败按 LOW 处理，避免误降级）
     */
    fun refreshMemoryPressure(activityManager: android.app.ActivityManager?) {
        if (activityManager == null) {
            memoryPressure = MemoryPressureLevel.LOW
            return
        }
        val snapshot = readMemorySnapshot(activityManager)
        val level = MemoryPressureGuard.assess(snapshot)
        memoryPressure = level
        // 内存水位联动：高压力收缩缓冲上限、中压力适当收缩
        when (level) {
            MemoryPressureLevel.HIGH -> bufferManager.setBufferSize(16 * 1024)
            MemoryPressureLevel.MEDIUM -> bufferManager.setBufferSize(256 * 1024)
            MemoryPressureLevel.LOW -> bufferManager.reset()
        }
    }

    /** 当前内存压力等级（测试/诊断用） */
    fun currentMemoryPressure(): MemoryPressureLevel = memoryPressure

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
     * mmap 零拷贝（V18：分块映射，防大文件 OOM）
     *
     * 对 >=16MB 文件按 [MMAP_CHUNK_SIZE] 分块映射，块内 put 后 sync，
     * 避免一次性 map(0, size) 在低内存设备上造成 OOM。文件超过 [MMAP_MAX_FILE_SIZE]
     * 时降级到流式写（[channelCopy]）。任何失败均降级到自适应缓冲区流。
     */
    private fun mmapCopy(source: File, target: File): Long {
        val size = source.length()

        // 超限文件走流式写（大文件 mmap 收益有限且内存风险高）
        if (size > MMAP_MAX_FILE_SIZE) {
            return channelCopy(source, target)
        }

        if (!mmapLimiter.tryAcquire()) {
            // 超过并发限制，降级到自适应缓冲区流
            return adaptiveBufferCopy(source, target)
        }
        return try {
            RandomAccessFile(source, "r").use { srcRaf ->
                RandomAccessFile(target, "rw").use { dstRaf ->
                    val srcCh = srcRaf.channel
                    val dstCh = dstRaf.channel
                    val fileSize = srcCh.size()
                    dstRaf.setLength(fileSize)

                    // 逐块映射：每次读取并写入 [MMAP_CHUNK_SIZE]，块间 sync 强制落盘
                    var offset = 0L
                    while (offset < fileSize) {
                        val chunkLen = minOf(MMAP_CHUNK_SIZE, fileSize - offset)
                        val srcBuf = srcCh.map(FileChannel.MapMode.READ_ONLY, offset, chunkLen)
                        val dstBuf = dstCh.map(FileChannel.MapMode.READ_WRITE, offset, chunkLen)
                        dstBuf.put(srcBuf)
                        dstBuf.force()
                        offset += chunkLen
                    }
                    fileSize
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "分块 mmap 复制失败，降级至自适应流: ${e.message}")
            adaptiveBufferCopy(source, target)
        } finally {
            mmapLimiter.release()
        }
    }

    /**
     * 大文件流式写（超过 mmap 上限时的兜底）
     * 使用 Channel 绕过分页缓存，避免一次性载入内存。
     *
     * 安全保证：
     * - 读侧 `read(buffer) > 0` 显式排除理论上的 0 返回，杜绝无进展自旋
     * - 写侧 drain 循环：write 返回 < remaining() 时继续写，防止部分写入被 clear() 丢弃
     * - 任一环节失败：删除半成品目标文件并返回 0（与 fastCopy 契约"失败返回 0"一致），
     *   避免残留截断文件被调用方误判成功
     */
    private fun channelCopy(source: File, target: File): Long {
        val buffer = java.nio.ByteBuffer.allocateDirect(1024 * 1024)
        return try {
            FileInputStream(source).use { ins ->
                FileOutputStream(target).use { out ->
                    val srcCh = ins.channel
                    val dstCh = out.channel
                    var total = 0L
                    while (srcCh.read(buffer) > 0) {
                        buffer.flip()
                        // drain 写入：阻塞通道理论上一次写满，此处防御部分写入
                        while (buffer.hasRemaining()) {
                            val written = dstCh.write(buffer)
                            if (written == 0) {
                                throw java.io.IOException("channelCopy 写入无进展（目标分区可能已满）")
                            }
                            total += written
                        }
                        buffer.clear()
                    }
                    dstCh.force(false)
                    total
                }
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "流式写失败: ${source.name}: ${e.message}")
            runCatching { if (target.exists()) target.delete() }
            0L
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

    /**
     * 从 Android ActivityManager 读取当前内存快照（外部调用方注入，便于单测与低耦合）
     * @return [MemorySnapshot]，读取失败时返回 available=total=0（判级为 LOW）
     */
    fun readMemorySnapshot(activityManager: android.app.ActivityManager): MemorySnapshot {
        return try {
            val info = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(info)
            MemorySnapshot(availMem = info.availMem, totalMem = info.totalMem)
        } catch (e: Exception) {
            MemorySnapshot(availMem = 0L, totalMem = 0L)
        }
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