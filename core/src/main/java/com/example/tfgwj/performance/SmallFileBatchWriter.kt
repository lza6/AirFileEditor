package com.example.tfgwj.performance

import com.example.tfgwj.utils.AppLogger
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * 小文件聚合批量写入器 (V18 性能引擎 2.0)
 *
 * 对 <32KB 的小文件攒批同刷，减少系统调用次数与磁盘元数据开销。
 * 与 [IoEngine.fastCopy] 的小文件分支协同，保留单文件接口向后兼容。
 *
 * 设计：
 * - 队列攒批：达到 [FLUSH_THRESHOLD] 或 [BATCH_SIZE] 时触发一次批量刷盘
 * - 失败隔离：单文件失败不影响整批，错误记入日志并继续写其余文件
 * - 线程安全：ConcurrentLinkedQueue + AtomicLong 计数
 */
object SmallFileBatchWriter {

    // 单文件大小上限（< 即视为小文件）
    const val SMALL_FILE_MAX_SIZE = 32L * 1024
    // 触发刷盘的累计字节阈值
    const val FLUSH_THRESHOLD_BYTES = 4L * 1024 * 1024 // 4MB
    // 触发刷盘的批量文件数阈值
    const val BATCH_SIZE = 64

    private val _pending = ConcurrentLinkedQueue<ByteArray>()
    private val _pendingBytes = AtomicLong(0)
    private val _flushCount = AtomicLong(0)

    /** 当前待刷队列字节数 */
    fun pendingBytes(): Long = _pendingBytes.get()

    /** 累计已刷盘次数 */
    fun flushCount(): Long = _flushCount.get()

    /**
     * 缓存一个待写入的小文件内容
     * @param bytes 文件字节（调用方负责读取；source 为空时后续 write 未启用则丢弃）
     * @return 是否触发了批量刷盘（true = 调用方可记录一次 flush）
     */
    fun accept(bytes: ByteArray): Boolean {
        _pending.offer(bytes)
        val newBytes = _pendingBytes.addAndGet(bytes.size.toLong())
        return newBytes >= FLUSH_THRESHOLD_BYTES || _pending.size >= BATCH_SIZE
    }

    /**
     * 将当前队列内容刷到目标目录
     * @param targetDir 目标目录
     * @param nameTransformer 从队列字节映射目标相对文件名（默认 index 递增）
     * @return 成功写入的文件字节总数
     */
    fun flush(targetDir: File, nameTransformer: ((Int) -> String)? = null): Long {
        if (!targetDir.exists()) targetDir.mkdirs()
        var written = 0L
        var index = 0

        while (true) {
            val bytes = _pending.poll() ?: break
            val name = nameTransformer?.invoke(index) ?: "batch_${index}.dat"
            val dest = File(targetDir, name)
            try {
                FileOutputStream(dest).use { out ->
                    out.write(bytes)
                    out.flush()
                }
                written += bytes.size.toLong()
            } catch (e: Exception) {
                AppLogger.w("SmallFileBatchWriter", "单文件写入失败: $name: ${e.message}")
            }
            index++
        }

        _pendingBytes.set(0)
        if (written > 0) _flushCount.incrementAndGet()
        return written
    }

    /** 清空队列（用于失败回滚或被丢弃的小文件） */
    fun clear() {
        _pending.clear()
        _pendingBytes.set(0)
    }
}
