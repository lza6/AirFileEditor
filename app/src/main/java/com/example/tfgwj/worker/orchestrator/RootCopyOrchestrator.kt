package com.example.tfgwj.worker.orchestrator

import android.content.Context
import android.util.Log
import com.example.tfgwj.utils.RootChecker
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
import java.io.File

/**
 * Root 模式复制编排器
 * 使用 Root 权限执行极速批量复制（cp -R + 看门狗监控）
 *
 * 核心流程：
 * 1. 统计源文件总数
 * 2. 准备目标环境（mkdir -p）
 * 3. 启动看门狗协程（实时上报进度）
 * 4. 并行执行 cp 命令（目录级任务）
 * 5. 批量验证（stat 命令）
 *
 * 性能优化：
 * - 使用 Shell cp -R 命令，接近硬件极限
 * - 看门狗实时监控物理进度（无需等待任务完成）
 * - 并发控制：避免 shell 输出竞争
 *
 * @property context 应用上下文
 * @property config 复制配置参数
 * @version V8.0.0 - Architecture Evolution
 */
class RootCopyOrchestrator(
    private val context: Context,
    private val config: CopyConfig,
) : FileReplaceOrchestrator {
    companion object {
        private const val TAG = "RootCopyOrchestrator"

        // Shell 命令模板
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
            Log.d(TAG, "🚀 Root 模式启动: ${androidDir.absolutePath} -> $targetPackage")

            this@RootCopyOrchestrator.targetPackage = targetPackage
            this@RootCopyOrchestrator.fileStatistics = FileStatistics(context)
            this@RootCopyOrchestrator.verificationManager = VerificationManager(context)

            try {
                // 1. 统计文件总数
                progressCallback(0, 0, 0, "正在扫描源文件...", 0f)
                totalFiles = fileStatistics.countFiles(androidDir)

                if (totalFiles == 0) {
                    return@withContext OrchestratorResult.Failure("源目录为空")
                }

                Log.d(TAG, "📊 扫描完成: $totalFiles 个文件")

                // 2. 初始化进度跟踪器
                progressTracker =
                    ProgressTracker(config, scope) { p, processed, total, msg, speed, phase ->
                        progressCallback(p, processed, total, msg, speed)
                    }
                progressTracker.initialize(totalFiles)

                // 3. 准备目标环境
                val targetBase = PathConstants.buildTargetDataPath(targetPackage)
                executeRootCommand(CMD_MKDIR.format(targetBase))

                // 4. 执行递归复制（带看门狗）
                progressCallback(5, 0, totalFiles, "开始复制...", 0f)
                executeRootRecursiveCopy(androidDir, targetPackage)

                // 5. 验证结果
                progressCallback(config.progressPhaseVerifyingStart, totalFiles, totalFiles, "正在验证...", 0f)
                val verifiedCount = verificationManager.verify(androidDir, targetPackage, totalFiles, VerificationMode.ROOT)

                // 6. 完成
                progressTracker.markComplete()

                val duration = System.currentTimeMillis() - startTime
                Log.i(TAG, "✅ Root 模式完成: 处理 $totalFiles, 验证 $verifiedCount, 耗时 ${duration}ms")

                OrchestratorResult.Success(
                    processedCount = totalFiles,
                    totalFiles = totalFiles,
                    verifiedCount = verifiedCount,
                    metadata =
                        mapOf(
                            "mode" to "ROOT",
                            "duration" to duration.toString(),
                        ),
                )
            } catch (e: Exception) {
                Log.e(TAG, "❌ Root 模式失败", e)
                OrchestratorResult.Failure("Root 复制失败: ${e.message}", e)
            } finally {
                cleanup()
            }
        }
    }

    override suspend fun verify(totalFiles: Int): Int {
        return withContext(Dispatchers.IO) {
            try {
                verificationManager.verify(
                    androidDir = File(PathConstants.buildTargetDataPath(targetPackage)),
                    targetPackage = targetPackage,
                    totalFiles = totalFiles,
                    mode = VerificationMode.ROOT,
                )
            } catch (e: Exception) {
                Log.e(TAG, "验证失败", e)
                0
            }
        }
    }

    override fun getStrategyType(): StrategyType = StrategyType.ROOT

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
     * 执行 Root 递归复制
     */
    private suspend fun executeRootRecursiveCopy(
        sourceRoot: File,
        targetPackage: String,
    ) {
        withContext(Dispatchers.IO) {
            // 收集目录级任务
            val tasks = fileStatistics.collectDirectoryTasks(sourceRoot, targetPackage)

            // 启动看门狗协程
            watchdogJob =
                scope.launch {
                    val targetBase = PathConstants.buildTargetDataPath(targetPackage)
                    while (watchdogActive.get() && isActive) {
                        delay(300) // 300ms 更新频率
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
                                message = if (current == 0) "等待输出..." else "正在处理 $current 个文件",
                                phase = "REPLACING",
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "看门狗更新跳过: ${e.message}")
                        }
                    }
                    Log.d(TAG, "🕵️ 看门狗已停止")
                }

            try {
                // 并行执行 cp 命令
                val semaphore = Semaphore(config.rootConcurrentPermits)
                tasks.map { task ->
                    scope.async {
                        semaphore.withPermit {
                            runCpCommand(task)
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
     * 执行单个 cp 命令
     */
    private suspend fun runCpCommand(task: FileStatistics.CopyTask) {
        val cmd =
            if (task.isDirectory) {
                CMD_CP_DIR.format(task.sourceDir.absolutePath, task.targetDir)
            } else {
                CMD_CP_FILE.format(task.sourceDir.absolutePath, task.targetDir)
            }

        Log.v(TAG, "执行 CP: ${task.sourceDir.name} -> $task.targetDir")

        try {
            val process =
                ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true)
                    .start()

            val reader = process.inputStream.bufferedReader()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrEmpty()) continue

                // cp -v 输出解析
                val current = progressCounter.incrementAndGet()

                // 解析文件名（简化版，参考原实现）
                val fileName = extractFileNameFromCpOutput(line)

                progressTracker.updateProgress(
                    processed = current,
                    message = fileName,
                    phase = "REPLACING",
                )
            }

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                Log.w(TAG, "CP 命令退出码非零: $exitCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "CP 执行失败: ${task.sourceDir.name}", e)
            throw e
        }
    }

    /**
     * 执行 Root 命令（封装）
     */
    private fun executeRootCommand(cmd: String): String? {
        return RootChecker.executeRootCommand(cmd)
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
