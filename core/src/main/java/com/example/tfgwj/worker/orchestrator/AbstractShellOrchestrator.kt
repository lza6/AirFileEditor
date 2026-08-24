package com.example.tfgwj.worker.orchestrator

import android.content.Context
import android.util.Log
import com.example.tfgwj.domain.model.TaskPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 抽象 Shell 编排器基类 (V14 抽取)
 *
 * 消除 RootCopyOrchestrator 与 ShizukuCopyOrchestrator 之间约 60% 的重复代码。
 * 提供通用能力：看门狗、并发控制、路径校验、Shell 转义、cp 输出解析。
 */
abstract class AbstractShellOrchestrator(
    protected val context: Context,
    protected val config: CopyConfig,
) : FileReplaceOrchestrator {

    protected val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    protected val progressCounter = AtomicInteger(0)
    protected val watchdogActive = AtomicBoolean(true)
    protected val permitMutex = Mutex()
    protected val activeProcesses = ConcurrentHashMap.newKeySet<Process>()
    @Volatile
    protected var dynamicPermits: Int = 1
    @Volatile
    protected var runningTasksCount: Int = 0

    protected var watchdogJob: Job? = null
    protected var totalFiles = 0
    protected var targetPackage = ""
    protected var sourceAndroidDir: File = File("")

    // ==================== 子类必须实现 ====================

    /**
     * 执行单个复制命令
     */
    protected abstract suspend fun executeCopyCommand(task: FileStatistics.CopyTask)

    /**
     * 执行 mkdir 命令
     */
    protected abstract fun executeMkdirCommand(path: String): String?

    // ==================== 看门狗 ====================

    /**
     * 创建并启动看门狗协程，定期上报进度
     * @param updateInterval 更新间隔（毫秒）
     * @param progressTracker 进度跟踪器
     * @param progressPhaseEnd 进度阶段上限
     */
    protected fun createWatchdog(
        updateInterval: Long = 300,
        progressTracker: ProgressTracker,
        progressPhaseEnd: Int = config.progressPhaseReplacingMax,
    ): Job {
        watchdogActive.set(true)
        val job = scope.launch {
            while (watchdogActive.get() && isActive) {
                delay(updateInterval)
                if (!watchdogActive.get()) break

                try {
                    val current = progressCounter.get()
                    val progress = if (totalFiles > 0) {
                        (current.toFloat() / totalFiles * progressPhaseEnd).toInt()
                            .coerceIn(0, progressPhaseEnd)
                    } else 0

                    progressTracker.updateProgress(
                        processed = current,
                        message = if (current == 0) "等待输出..." else "正在处理 $current 个文件",
                        phase = TaskPhase.REPLACING,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "看门狗更新跳过: ${e.message}")
                }
            }
            Log.d(TAG, "看门狗已停止")
        }
        watchdogJob = job
        return job
    }

    // ==================== 并发控制 ====================

    /**
     * 根据 scheduler 的 permits 做软并发限制
     * 避免动态替换 Semaphore 实例引发竞态
     */
    protected suspend fun processWithAdaptiveLimit(action: suspend () -> Unit) {
        while (true) {
            val permits = dynamicPermits.coerceAtLeast(1)
            val acquired = withTimeoutOrNull(1000) {
                permitMutex.withLock {
                    if (runningTasksCount < permits) {
                        runningTasksCount++
                        true
                    } else {
                        false
                    }
                }
            } ?: false

            if (acquired) break
            delay(50)
        }

        try {
            action()
        } finally {
            permitMutex.withLock {
                runningTasksCount = (runningTasksCount - 1).coerceAtLeast(0)
            }
        }
    }

    // ==================== 路径安全 ====================

    /**
     * 校验目标路径是否在允许目录内
     */
    protected fun isSafeTargetPath(path: String): Boolean {
        return try {
            val normalized = File(path).canonicalPath
            normalized.startsWith("/storage/emulated/0/Android/data/") ||
                normalized.startsWith("/storage/emulated/0/Android/obb/")
        } catch (e: Exception) {
            false
        }
    }

    // ==================== Shell 工具 ====================

    /**
     * Shell 参数转义（单引号包裹，内部单引号转义）
     */
    protected fun shellEscape(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    /**
     * 从 cp -v 输出提取文件名
     */
    protected fun extractFileNameFromCpOutput(line: String): String {
        return when {
            line.contains(" -> ") -> {
                line.substringAfterLast(" -> ")
                    .trim()
                    .trim('\'', '"')
                    .substringAfterLast("/")
            }
            line.contains("cp '") -> {
                line.substringAfter("cp '")
                    .substringBefore("'")
                    .substringAfterLast("/")
            }
            else -> {
                line.trim()
                    .trim('\'', '"')
                    .substringAfterLast("/")
                    .substringBefore(" ")
                    .ifEmpty { "正在处理..." }
            }
        }
    }

    // ==================== 生命周期 ====================

    override fun cleanup() {
        try {
            watchdogActive.set(false)
            watchdogJob?.cancel()
            activeProcesses.forEach { process ->
                runCatching { process.destroy() }
                if (process.isAlive) runCatching { process.destroyForcibly() }
            }
            activeProcesses.clear()
            scope.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "清理资源失败", e)
        }
    }

    companion object {
        private const val TAG = "AbstractShellOrchestrator"
    }
}