package com.example.tfgwj.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
}
