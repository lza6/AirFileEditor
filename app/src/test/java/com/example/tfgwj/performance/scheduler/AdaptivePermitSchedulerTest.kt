package com.example.tfgwj.performance.scheduler

import android.content.Context
import android.util.Log
import com.example.tfgwj.performance.PerformanceMonitor
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdaptivePermitSchedulerTest {
    private lateinit var context: Context
    private val basePermits = 8
    private val minPermits = 2
    private val maxPermits = 16

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0

        mockkObject(PerformanceMonitor)
    }

    @Test
    fun `scheduler adjusts permits based on IO speed`() =
        runTest {
            // Mock high IO speed, low memory usage
            every { PerformanceMonitor.getIOStats() } returns
                PerformanceMonitor.IOStats(
                    totalBytesCopied = 100 * 1024 * 1024,
                    totalCopyTimeMs = 1000,
                    totalFilesCopied = 100,
                    avgSpeedMBps = 100.0, // > 50 MB/s
                    mmapFallbackRate = 0.0,
                    incrementalHitRate = 0.0,
                )

            val scheduler = AdaptivePermitScheduler(context, basePermits, minPermits, maxPermits)
            scheduler.memoryUsageProvider = { 0.3 } // 确保走高吞吐提并发分支
            var updatedPermits = basePermits

            scheduler.start {
                updatedPermits = it
            }

            // Trigger manual update
            val updateMethod = scheduler.javaClass.getDeclaredMethod("updatePermits")
            updateMethod.isAccessible = true
            updateMethod.invoke(scheduler)

            // Should increase permits (base 8 * 1.5 = 12)
            Thread.sleep(50)
            assertEquals(12, updatedPermits)

            scheduler.stop()
        }

    @Test
    fun `scheduler reduces permits on high mmap fallback`() =
        runTest {
            every { PerformanceMonitor.getIOStats() } returns
                PerformanceMonitor.IOStats(
                    totalBytesCopied = 10 * 1024 * 1024,
                    totalCopyTimeMs = 1000,
                    totalFilesCopied = 100,
                    avgSpeedMBps = 10.0,
                    mmapFallbackRate = 60.0, // > 50%
                    incrementalHitRate = 0.0,
                )

            val scheduler = AdaptivePermitScheduler(context, basePermits, minPermits, maxPermits)
            scheduler.memoryUsageProvider = { 0.3 } // 避免触发内存压力分支，专测 fallback 分支
            var updatedPermits = basePermits

            scheduler.start {
                updatedPermits = it
            }

            val updateMethod = scheduler.javaClass.getDeclaredMethod("updatePermits")
            updateMethod.isAccessible = true
            updateMethod.invoke(scheduler)

            // 策略 C (mmap fallback > 50%) -> 8 * 0.8 = 6.4 -> 6
            Thread.sleep(50)
            assertEquals(6, updatedPermits)

            scheduler.stop()
        }
}
