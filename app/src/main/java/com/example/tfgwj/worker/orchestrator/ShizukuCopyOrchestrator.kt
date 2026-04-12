package com.example.tfgwj.worker.orchestrator

import android.content.Context
import android.util.Log
import com.example.tfgwj.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Shizuku 模式复制编排器
 * 使用 Shizuku 跨进程权限执行批量复制
 *
 * 核心流程：
 * 1. 等待 Shizuku 服务连接（如有必要）
 * 2. 统计文件总数
 * 3. 启动看门狗（通过进度驱动而非 shell 输出解析）
 * 4. 使用 Shizuku.newProcess 执行 cp -R
 * 5. Shizuku 验证（同 Root）
 *
 * 技术要点：
 * - 使用 rikka.shizuku.Shizuku.newProcess() 而非 Shell
 * - 看门狗不依赖 shell 输出，直接基于进度计数
 * - 并发控制更保守（Shizuku IPC 开销）
 *
 * @property context 应用上下文
 * @property config 复制配置参数
 * @property shizukuManager Shizuku 管理器实例
 * @version V8.0.0 - Architecture Evolution
 */
class ShizukuCopyOrchestrator(
    private val context: Context,
    private val config: CopyConfig,
    private val shizukuManager: ShizukuManager,
) : FileReplaceOrchestrator {
    companion object {
        private const val TAG = "ShizukuCopyOrchestrator"

        private const val CMD_MKDIR = "mkdir -p \"%s\""
        private const val CMD_CP_DIR = "cp -p -v -R \"%s/.\" \"%s/\""
        private const val CMD_CP_FILE = "cp -p -v \"%s\" \"%s\""
    }

    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var watchdogJob: Job? = null
    private val progressCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private val watchdogActive = java.util.concurrent.atomic.AtomicBoolean(true)

    private lateinit var fileStatistics: FileStatistics
    private lateinit var progressTracker: ProgressTracker
    private lateinit var verificationManager: VerificationManager

    private var totalFiles = 0
    private var targetPackage = ""

    override suspend fun execute(
        androidDir: File,
        targetPackage: String,
        incrementalUpdate: Boolean,
        progressCallback: (progress: Int, processed: Int, total: Int, message: String, speed: Float) -> Unit,
    ): OrchestratorResult {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            Log.d(TAG, "🚀 Shizuku 模式启动: ${androidDir.absolutePath} -> $targetPackage")

            this@ShizukuCopyOrchestrator.targetPackage = targetPackage
            this@ShizukuCopyOrchestrator.fileStatistics = FileStatistics(context, shizukuManager)
            this@ShizukuCopyOrchestrator.verificationManager = VerificationManager(context, shizukuManager)

            try {
                // 1. 等待 Shizuku 服务连接（如果需要）
                waitForShizukuService()

                // 2. 统计文件总数
                progressCallback(0, 0, 0, "正在扫描源文件...", 0f)
                totalFiles = fileStatistics.countFiles(androidDir)

                if (totalFiles == 0) {
                    return@withContext OrchestratorResult.Failure("源目录为空")
                }

                Log.d(TAG, "📊 扫描完成: $totalFiles 个文件")

                // 3. 初始化进度跟踪器
                progressTracker =
                    ProgressTracker(config, scope) { p, processed, total, msg, speed, phase ->
                        progressCallback(p, processed, total, msg, speed)
                    }
                progressTracker.initialize(totalFiles)

                // 4. 准备目标环境
                val targetBase = PathConstants.buildTargetDataPath(targetPackage)
                executeShizukuCommand(CMD_MKDIR.format(targetBase))

                // 5. 执行递归复制（带看门狗）
                progressCallback(5, 0, totalFiles, "开始复制...", 0f)
                executeShizukuRecursiveCopy(androidDir, targetPackage)

                // 6. 验证结果
                progressCallback(config.progressPhaseVerifyingStart, totalFiles, totalFiles, "正在验证...", 0f)
                val verifiedCount = verificationManager.verify(androidDir, targetPackage, totalFiles, VerificationMode.SHIZUKU)

                // 7. 完成
                progressTracker.markComplete()

                val duration = System.currentTimeMillis() - startTime
                Log.i(TAG, "✅ Shizuku 模式完成: 处理 $totalFiles, 验证 $verifiedCount, 耗时 ${duration}ms")

                OrchestratorResult.Success(
                    processedCount = totalFiles,
                    totalFiles = totalFiles,
                    verifiedCount = verifiedCount,
                    metadata =
                        mapOf(
                            "mode" to "SHIZUKU",
                            "duration" to duration.toString(),
                        ),
                )
            } catch (e: Exception) {
                Log.e(TAG, "❌ Shizuku 模式失败", e)
                OrchestratorResult.Failure("Shizuku 复制失败: ${e.message}", e)
            } finally {
                cleanup()
            }
        }
    }

    override suspend fun verify(totalFiles: Int): Int {
        return withContext(Dispatchers.IO) {
            try {
                verificationManager.verify(
                    File("").apply { /* 需要源目录路径 */ },
                    targetPackage,
                    totalFiles,
                    VerificationMode.SHIZUKU,
                )
            } catch (e: Exception) {
                Log.e(TAG, "验证失败", e)
                0
            }
        }
    }

    override fun getStrategyType(): StrategyType = StrategyType.SHIZUKU

    override fun cleanup() {
        try {
            watchdogActive.set(false)
            watchdogJob?.cancel()
            scope.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "清理资源失败", e)
        }
    }

    /**
     * 等待 Shizuku 服务连接
     */
    private suspend fun waitForShizukuService() {
        if (shizukuManager.isAvailable.value && shizukuManager.isAuthorized.value && !shizukuManager.isServiceConnected.value) {
            Log.d(TAG, "检测到 Shizuku 已授权但未连接，尝试等待...")
            try {
                kotlinx.coroutines.withTimeout(2000) {
                    while (!shizukuManager.isServiceConnected.value && isActive) {
                        kotlinx.coroutines.delay(100)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "等待 Shizuku 连接超时，将尝试继续执行")
            }
        }
    }

    /**
     * 执行 Shizuku 递归复制
     */
    private suspend fun executeShizukuRecursiveCopy(
        sourceRoot: File,
        targetPackage: String,
    ) {
        withContext(Dispatchers.IO) {
            val tasks = fileStatistics.collectDirectoryTasks(sourceRoot, targetPackage)

            // 启动看门狗（Shizuku 模式下不解析 shell 输出，直接驱动进度）
            watchdogJob =
                scope.launch {
                    while (watchdogActive.get() && isActive) {
                        delay(500) // 500ms 更新频率
                        if (!watchdogActive.get()) break

                        try {
                            val current = progressCounter.get()
                            val progress =
                                if (totalFiles > 0) {
                                    (current.toFloat() / totalFiles * config.progressPhaseReplacingMax).toInt().coerceIn(
                                        0,
                                        config.progressPhaseReplacingMax,
                                    )
                                } else {
                                    0
                                }

                            progressTracker.updateProgress(
                                processed = current,
                                message = "进行中... ($current/$totalFiles)",
                                phase = "REPLACING",
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "看门狗更新跳过: ${e.message}")
                        }
                    }
                    Log.d(TAG, "🕵️ 看门狗已停止")
                }

            try {
                val semaphore = Semaphore(config.shizukuConcurrentPermits)
                tasks.map { task ->
                    scope.async {
                        semaphore.withPermit {
                            runShizukuCpCommand(task)
                        }
                    }
                }.awaitAll()
            } finally {
                watchdogActive.set(false)
                watchdogJob?.cancel()
            }
        }
    }

    /**
     * 执行单个 Shizuku cp 命令
     */
    private suspend fun runShizukuCpCommand(task: FileStatistics.CopyTask) {
        val cmd =
            if (task.isDirectory) {
                CMD_CP_DIR.format(task.sourceDir.absolutePath, task.targetDir)
            } else {
                CMD_CP_FILE.format(task.sourceDir.absolutePath, task.targetDir)
            }

        try {
            @Suppress("DEPRECATION")
            val process = rikka.shizuku.Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val reader = process.inputStream.bufferedReader()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) continue

                val current = progressCounter.incrementAndGet()

                // 解析文件名（同 Root 模式）
                val fileName = extractFileNameFromCpOutput(line)

                progressTracker.updateProgress(
                    processed = current,
                    message = fileName,
                    phase = "REPLACING",
                )
            }

            process.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku CP 失败: ${task.sourceDir.name}", e)
            throw e
        }
    }

    /**
     * 执行 Shizuku 命令（封装）
     */
    private fun executeShizukuCommand(cmd: String): String? {
        return if (shizukuManager.isServiceConnected.value) {
            shizukuManager.executeCommandWithOutput(cmd)
        } else {
            Log.w(TAG, "Shizuku 服务未连接，跳过命令: $cmd")
            null
        }
    }

    /**
     * 从 cp -v 输出提取文件名
     */
    private fun extractFileNameFromCpOutput(line: String): String {
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
}
