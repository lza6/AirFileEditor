package com.example.tfgwj.performance

import java.util.concurrent.atomic.AtomicInteger

/**
 * 动态自适应缓冲区管理器 (V14 性能引擎 2.0)
 *
 * 根据实时 I/O 延迟与吞吐动态调整缓冲区大小：
 * - 延迟高 (平均单字节耗时 > 1µs / 密集小文件) -> 扩容缓冲区至 1.5 倍 (上限 8MB)
 * - 延迟极低 (平均单字节耗时 < 0.1µs / 顺序高速) -> 平滑收缩 (下限 16KB)
 * - 64 次写入样本触发一次防抖调优
 */
interface AdaptiveBufferManager {
    fun acquireBuffer(): ByteArray
    fun releaseBuffer(buffer: ByteArray)
    fun recordIoDuration(bytesTransferred: Long, durationNanos: Long)
    fun getCurrentBufferSize(): Int
    fun reset(initialSize: Int = 512 * 1024)

    /** V18：显式设置当前缓冲区大小（供内存水位联动，越界会被 clamp 到 [minSize,maxSize]） */
    fun setBufferSize(size: Int)
}

class AdaptiveBufferManagerImpl(
    private val minSize: Int = 16 * 1024, // 16 KB
    private val maxSize: Int = 8 * 1024 * 1024, // 8 MB
    initialSize: Int = 512 * 1024 // 512 KB
) : AdaptiveBufferManager {
    private val currentSize = AtomicInteger(initialSize.coerceIn(minSize, maxSize))
    private var sampleCount = 0
    private var accumulatedBytes = 0L
    private var accumulatedNanos = 0L

    override fun acquireBuffer(): ByteArray {
        return ByteArray(currentSize.get())
    }

    override fun releaseBuffer(buffer: ByteArray) {
        // 可与对象池集成，此处留作扩展
    }

    @Synchronized
    override fun recordIoDuration(bytesTransferred: Long, durationNanos: Long) {
        if (bytesTransferred <= 0 || durationNanos <= 0) return
        accumulatedBytes += bytesTransferred
        accumulatedNanos += durationNanos
        sampleCount++

        if (sampleCount >= 64) {
            val avgNanosPerByte = accumulatedNanos.toDouble() / accumulatedBytes
            adjustSize(avgNanosPerByte)
            sampleCount = 0
            accumulatedBytes = 0L
            accumulatedNanos = 0L
        }
    }

    private fun adjustSize(avgNanosPerByte: Double) {
        val current = currentSize.get()
        if (avgNanosPerByte > 1000.0) { // > 1µs/byte，表明 I/O 存在瓶颈，扩大缓冲区减少系统调用
            val next = (current * 1.5).toInt().coerceAtMost(maxSize)
            currentSize.set(next)
        } else if (avgNanosPerByte < 100.0) { // < 100ns/byte，I/O 顺畅，适当回收内存
            val next = (current / 1.2).toInt().coerceAtLeast(minSize)
            currentSize.set(next)
        }
    }

    override fun getCurrentBufferSize(): Int = currentSize.get()

    @Synchronized
    override fun setBufferSize(size: Int) {
        currentSize.set(size.coerceIn(minSize, maxSize))
    }

    @Synchronized
    override fun reset(initialSize: Int) {
        currentSize.set(initialSize.coerceIn(minSize, maxSize))
        sampleCount = 0
        accumulatedBytes = 0L
        accumulatedNanos = 0L
    }
}
