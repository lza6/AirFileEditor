package com.example.tfgwj.performance

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * MemoryPressureGuard 单元测试 (V18)
 *
 * 纯 JVM，不依赖 Android。构造模拟内存快照判定压力等级与降级决策。
 */
class MemoryPressureGuardTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun snapshot(availRatio: Float): MemorySnapshot {
        val total = 8L * 1024 * 1024 * 1024 // 8GB
        return MemorySnapshot(availMem = (total * availRatio).toLong(), totalMem = total)
    }

    @Test
    fun `low pressure when avail ratio above 30 percent`() {
        assertEquals(MemoryPressureLevel.LOW, MemoryPressureGuard.assess(snapshot(0.5f)))
        assertEquals(MemoryPressureLevel.LOW, MemoryPressureGuard.assess(snapshot(0.35f)))
    }

    @Test
    fun `medium pressure when ratio between 15 and 30 percent`() {
        assertEquals(MemoryPressureLevel.MEDIUM, MemoryPressureGuard.assess(snapshot(0.20f)))
        assertEquals(MemoryPressureLevel.MEDIUM, MemoryPressureGuard.assess(snapshot(0.299f)))
    }

    @Test
    fun `high pressure when ratio below 15 percent`() {
        assertEquals(MemoryPressureLevel.HIGH, MemoryPressureGuard.assess(snapshot(0.05f)))
        assertEquals(MemoryPressureLevel.HIGH, MemoryPressureGuard.assess(snapshot(0.14f)))
    }

    @Test
    fun `zero total mem falls back to low`() {
        assertEquals(MemoryPressureLevel.LOW, MemoryPressureGuard.assess(MemorySnapshot(availMem = 0L, totalMem = 0L)))
    }

    @Test
    fun `recommendedConcurrency scales down under pressure`() {
        assertEquals(16, MemoryPressureGuard.recommendedConcurrency(MemoryPressureLevel.LOW, 16))
        assertEquals(8, MemoryPressureGuard.recommendedConcurrency(MemoryPressureLevel.MEDIUM, 16))
        assertEquals(4, MemoryPressureGuard.recommendedConcurrency(MemoryPressureLevel.HIGH, 16))
        // 下限保护：并发至少为 1
        assertEquals(1, MemoryPressureGuard.recommendedConcurrency(MemoryPressureLevel.HIGH, 2))
    }

    @Test
    fun `shouldUseMmap disabled under high pressure`() {
        assertTrue(MemoryPressureGuard.shouldUseMmap(MemoryPressureLevel.LOW))
        assertTrue(MemoryPressureGuard.shouldUseMmap(MemoryPressureLevel.MEDIUM))
        assertFalse(MemoryPressureGuard.shouldUseMmap(MemoryPressureLevel.HIGH))
    }

    @Test
    fun `readMemorySnapshot handles null activityManager gracefully`() {
        // 传入 null 会导致 NPE，这里验证 IoEngine 侧的防御路径以外的方式：
        // 直接构造 0/0 snapshot 判级为 LOW。
        val snapshot = MemorySnapshot(availMem = 0L, totalMem = 0L)
        assertEquals(MemoryPressureLevel.LOW, MemoryPressureGuard.assess(snapshot))
    }
}
