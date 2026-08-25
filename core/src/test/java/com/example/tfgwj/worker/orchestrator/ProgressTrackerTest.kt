package com.example.tfgwj.worker.orchestrator

import android.util.Log
import com.example.tfgwj.domain.model.TaskPhase
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ProgressTrackerTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val config = CopyConfig.getTestConfig()

    private var progressCount = 0
    private var lastProgress = -1
    private var lastProcessed = -1
    private var lastTotal = -1
    private var lastMessage = ""
    private var lastSpeed = -1f
    private var lastPhase = TaskPhase.IDLE

    private var virtualTime = 0L

    private lateinit var tracker: ProgressTracker

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.v(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        virtualTime = 1000L
        progressCount = 0
        tracker =
            ProgressTracker(
                config, testScope,
                clock = { virtualTime },
            ) { p, pr, t, m, s, ph ->
                progressCount++
                lastProgress = p
                lastProcessed = pr
                lastTotal = t
                lastMessage = m
                lastSpeed = s
                lastPhase = ph
            }
    }

    @After
    fun tearDown() {
        try {
            shadowOf(Looper.getMainLooper()).idle()
            unmockkAll()
        } catch (e: Exception) {
            // Ignore unmockk errors during teardown
        }
    }

    @Test
    fun `initial update triggers callback`() = runTest {
        tracker.initialize(100)
        tracker.updateProgress(0, "Starting")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, progressCount)
        assertEquals(0, lastProgress)
        assertEquals(0, lastProcessed)
        assertEquals(100, lastTotal)
    }

    @Test
    fun `updates are throttled by time`() = runTest {
        tracker.initialize(100)

        // 1. First update (isInitial=true)
        virtualTime = 2000L
        tracker.updateProgress(0, "Update 1")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, progressCount)

        // 2. Immediate update (processed=2, not initial in test config threshold=1)
        // Interval is 200ms. Since we didn't wait, it should NOT trigger.
        virtualTime = 2100L
        tracker.updateProgress(2, "Update 2")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, progressCount) // Throttled

        // 3. Wait enough time (total 250ms elapsed since last success)
        virtualTime = 2300L
        tracker.updateProgress(3, "Update 3")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, progressCount) // Triggered
    }

    @Test
    fun `markComplete triggers final callback`() = runTest {
        tracker.initialize(100)

        // Even if we just updated and are throttled
        virtualTime = 2000L
        tracker.updateProgress(50, "Halfway")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, progressCount)

        // markComplete must always trigger regardless of throttle interval
        virtualTime = 2050L
        tracker.markComplete()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, progressCount)
        assertEquals(100, lastProgress)
        assertEquals(100, lastProcessed)
    }

    @Test
    fun `phase is correctly propagated`() = runTest {
        tracker.initialize(100)
        virtualTime = 2000L
        tracker.updateProgress(50, "Verifying...", TaskPhase.VERIFYING)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(TaskPhase.VERIFYING, lastPhase)
    }

    @Test
    fun `progress is clamped to 100`() = runTest {
        tracker.initialize(100)
        virtualTime = 2000L
        tracker.updateProgress(150, "Over")
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(100, lastProgress)
    }
}
