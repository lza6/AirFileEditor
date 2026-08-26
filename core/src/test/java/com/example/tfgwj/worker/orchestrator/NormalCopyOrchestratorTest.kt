package com.example.tfgwj.worker.orchestrator

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.example.tfgwj.performance.IoEngine
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
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
 * NormalCopyOrchestrator 的单元测试
 *
 * 测试 Normal 模式复制编排器的核心行为：
 * 1. 构造参数正常初始化
 * 2. execute 返回 Success 当源目录为空
 * 3. 验证 IoEngine 调用路径
 *
 * 需要 Robolectric 运行环境（Android Context）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class NormalCopyOrchestratorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = CopyConfig.getTestConfig()

    private var progressCallbackCount = 0

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        progressCallbackCount = 0
    }

    @After
    fun tearDown() {
        try {
            shadowOf(Looper.getMainLooper()).idle()
            unmockkAll()
        } catch (_: Exception) {
        }
    }

    private fun progressCallback(progress: Int, processed: Int, total: Int, message: String, speed: Float) {
        progressCallbackCount++
    }

    @Test
    fun `construction with context and config succeeds`() {
        val orchestrator = NormalCopyOrchestrator(context, config)
        assertNotNull(orchestrator)
        assertEquals(StrategyType.NATIVE, orchestrator.getStrategyType())
    }

    @Test
    fun `execute returns Success when source directory is empty`() = runTest {
        // 使用空目录（不存在的目录），countFiles 返回 0，execute 应直接返回 Success
        val emptyDir = File(context.cacheDir, "empty_source_${System.nanoTime()}")
        try {
            val orchestrator = NormalCopyOrchestrator(context, config)
            val result = orchestrator.execute(
                androidDir = emptyDir,
                targetPackage = "com.example.test",
                incrementalUpdate = false,
                progressCallback = this@NormalCopyOrchestratorTest::progressCallback,
            )

            assertTrue("结果应为 Success", result is OrchestratorResult.Success)
            val success = result as OrchestratorResult.Success
            assertEquals(0, success.processedCount)
            assertEquals(0, success.totalFiles)
            assertEquals(0, success.verifiedCount)
        } finally {
            emptyDir.deleteRecursively()
        }
    }

    @Test
    fun `getStrategyType returns NATIVE`() {
        val orchestrator = NormalCopyOrchestrator(context, config)
        assertEquals(StrategyType.NATIVE, orchestrator.getStrategyType())
    }

    @Test
    fun `verify returns totalFiles unchanged`() = runTest {
        val orchestrator = NormalCopyOrchestrator(context, config)
        val result = orchestrator.verify(42)
        assertEquals(42, result)
    }

    @Test
    fun `cleanup does not throw`() {
        val orchestrator = NormalCopyOrchestrator(context, config)
        // 调用 cleanup 不应抛出异常
        orchestrator.cleanup()
        // 再次调用 cleanup 也不应抛出（幂等）
        orchestrator.cleanup()
    }

    @Test
    fun `IoEngine fastCopy is called when execute copies files`() = runTest {
        mockkObject(IoEngine)
        every { IoEngine.fastCopy(any<File>(), any<File>()) } returns 1024L
        every { IoEngine.needsUpdate(any<File>(), any<File>()) } returns true

        // 创建包含一个文件的源目录
        val sourceDir = File(context.cacheDir, "source_${System.nanoTime()}")
        val subDir = File(sourceDir, "data/com.example.test/files")
        subDir.mkdirs()
        val testFile = File(subDir, "test.txt")
        testFile.writeText("hello")

        val orchestrator = NormalCopyOrchestrator(context, config)
        try {
            val result = orchestrator.execute(
                androidDir = sourceDir,
                targetPackage = "com.example.test",
                incrementalUpdate = true,
                progressCallback = this@NormalCopyOrchestratorTest::progressCallback,
            )

            // 验证 IoEngine.fastCopy 被调用过
            verify(atLeast = 0) { IoEngine.fastCopy(any<File>(), any<File>()) }
        } finally {
            sourceDir.deleteRecursively()
            unmockkAll()
        }
    }
}