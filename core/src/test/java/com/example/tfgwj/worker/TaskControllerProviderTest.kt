package com.example.tfgwj.worker

import com.example.tfgwj.domain.model.TaskPhase
import com.example.tfgwj.domain.repository.TaskController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TaskControllerProvider 单例一致性测试
 *
 * 验证"任务契约只走 ConfigRepository（唯一权威状态源）"红线：
 * [TaskControllerProvider] 必须始终返回同一个实例，避免 Worker 与 UI 各持
 * 独立 `TaskControllerImpl` 导致状态流互不共享。
 *
 * 纯 Kotlin JVM 测试，无需 Robolectric。
 */
class TaskControllerProviderTest {

    /**
     * 同一进程内多次获取必须返回同一实例——这是状态源统一的核心断言。
     * 若此断言失败，说明 [TaskControllerProvider] 的懒初始化被并发破坏或
     * 移除了单例语义，任务进度将再次发生跨实例断裂。
     */
    @Test
    fun `get returns the same instance every call`() {
        val a = TaskControllerProvider.get()
        val b = TaskControllerProvider.get()
        assertSame("TaskControllerProvider 必须返回单例；实际返回不同实例则状态源断裂", a, b)
    }

    /**
     * 获取到的实例必须实现完整 TaskController 状态机，且能正常推进状态。
     * 保证 Provider 抛出的不是未初始化代理或禁用对象。
     */
    @Test
    fun `get returns a functional TaskController that can advance state`() = runTest {
        val controller: TaskController = TaskControllerProvider.get()

        controller.startMeasure()
        assertEquals(TaskPhase.PREPARING, controller.state.first().phase)

        controller.updateState(
            processed = 10,
            total = 100,
            currentFile = "test.bin",
            progress = 10,
            speed = 1.5f,
            phase = TaskPhase.REPLACING,
        )
        val running = controller.state.first()
        assertEquals(TaskPhase.REPLACING, running.phase)
        assertEquals(10, running.processed)
        assertEquals(100, running.total)
        assertTrue("运行中状态应处于 isReplacing", running.isReplacing)

        controller.finish()
        assertEquals(TaskPhase.COMPLETED, controller.state.first().phase)
    }

    /**
     * Provider 初始状态应为 IDLE（干净基线），确保多个调用方从一致起点订阅。
     *
     * 注意：单例的状态会被任何使用方推进，因此不能断言某次获取后恰好为 IDLE
     * （测试执行顺序会使该断言不稳定）。此处验证的是能力：通过 [TaskControllerImpl]
     * 的全新（非共享）实例确认初始态为 IDLE，从而证明 Provider 委托的实现是干净的。
     */
    @Test
    fun `delegated implementation starts in IDLE`() = runTest {
        val controller = TaskControllerImpl()
        assertEquals(TaskPhase.IDLE, controller.state.first().phase)
    }
}
