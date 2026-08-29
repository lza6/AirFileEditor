package com.example.tfgwj.worker

import com.example.tfgwj.domain.model.TaskPhase
import com.example.tfgwj.domain.repository.TaskState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TaskControllerImpl 的纯 JVM 单元测试
 *
 * 验证状态机转换：
 * IDLE → PREPARING → REPLACING → VERIFYING → COMPLETED
 *                ↓            ↓           ↓
 *                └──── FAILURE / CANCELLED ←┘
 *
 * 不需要 Robolectric，纯 Kotlin JVM 测试。
 */
class TaskControllerImplTest {

    private val controller = TaskControllerImpl()

    @Test
    fun `startMeasure sets phase to PREPARING`() = runTest {
        controller.startMeasure()

        val state = controller.state.first()
        assertEquals(TaskPhase.PREPARING, state.phase)
        assertEquals(0, state.processed)
        assertEquals(0, state.total)
        assertEquals(0, state.progress)
        assertEquals("", state.currentFile)
        assertEquals(0f, state.speed)
        assertNull(state.errorMessage)
    }

    @Test
    fun `updateState correctly updates all fields`() = runTest {
        controller.updateState(
            processed = 10,
            total = 100,
            currentFile = "test.txt",
            progress = 50,
            speed = 25.5f,
            phase = TaskPhase.REPLACING,
            errorMessage = null,
        )

        val state = controller.state.first()
        assertEquals(10, state.processed)
        assertEquals(100, state.total)
        assertEquals(50, state.progress)
        assertEquals("test.txt", state.currentFile)
        assertEquals(25.5f, state.speed)
        assertEquals(TaskPhase.REPLACING, state.phase)
        assertNull(state.errorMessage)
    }

    @Test
    fun `finish sets phase to COMPLETED and clears errorMessage`() = runTest {
        // 先设置一个错误状态
        controller.updateState(
            processed = 50, total = 100, currentFile = "test.txt",
            progress = 50, speed = 0f, phase = TaskPhase.VERIFYING,
            errorMessage = "some error",
        )

        controller.finish()

        val state = controller.state.first()
        assertEquals(TaskPhase.COMPLETED, state.phase)
        assertNull(state.errorMessage)
        // 其他字段保持最后一次 updateState 的值不变
        assertEquals(50, state.processed)
        assertEquals(100, state.total)
    }

    @Test
    fun `fail sets phase to FAILURE and preserves errorMessage`() = runTest {
        controller.updateState(
            processed = 10, total = 100, currentFile = "fail.txt",
            progress = 10, speed = 0f, phase = TaskPhase.REPLACING,
        )

        controller.fail("复制失败: 文件未找到")

        val state = controller.state.first()
        assertEquals(TaskPhase.FAILURE, state.phase)
        assertEquals("复制失败: 文件未找到", state.errorMessage)
        // 进度信息保留供 UI 展示
        assertEquals(10, state.processed)
    }

    @Test
    fun `cancel sets phase to CANCELLED`() = runTest {
        controller.updateState(
            processed = 30, total = 100, currentFile = "cancel.txt",
            progress = 30, speed = 0f, phase = TaskPhase.REPLACING,
        )

        controller.cancel()

        val state = controller.state.first()
        assertEquals(TaskPhase.CANCELLED, state.phase)
        assertNull(state.errorMessage)
        // 进度信息保留供 UI 解释
        assertEquals(30, state.processed)
    }

    @Test
    fun `reset returns to IDLE default state`() = runTest {
        // 先进入一个非初始状态
        controller.startMeasure()
        controller.updateState(
            processed = 50, total = 100, currentFile = "reset.txt",
            progress = 50, speed = 0f, phase = TaskPhase.VERIFYING,
        )

        controller.reset()

        val state = controller.state.first()
        assertEquals(TaskPhase.IDLE, state.phase)
        assertEquals(0, state.processed)
        assertEquals(0, state.total)
        assertEquals(0, state.progress)
        assertEquals("", state.currentFile)
        assertEquals(0f, state.speed)
        assertNull(state.errorMessage)
    }

    @Test
    fun `isReplacing returns true when phase is PREPARING REPLACING or VERIFYING`() {
        assertTrue(TaskState(phase = TaskPhase.PREPARING).isReplacing)
        assertTrue(TaskState(phase = TaskPhase.REPLACING).isReplacing)
        assertTrue(TaskState(phase = TaskPhase.VERIFYING).isReplacing)

        // 终态和 IDLE 不算 isReplacing
        assertFalse(TaskState(phase = TaskPhase.IDLE).isReplacing)
        assertFalse(TaskState(phase = TaskPhase.COMPLETED).isReplacing)
        assertFalse(TaskState(phase = TaskPhase.FAILURE).isReplacing)
        assertFalse(TaskState(phase = TaskPhase.CANCELLED).isReplacing)
    }

    @Test
    fun `isTerminal returns true when phase is COMPLETED FAILURE or CANCELLED`() {
        assertTrue(TaskState(phase = TaskPhase.COMPLETED).isTerminal)
        assertTrue(TaskState(phase = TaskPhase.FAILURE).isTerminal)
        assertTrue(TaskState(phase = TaskPhase.CANCELLED).isTerminal)

        // 非终态
        assertFalse(TaskState(phase = TaskPhase.IDLE).isTerminal)
        assertFalse(TaskState(phase = TaskPhase.PREPARING).isTerminal)
        assertFalse(TaskState(phase = TaskPhase.REPLACING).isTerminal)
        assertFalse(TaskState(phase = TaskPhase.VERIFYING).isTerminal)
    }

    @Test
    fun `cancel after completion keeps terminal state consistent`() = runTest {
        // 终态交叉：COMPLETED 之后再 cancel，不应回到运行中
        controller.startMeasure()
        controller.finish()

        controller.cancel()

        val state = controller.state.first()
        // cancel() 保持 CANCELLED 终态（copy cased），errorMessage 清空
        assertEquals(TaskPhase.CANCELLED, state.phase)
        assertFalse(state.isReplacing)
        assertTrue(state.isTerminal)
    }

    @Test
    fun `fail after completion stays terminal and not replacing`() = runTest {
        controller.startMeasure()
        controller.finish()

        controller.fail("终态后失败应保持 FAILURE")

        val state = controller.state.first()
        assertEquals(TaskPhase.FAILURE, state.phase)
        assertTrue(state.isTerminal)
        assertFalse(state.isReplacing)
        assertEquals("终态后失败应保持 FAILURE", state.errorMessage)
    }
}