package com.example.tfgwj.utils

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Root 权限检测工具类
 */
object RootChecker {
    private const val TAG = "RootChecker"
    private const val COMMAND_TIMEOUT_MS = 5_000L

    internal data class CommandResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val timedOut: Boolean = false,
    )

    internal fun interface CommandRunner {
        fun run(arguments: List<String>): CommandResult
    }

    private var cachedIsRooted: Boolean? = null
    private var commandPrefix: List<String>? = null
    private var commandRunner: CommandRunner = CommandRunner(::runProcess)

    internal fun setCommandRunnerForTest(runner: CommandRunner?) {
        commandRunner = runner ?: CommandRunner(::runProcess)
        refresh()
    }

    fun refresh() {
        cachedIsRooted = null
        commandPrefix = null
    }

    /**
     * 检测当前 App 是否真正具备 Root 能力。
     *
     * 仅凭 `/system/xbin/su` 等文件存在不能证明当前 App 能提权，
     * 因此必须以实际命令执行成功为准。
     */
    fun isRooted(forceRefresh: Boolean = false): Boolean {
        synchronized(this) {
            if (forceRefresh) refresh()
            cachedIsRooted?.let { return it }

            val prefix = ROOT_COMMAND_PREFIXES.firstOrNull { candidate ->
                val result = commandRunner.run(candidate + listOf("id", "-u"))
                result.exitCode == 0 && !result.timedOut && result.stdout.trim() == "0"
            }
            commandPrefix = prefix
            cachedIsRooted = prefix != null
            if (prefix == null) Log.d(TAG, "Root 检测: 当前 App 无有效 Root 能力")
            return prefix != null
        }
    }

    private val ROOT_COMMAND_PREFIXES =
        listOf(
            listOf("su", "-c"),
            // AOSP userdebug/toybox su 采用 UID + command 形式；真实 Magisk 通常命中上一候选。
            listOf("su", "0", "sh", "-c"),
        )

    fun executeRootCommand(command: String): String? {
        if (!isRooted()) return null
        val prefix = commandPrefix ?: return null
        val result = commandRunner.run(prefix + listOf(command))
        if (result.timedOut || result.exitCode != 0) {
            cachedIsRooted = false
            commandPrefix = null
            if (result.stderr.isNotBlank()) Log.w(TAG, "Root 命令失败: ${result.stderr.trim()}")
            return null
        }
        if (result.stderr.isNotBlank()) Log.w(TAG, "Root 命令输出: ${result.stderr.trim()}")
        return result.stdout
    }

    private fun runProcess(arguments: List<String>): CommandResult {
        return try {
            val process = ProcessBuilder(arguments).redirectErrorStream(false).start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val stdoutThread = Thread {
                process.inputStream.bufferedReader().use { stdout.append(it.readText()) }
            }.apply { isDaemon = true }
            val stderrThread = Thread {
                process.errorStream.bufferedReader().use { stderr.append(it.readText()) }
            }.apply { isDaemon = true }
            stdoutThread.start()
            stderrThread.start()

            if (!process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                stdoutThread.join(200)
                stderrThread.join(200)
                return CommandResult(stdout.toString(), stderr.toString(), -1, timedOut = true)
            }
            stdoutThread.join(200)
            stderrThread.join(200)
            CommandResult(stdout.toString(), stderr.toString(), process.exitValue())
        } catch (e: Exception) {
            CommandResult("", e.message ?: e.javaClass.simpleName, -1)
        }
    }

    /**
     * 检查 Magisk 是否已安装
     */
    fun isMagiskInstalled(): Boolean {
        return File("/data/adb/magisk").exists() ||
            File("/sbin/magisk").exists() ||
            File("/system/bin/magisk").exists()
    }

    /**
     * 获取 Root 管理器类型；不能仅凭一个 su 文件路径猜测具体管理器。
     */
    fun getRootManagerType(): String {
        return when {
            !isRooted() -> "Unknown"
            isMagiskInstalled() -> "Magisk"
            else -> "Root"
        }
    }
}
