package com.example.tfgwj.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootCheckerTest {
    @After
    fun tearDown() {
        RootChecker.setCommandRunnerForTest(null)
        RootChecker.refresh()
    }

    @Test
    fun `root command failure is not replaced by su file detection`() {
        val runner = RecordingRunner { RootChecker.CommandResult("", "su: not allowed", 1) }
        RootChecker.setCommandRunnerForTest(runner)

        val result = RootChecker.isRooted(forceRefresh = true)

        assertFalse(result)
        assertEquals(2, runner.commands.size)
    }

    @Test
    fun `magisk su protocol is reused for root commands`() {
        val runner = RecordingRunner { args ->
            if (args == listOf("su", "-c", "id", "-u")) {
                RootChecker.CommandResult("0\n", "", 0)
            } else {
                RootChecker.CommandResult("ok\n", "", 0)
            }
        }
        RootChecker.setCommandRunnerForTest(runner)

        assertTrue(RootChecker.isRooted(forceRefresh = true))
        assertEquals("ok\n", RootChecker.executeRootCommand("echo ok"))
        assertTrue(runner.commands.contains(listOf("su", "-c", "echo ok")))
    }

    @Test
    fun `aosp uid protocol is used when magisk protocol fails`() {
        val runner = RecordingRunner { args ->
            when (args) {
                listOf("su", "-c", "id", "-u") -> RootChecker.CommandResult("", "invalid uid", 1)
                listOf("su", "0", "sh", "-c", "id", "-u") -> RootChecker.CommandResult("0\n", "", 0)
                listOf("su", "0", "sh", "-c", "printf payload") -> RootChecker.CommandResult("payload\n", "", 0)
                else -> RootChecker.CommandResult("", "unknown command", 1)
            }
        }
        RootChecker.setCommandRunnerForTest(runner)

        assertTrue(RootChecker.isRooted(forceRefresh = true))
        assertEquals("payload\n", RootChecker.executeRootCommand("printf payload"))
        assertTrue(runner.commands.contains(listOf("su", "0", "sh", "-c", "printf payload")))
    }

    @Test
    fun `uid output with nonzero exit code is rejected`() {
        val runner = RecordingRunner { RootChecker.CommandResult("uid=0(root)\n", "denied", 1) }
        RootChecker.setCommandRunnerForTest(runner)

        assertFalse(RootChecker.isRooted(forceRefresh = true))
    }

    @Test
    fun `force refresh retries after cached failure`() {
        var attempt = 0
        val runner = RecordingRunner {
            attempt++
            if (attempt <= 2) RootChecker.CommandResult("", "denied", 1)
            else RootChecker.CommandResult("0\n", "", 0)
        }
        RootChecker.setCommandRunnerForTest(runner)

        assertFalse(RootChecker.isRooted(forceRefresh = true))
        assertTrue(RootChecker.isRooted(forceRefresh = true))
    }

    @Test
    fun `timeout is treated as root failure`() {
        val runner = RecordingRunner { RootChecker.CommandResult("0\n", "", -1, timedOut = true) }
        RootChecker.setCommandRunnerForTest(runner)

        assertFalse(RootChecker.isRooted(forceRefresh = true))
    }

    private class RecordingRunner(
        private val response: (List<String>) -> RootChecker.CommandResult,
    ) : RootChecker.CommandRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(arguments: List<String>): RootChecker.CommandResult {
            commands += arguments
            return response(arguments)
        }
    }
}
