package com.example.tfgwj.worker.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpeedCalculatorTest {

    private lateinit var calculator: SpeedCalculator
    private val windowSeconds = 3

    @Before
    fun setup() {
        calculator = SpeedCalculator(windowSeconds)
    }

    @Test
    fun `initial speed is zero`() {
        val speed = calculator.update(0, 1000)
        assertEquals(0f, speed, 0.01f)
    }

    @Test
    fun `calculates speed correctly over a window`() {
        // t=1000, processed=0
        calculator.update(0, 1000)

        // t=2000, processed=10 (speed = 10 files/sec)
        val speed = calculator.update(10, 2000)
        assertEquals(10f, speed, 0.1f)
    }

    @Test
    fun `sliding window removes old samples`() {
        // t=1000, p=0
        calculator.update(0, 1000)
        // t=2000, p=10 (speed=10)
        calculator.update(10, 2000)
        // t=3000, p=20 (speed=10)
        calculator.update(20, 3000)

        // t=5000, p=50. Window is 3s, so t=1000 sample should be removed.
        // First sample in window is now t=2000, p=10.
        // Diff = p(50-10)=40, t(5000-2000)=3s. Speed = 40/3 = 13.33
        val speed = calculator.update(50, 5000)
        assertEquals(13.33f, speed, 0.1f)
    }

    @Test
    fun `reset clears all state`() {
        calculator.update(10, 2000)
        calculator.reset()
        val speed = calculator.update(0, 3000)
        assertEquals(0f, speed, 0.01f)
    }

    @Test
    fun `zero time diff handles gracefully`() {
        calculator.update(0, 1000)
        val speed = calculator.update(10, 1000)
        assertEquals(0f, speed, 0.01f)
    }
}
