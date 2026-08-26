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
import org.junit.Assert.assertTrue
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
    fun `execute returns Failure when no root available and source has files`() = runTest {
        // 创建一个含 1 个文件的源目录（非空），Root 模式下 RootChecker 不可用应导致失败路径
        val sourceDir = File(context.cacheDir, "root_source_${System.nanoTime()}")
        sourceDir.mkdirs()
        File(sourceDir, "test.txt").writeText("hello")
        try {
            val orchestrator = RootCopyOrchestrator(context, config)
            val result = orchestrator.execute(
                androidDir = sourceDir,
                targetPackage = "com.example.test",
                incrementalUpdate = false,
                progressCallback = { _, _, _, _, _ -> },
            )
            // 无 Root 环境下 executeRootCommand 返回 null → 复制失败 → Failure
            assertTrue("无 Root 且源非空应返回 Failure", result is OrchestratorResult.Failure)
        } finally {
            sourceDir.deleteRecursively()
        }
    }

    @Test
    fun `verify does not throw and returns non-negative`() = runTest {
        // verify 是纯转发接口；在未执行 execute 时返回守卫值（≥0），不应抛异常
        val orchestrator = RootCopyOrchestrator(context, config)
        val result = orchestrator.verify(0)
        assertTrue("verify 应返回非负计数", result >= 0)
    }
}