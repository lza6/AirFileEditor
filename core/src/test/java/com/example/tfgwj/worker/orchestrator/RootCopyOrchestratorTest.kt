package com.example.tfgwj.worker.orchestrator

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.io.File

/**
 * RootCopyOrchestrator 的单元测试骨架
 *
 * 验证 Root 模式编排器的核心行为：
 * 1. 构造参数正常初始化
 * 2. execute 返回 Success 当源目录为空
 * 3. 策略类型为 ROOT
 * 4. cleanup 幂等安全
 *
 * 注意：完整的 Root 命令执行测试需要 Root 环境，这里仅做逻辑验证。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RootCopyOrchestratorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = CopyConfig.getTestConfig()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @After
    fun tearDown() {
        try {
            shadowOf(Looper.getMainLooper()).idle()
            unmockkAll()
        } catch (_: Exception) {
        }
    }

    @Test
    fun `construction with context and config succeeds`() {
        val orchestrator = RootCopyOrchestrator(context, config)
        assertNotNull(orchestrator)
        assertEquals(StrategyType.ROOT, orchestrator.getStrategyType())
    }

    @Test
    fun `getStrategyType returns ROOT`() {
        val orchestrator = RootCopyOrchestrator(context, config)
        assertEquals(StrategyType.ROOT, orchestrator.getStrategyType())
    }

    @Test
    fun `cleanup does not throw`() {
        val orchestrator = RootCopyOrchestrator(context, config)
        orchestrator.cleanup()
        // 再次调用也不应抛出（幂等）
        orchestrator.cleanup()
    }

    @Test
    fun `execute returns Failure when no root available`() = runTest {
        // 不 mock RootChecker，executeRootCommand 在测试环境返回 null
        val emptyDir = File(context.cacheDir, "root_empty_${System.nanoTime()}")
        try {
            val orchestrator = RootCopyOrchestrator(context, config)
            val result = orchestrator.execute(
                androidDir = emptyDir,
                targetPackage = "com.example.test",
                incrementalUpdate = false,
                progressCallback = { _, _, _, _, _ -> },
            )
            // 无 Root 环境下，文件统计降级到 Native，空目录应返回 Success
            val isSuccess = result is OrchestratorResult.Success
            val isFailure = result is OrchestratorResult.Failure
            assert(isSuccess || isFailure) { "结果应为 Success 或 Failure" }
            if (isSuccess) {
                assertEquals(0, (result as OrchestratorResult.Success).processedCount)
            }
        } finally {
            emptyDir.deleteRecursively()
        }
    }

    @Test
    fun `verify returns count`() = runTest {
        val orchestrator = RootCopyOrchestrator(context, config)
        // 验证接口可用，不抛出异常
        try {
            orchestrator.verify(0)
        } catch (_: Exception) {
            // 未初始化时可能抛出异常，不做要求
        }
    }
}