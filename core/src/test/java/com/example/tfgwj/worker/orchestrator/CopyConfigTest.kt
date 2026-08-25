package com.example.tfgwj.worker.orchestrator

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CopyConfigTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `default config contains reasonable concurrency`() {
        val config = CopyConfig.getDefault(context)

        // 动态并发：CPU 核心数 × 2，钳制在 [4, 32]
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val expectedMaxConcurrent = (cpuCores * 2).coerceIn(4, 32)
        assertEquals(expectedMaxConcurrent, config.maxConcurrentTasks)
        assertTrue(config.maxConcurrentTasks in 4..32)

        // Root/Shizuku 保守并发
        assertEquals(2, config.rootConcurrentPermits)
        assertEquals(2, config.shizukuConcurrentPermits)
        assertEquals(expectedMaxConcurrent, config.nativeConcurrentPermits)
    }

    @Test
    fun `default config is not null and carries all fields`() {
        val config = CopyConfig.getDefault(context)

        assertNotNull(config)
        // 所有进度阶段字段均被填充（0-100 之间）
        assertTrue(config.progressPhaseReplacingMax in 0..100)
        assertTrue(config.progressPhaseVerifyingStart in 0..100)
        assertEquals(100, config.progressPhaseVerifyingMax)
        // 起始进度不得高于上限
        assertTrue(config.progressPhaseVerifyingStart <= config.progressPhaseVerifyingMax)
    }

    @Test
    fun `default config throttle intervals are reasonable`() {
        val config = CopyConfig.getDefault(context)

        // WorkManager 写入频率 1s
        assertEquals(1000L, config.wmUpdateIntervalMs)
        // UI 刷新 ~30FPS
        assertEquals(32L, config.uiUpdateIntervalMs)
        // 初始进度阈值 / 批次大小合理
        assertEquals(5, config.initialProgressThreshold)
        assertEquals(32, config.fileBatchSize)
        assertTrue(config.shellCommandTimeoutMs > 0)
    }

    @Test
    fun `config can be modified via data class copy`() {
        val original = CopyConfig.getDefault(context)
        val originalWmInterval = original.wmUpdateIntervalMs
        val originalBatch = original.fileBatchSize

        // 通过 data class copy 修改字段，原配置保持不可变
        val modified = original.copy(wmUpdateIntervalMs = 250, fileBatchSize = 16)

        assertEquals(250L, modified.wmUpdateIntervalMs)
        assertEquals(16, modified.fileBatchSize)
        // 未修改的字段被保留
        assertEquals(original.maxConcurrentTasks, modified.maxConcurrentTasks)
        // 原配置不被影响
        assertEquals(originalWmInterval, original.wmUpdateIntervalMs)
        assertEquals(originalBatch, original.fileBatchSize)
        assertNotEquals(original, modified)
    }

    @Test
    fun `test config is a distinct fast-throttle configuration`() {
        val testConfig = CopyConfig.getTestConfig()
        val defaultConfig = CopyConfig.getDefault(context)

        // 测试配置节流更快，便于观察
        assertTrue(testConfig.uiUpdateIntervalMs < defaultConfig.uiUpdateIntervalMs)
        assertTrue(testConfig.wmUpdateIntervalMs < defaultConfig.wmUpdateIntervalMs)
        assertTrue(testConfig.fileBatchSize < defaultConfig.fileBatchSize)
        // 结构与默认配置一致（每个阶段字段合法）
        assertTrue(testConfig.progressPhaseVerifyingStart <= testConfig.progressPhaseVerifyingMax)
    }
}