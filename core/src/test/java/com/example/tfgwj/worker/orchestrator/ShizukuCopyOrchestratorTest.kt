package com.example.tfgwj.worker.orchestrator

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.example.tfgwj.shizuku.ShizukuManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
 * ShizukuCopyOrchestrator 的单元测试骨架
 *
 * 验证 Shizuku 模式编排器的核心行为：
 * 1. 构造参数正常初始化
 * 2. execute 返回 Success 当源目录为空
 * 3. 策略类型为 SHIZUKU
 * 4. cleanup 幂等安全
 *
 * 注意：完整的 Shizuku 命令执行测试需要 Shizuku 环境，这里仅做逻辑验证。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ShizukuCopyOrchestratorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = CopyConfig.getTestConfig()
    private val shizukuManager = mockk<ShizukuManager>(relaxed = true)

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
    fun `construction with context config and shizukuManager succeeds`() {
        val orchestrator = ShizukuCopyOrchestrator(context, config, shizukuManager)
        assertNotNull(orchestrator)
        assertEquals(StrategyType.SHIZUKU, orchestrator.getStrategyType())
    }

    @Test
    fun `getStrategyType returns SHIZUKU`() {
        val orchestrator = ShizukuCopyOrchestrator(context, config, shizukuManager)
        assertEquals(StrategyType.SHIZUKU, orchestrator.getStrategyType())
    }

    @Test
    fun `cleanup does not throw`() {
        val orchestrator = ShizukuCopyOrchestrator(context, config, shizukuManager)
        orchestrator.cleanup()
        // 再次调用也不应抛出（幂等）
        orchestrator.cleanup()
    }

    @Test
    fun `execute returns Failure when source directory is empty`() = runTest {
        every { shizukuManager.isAuthorized } returns MutableStateFlow(true)
        every { shizukuManager.isServiceConnected } returns MutableStateFlow(true)
        every { shizukuManager.executeCommandWithOutput(any<String>()) } returns "0"

        val emptyDir = File(context.cacheDir, "shizuku_empty_${System.nanoTime()}")
        try {
            val orchestrator = ShizukuCopyOrchestrator(context, config, shizukuManager)
            val result = orchestrator.execute(
                androidDir = emptyDir,
                targetPackage = "com.example.test",
                incrementalUpdate = false,
                progressCallback = { _, _, _, _, _ -> },
            )
            // 生产行为：Shizuku 模式空源目录时统计 totalFiles=0，直接返回 Failure("源目录为空")
            assertTrue("空目录应返回 Failure（生产行为：源目录为空即失败）", result is OrchestratorResult.Failure)
        } finally {
            emptyDir.deleteRecursively()
        }
    }

    @Test
    fun `verify does not throw and returns non-negative`() = runTest {
        // verify 是纯转发接口；在未执行 execute 时返回守卫值（≥0），不应抛异常
        val orchestrator = ShizukuCopyOrchestrator(context, config, shizukuManager)
        val result = orchestrator.verify(0)
        assertTrue("verify 应返回非负计数", result >= 0)
    }
}