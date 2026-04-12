package com.example.tfgwj.utils

import android.util.Log
import com.example.tfgwj.performance.MetricCollector
import com.example.tfgwj.performance.PerformanceMonitor
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class IoOptimizerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.v(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        mockkObject(PerformanceMonitor)
        every { PerformanceMonitor.recordIOCopy(any<Long>(), any<Long>(), any<Boolean>(), any<Boolean>()) } returns Unit
        every { PerformanceMonitor.recordIOWriteLatency(any<Long>(), any<Long>()) } returns Unit

        mockkObject(MetricCollector)
        every { MetricCollector.recordIO(any<String>(), any<Double>(), any<String>(), any<Map<String, String>>()) } returns Unit
    }

    @Test
    fun `fastCopy copies file correctly`() {
        val source = tempFolder.newFile("src_${System.nanoTime()}.bin")
        val target = File(tempFolder.root, "dest_${System.nanoTime()}.bin")
        val content = "hello nio mmap copy".toByteArray()
        source.writeBytes(content)

        val result = IoOptimizer.fastCopy(source, target)

        assertTrue(result)
        assertTrue(target.exists())
        assertArrayEquals(content, target.readBytes())
        assertEquals(source.lastModified(), target.lastModified())
    }

    @Test
    fun `needsUpdate returns true when target missing`() {
        val source = tempFolder.newFile("src_new_${System.nanoTime()}.bin")
        val target = File(tempFolder.root, "not_exist_${System.nanoTime()}.bin")

        assertTrue(IoOptimizer.needsUpdate(source, target))
    }

    @Test
    fun `needsUpdate returns false when size and time match`() {
        val source = tempFolder.newFile("src_match_${System.nanoTime()}.bin")
        val target = tempFolder.newFile("dest_match_${System.nanoTime()}.bin")
        val content = "match".toByteArray()
        source.writeBytes(content)
        target.writeBytes(content)

        val time = System.currentTimeMillis() / 1000 * 1000 // Strip millis to avoid precision issues on some FS
        source.setLastModified(time)
        target.setLastModified(time)

        assertFalse(IoOptimizer.needsUpdate(source, target))
    }

    @Test
    fun `parallelProcess executes all actions`() = runTest {
        val items = listOf("task1", "task2", "task3")
        val completed = mutableListOf<String>()

        val result = IoOptimizer.parallelProcess(items, action = { item: String ->
            completed.add(item)
            true
        })

        assertTrue(result.success)
        assertEquals(3, result.successCount)
        assertEquals(3, completed.size)
        assertTrue(completed.containsAll(items))
    }

    @Test
    fun `buffer pool reuses buffers`() {
        val buffer = IoOptimizer.acquireBuffer()
        assertNotNull(buffer)
        IoOptimizer.releaseBuffer(buffer)

        val buffer2 = IoOptimizer.acquireBuffer()
        assertSame(buffer, buffer2)
    }
}
