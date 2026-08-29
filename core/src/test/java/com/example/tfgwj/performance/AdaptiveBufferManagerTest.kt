package com.example.tfgwj.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AdaptiveBufferManagerTest {

    private lateinit var manager: AdaptiveBufferManager

    @Before
    fun setup() {
        manager = AdaptiveBufferManagerImpl(
            minSize = 16 * 1024,
            maxSize = 8 * 1024 * 1024,
            initialSize = 512 * 1024
        )
    }

    @Test
    fun `initial buffer size matches configuration`() {
        assertEquals(512 * 1024, manager.getCurrentBufferSize())
        val buffer = manager.acquireBuffer()
        assertEquals(512 * 1024, buffer.size)
    }

    @Test
    fun `high latency triggers buffer expansion`() {
        val initialSize = manager.getCurrentBufferSize()
        // 模拟 64 次高延迟 I/O (例如每个字节耗时 2000ns)
        repeat(64) {
            manager.recordIoDuration(bytesTransferred = 1024, durationNanos = 2_000_000L)
        }
        val expandedSize = manager.getCurrentBufferSize()
        assertTrue(expandedSize > initialSize)
        assertEquals((initialSize * 1.5).toInt(), expandedSize)
    }

    @Test
    fun `low latency triggers buffer shrink`() {
        val initialSize = manager.getCurrentBufferSize()
        // 模拟 64 次超低延迟 I/O (例如每个字节耗时 10ns)
        repeat(64) {
            manager.recordIoDuration(bytesTransferred = 1024, durationNanos = 10_000L)
        }
        val shrunkSize = manager.getCurrentBufferSize()
        assertTrue(shrunkSize < initialSize)
        assertEquals((initialSize / 1.2).toInt(), shrunkSize)
    }

    @Test
    fun `buffer size stays within bounds`() {
        // 极限扩容测试
        repeat(1000) {
            manager.recordIoDuration(bytesTransferred = 1024, durationNanos = 10_000_000L)
        }
        assertTrue(manager.getCurrentBufferSize() <= 8 * 1024 * 1024)

        // 极限收缩测试
        repeat(1000) {
            manager.recordIoDuration(bytesTransferred = 1024, durationNanos = 10L)
        }
        assertTrue(manager.getCurrentBufferSize() >= 16 * 1024)
    }

    // ==================== 追加：自适应调优细节测试 ====================

    @Test
    fun `size stays unchanged before 64 samples reached`() {
        // 不满 64 次采样不触发调优：buffer 大小保持不变（即使单样本延迟极高）
        val initialSize = manager.getCurrentBufferSize()
        repeat(63) {
            manager.recordIoDuration(bytesTransferred = 1024, durationNanos = 10_000_000L)
        }
        assertEquals(initialSize, manager.getCurrentBufferSize())
        // 第 64 次触发防抖调优
        manager.recordIoDuration(bytesTransferred = 1024, durationNanos = 10_000_000L)
        assertTrue(manager.getCurrentBufferSize() > initialSize)
    }

    @Test
    fun `sample counters reset after resize so samples accumulate again`() {
        // 第一次 64 采样触发扩容后，内部采样窗口清零
        val initialSize = manager.getCurrentBufferSize()
        repeat(64) {
            manager.recordIoDuration(bytesTransferred = 1024, durationNanos = 2_000_000L)
        }
        assertEquals((initialSize * 1.5).toInt(), manager.getCurrentBufferSize())

        // 若计数器未清零，额外的 64 次会继续放大；清零则需再等 64 次采样
        repeat(64) {
            manager.recordIoDuration(bytesTransferred = 1024, durationNanos = 2_000_000L)
        }
        val afterSecondWindow = manager.getCurrentBufferSize()
        assertEquals((initialSize * 1.5 * 1.5).toInt(), afterSecondWindow)
    }

    @Test
    fun `shrink never goes below minimum size`() {
        // 从最小尺寸出发，持续超低延迟采样，缓冲区不得收缩到 16KB 以下
        manager.reset(16 * 1024)
        repeat(1024) {
            manager.recordIoDuration(bytesTransferred = 1024, durationNanos = 10L)
        }
        assertEquals(16 * 1024, manager.getCurrentBufferSize())
        assertTrue(manager.getCurrentBufferSize() >= 16 * 1024)
    }

    @Test
    fun `expansion never exceeds maximum size`() {
        // 从最大尺寸出发，持续高延迟采样，缓冲区不得扩容到 8MB 以上
        manager.reset(8 * 1024 * 1024)
        repeat(1024) {
            manager.recordIoDuration(bytesTransferred = 1024, durationNanos = 10_000_000L)
        }
        assertEquals(8 * 1024 * 1024, manager.getCurrentBufferSize())
        assertTrue(manager.getCurrentBufferSize() <= 8 * 1024 * 1024)
    }

    @Test
    fun `reset restores initial size and clears samples`() {
        // 先调大再调小，reset 应恢复构造时的初始尺寸
        repeat(64) {
            manager.recordIoDuration(bytesTransferred = 1024, durationNanos = 2_000_000L)
        }
        assertTrue(manager.getCurrentBufferSize() > 512 * 1024)

        manager.reset(512 * 1024)
        assertEquals(512 * 1024, manager.getCurrentBufferSize())
        assertEquals(512 * 1024, manager.acquireBuffer().size)
        // reset 后采样清零：再记录一次不触发调优
        manager.recordIoDuration(bytesTransferred = 1024, durationNanos = 10_000_000L)
        assertEquals(512 * 1024, manager.getCurrentBufferSize())
    }

    @Test
    fun `zero byte or negative transfer does not trigger tuning`() {
        // 0 字节 / 负字节 / 0 耗时等非法样本直接忽略，不累积也不触发调优
        repeat(100) {
            manager.recordIoDuration(bytesTransferred = 0L, durationNanos = 10_000_000L)
            manager.recordIoDuration(bytesTransferred = -1L, durationNanos = 10_000_000L)
            manager.recordIoDuration(bytesTransferred = 1024L, durationNanos = 0L)
            manager.recordIoDuration(bytesTransferred = 1024L, durationNanos = -1L)
        }
        assertEquals(512 * 1024, manager.getCurrentBufferSize())
    }

    @Test
    fun `mixed latency samples ultimately converge to stable max size`() {
        // 混合高/低延迟样本，反复调优后仍收敛在 [16KB, 8MB] 边界内
        var seed = 0L
        repeat(2048) {
            seed += 11
            val high = (seed % 3L == 0L)
            if (high) {
                manager.recordIoDuration(bytesTransferred = 2048, durationNanos = 20_000_000L)
            } else {
                manager.recordIoDuration(bytesTransferred = 2048, durationNanos = 50L)
            }
        }
        val finalSize = manager.getCurrentBufferSize()
        assertTrue(finalSize in 16 * 1024..8 * 1024 * 1024)
    }

    @Test
    fun `releaseBuffer returns quietly`() {
        // releaseBuffer 是扩展点：调用不得抛异常
        val buffer = manager.acquireBuffer()
        manager.releaseBuffer(buffer)
        manager.releaseBuffer(ByteArray(1024))
        // 释放后仍可正常获取新缓冲
        assertEquals(512 * 1024, manager.acquireBuffer().size)
    }

    @Test
    fun `concurrent acquireBuffer does not throw`() {
        // 8 线程并发获取缓冲 + 记录耗时，验证线程安全（不会抛异常/死锁）
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..8).map {
                pool.submit(Callable {
                    repeat(200) { idx ->
                        val buf = manager.acquireBuffer()
                        manager.releaseBuffer(buf)
                        if (idx % 8 == 0) {
                            manager.recordIoDuration(bytesTransferred = 1024, durationNanos = 100_000L)
                        }
                    }
                    true
                })
            }
            futures.forEach { assertTrue(it.get(30, TimeUnit.SECONDS)) }
            // 并发记录后缓冲区仍在合理范围内
            assertTrue(manager.getCurrentBufferSize() in 16 * 1024..8 * 1024 * 1024)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `acquired buffer size always matches current size`() {
        // 无论调优如何变化，acquireBuffer 返回的数组长度始终等于 getCurrentBufferSize
        repeat(64) {
            manager.recordIoDuration(bytesTransferred = 1024, durationNanos = 2_000_000L)
        }
        assertEquals(manager.getCurrentBufferSize(), manager.acquireBuffer().size)

        repeat(64) {
            manager.recordIoDuration(bytesTransferred = 1024, durationNanos = 10_000L)
        }
        assertEquals(manager.getCurrentBufferSize(), manager.acquireBuffer().size)
    }

    @Test
    fun `tsuka config reproduces same growth pattern`() {
        // 使用与 setup 相同的 16KB~8MB / 512KB 配置，验证默认构造器与显式构造一致
        val defaultManager = AdaptiveBufferManagerImpl()
        repeat(64) {
            defaultManager.recordIoDuration(bytesTransferred = 1024, durationNanos = 2_000_000L)
        }
        assertNotEquals(512 * 1024, defaultManager.getCurrentBufferSize())
        assertTrue(defaultManager.getCurrentBufferSize() <= 8 * 1024 * 1024)
    }

    // ==================== V18：显式 setBufferSize（内存水位联动 clamp） ====================

    @Test
    fun `setBufferSize clamps above max`() {
        manager.setBufferSize(100 * 1024 * 1024)
        assertEquals(8 * 1024 * 1024, manager.getCurrentBufferSize())
    }

    @Test
    fun `setBufferSize clamps below min`() {
        manager.setBufferSize(1)
        assertEquals(16 * 1024, manager.getCurrentBufferSize())
    }

    @Test
    fun `setBufferSize respects valid midrange value`() {
        manager.setBufferSize(1024 * 1024)
        assertEquals(1024 * 1024, manager.getCurrentBufferSize())
    }

    @Test
    fun `setBufferSize then acquire returns clamped size`() {
        manager.setBufferSize(4096)
        assertEquals(16 * 1024, manager.acquireBuffer().size)
        manager.setBufferSize(16 * 1024)
        assertEquals(16 * 1024, manager.acquireBuffer().size)
    }
}