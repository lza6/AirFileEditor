package com.example.tfgwj.manager

import com.example.tfgwj.domain.model.TaskPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReplaceProgressManagerTest {

    @Before
    fun resetState() {
        ReplaceProgressManager.reset()
    }

    @Test
    fun `failure keeps terminal state and error for UI feedback`() {
        ReplaceProgressManager.startMeasure()

        ReplaceProgressManager.fail("源目录不存在")

        val state = ReplaceProgressManager.progressState.value
        assertEquals(TaskPhase.FAILURE, state.phase)
        assertFalse(state.isReplacing)
        assertEquals("源目录不存在", state.errorMessage)
    }

    @Test
    fun `cancel returns terminal cancelled state without a stale error`() {
        ReplaceProgressManager.fail("old failure")

        ReplaceProgressManager.cancel()

        val state = ReplaceProgressManager.progressState.value
        assertEquals(TaskPhase.CANCELLED, state.phase)
        assertFalse(state.isReplacing)
        assertNull(state.errorMessage)
    }

    @Test
    fun `startMeasure resets counters and clears previous terminal state`() {
        ReplaceProgressManager.updateState(9, 10, "last", 90)
        ReplaceProgressManager.finish()

        ReplaceProgressManager.startMeasure()

        val state = ReplaceProgressManager.progressState.value
        assertEquals(TaskPhase.PREPARING, state.phase)
        assertTrue(state.isReplacing)
        assertEquals(0, state.processed)
        assertEquals(0, state.total)
        assertEquals(0, state.progress)
        assertNull(state.errorMessage)
    }

    @Test
    fun `cancel is terminal and not considered running`() {
        ReplaceProgressManager.updateState(3, 10, "mid", 30)

        ReplaceProgressManager.cancel()

        val state = ReplaceProgressManager.progressState.value
        assertEquals(TaskPhase.CANCELLED, state.phase)
        assertFalse(state.isReplacing)
        assertEquals(3, state.processed)
        assertEquals(10, state.total)
    }

    @Test
    fun `success is terminal and preserves completed progress`() {
        ReplaceProgressManager.updateState(9, 10, "last", 90)

        ReplaceProgressManager.finish()

        val state = ReplaceProgressManager.progressState.value
        assertEquals(TaskPhase.COMPLETED, state.phase)
        assertFalse(state.isReplacing)
        assertTrue(state.progress >= 90)
    }

    @Test
    fun `startMeasure initializes to PREPARING phase`() {
        ReplaceProgressManager.startMeasure()

        val state = ReplaceProgressManager.progressState.value
        assertEquals(TaskPhase.PREPARING, state.phase)
        assertEquals(0, state.processed)
        assertEquals(0, state.total)
        assertEquals(0, state.progress)
        assertTrue(state.isReplacing)
        assertFalse(state.isTerminal)
    }

    @Test
    fun `updateState with explicit phase sets correct enum value`() {
        ReplaceProgressManager.updateState(5, 20, "file.bin", 25, phase = TaskPhase.REPLACING)

        val state = ReplaceProgressManager.progressState.value
        assertEquals(TaskPhase.REPLACING, state.phase)
        assertEquals(5, state.processed)
        assertEquals(20, state.total)
        assertEquals("file.bin", state.currentFile)
    }

    @Test
    fun `isTerminal is true for COMPLETED state`() {
        ReplaceProgressManager.updateState(10, 10, "done", 100)
        ReplaceProgressManager.finish()

        assertTrue(ReplaceProgressManager.progressState.value.isTerminal)
    }

    @Test
    fun `isTerminal is true for FAILURE state`() {
        ReplaceProgressManager.fail("error")

        assertTrue(ReplaceProgressManager.progressState.value.isTerminal)
    }

    @Test
    fun `isTerminal is false for running states`() {
        ReplaceProgressManager.startMeasure()

        assertFalse(ReplaceProgressManager.progressState.value.isTerminal)
    }

    @Test
    fun `reset clears all state to defaults`() {
        ReplaceProgressManager.updateState(10, 10, "final", 100)
        ReplaceProgressManager.finish()

        ReplaceProgressManager.reset()

        val state = ReplaceProgressManager.progressState.value
        assertEquals(TaskPhase.IDLE, state.phase)
        assertEquals(0, state.processed)
        assertEquals(0, state.total)
        assertNull(state.errorMessage)
        assertFalse(state.isReplacing)
        assertFalse(state.isTerminal)
    }
}
