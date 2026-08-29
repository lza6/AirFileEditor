package com.example.tfgwj.worker.orchestrator

import android.content.Context
import android.util.Log
import com.example.tfgwj.domain.model.TaskPhase
import com.example.tfgwj.utils.RootChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    context: Context,
    config: CopyConfig,
) : AbstractShellOrchestrator(context, config) {
    companion object {
        private const val TAG = "RootCopyOrchestrator"

        // Shell 命令模板
        private const val CMD_MKDIR = "mkdir -p %s"
        private const val CMD_CP_DIR = "cp -p -v -R %s %s"
        private const val CMD_CP_FILE = "cp -p -v %s %s"
    }

    private lateinit var fileStatistics: FileStatistics
    private lateinit var progressTracker: ProgressTracker
    private lateinit var verificationManager: VerificationManager
    private var scheduler: com.example.tfgwj.performance.scheduler.AdaptivePermitScheduler? = null

    override suspend fun execute(
        androidDir: File,
        targetPackage: String,
        incrementalUpdate: Boolean,
        progressCallback: (progress: Int, processed: Int, total: Int, message: String, speed: Float) -> Unit,
    ): OrchestratorResult {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            Log.d(TAG, "🚀 Root 模式启动: ${androidDir.absolutePath} -> $targetPackage")

            val pkg = targetPackage
            val dir = androidDir
            this@RootCopyOrchestrator.targetPackage = pkg
            this@RootCopyOrchestrator.sourceAndroidDir = dir
            fileStatistics = FileStatistics(context)
            verificationManager = VerificationManager(context)

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

                // V10: 初始化自适应调度器
                dynamicPermits = config.rootConcurrentPermits.coerceAtLeast(1)
                scheduler = com.example.tfgwj.performance.scheduler.AdaptivePermitScheduler(
                    context = context,
                    basePermits = config.rootConcurrentPermits,
                    minPermits = 1,
                    maxPermits = 4 // Root 模式 Shell 竞争更严重，限制最大并发
                ).apply {
                    start { newPermits ->
                        dynamicPermits = newPermits.coerceAtLeast(1)
                        Log.d(TAG, "Scheduler: Root permits updated to $dynamicPermits")
                    }
                }

                // 3. 准备目标环境
                val targetBase = PathConstants.buildTargetDataPath(targetPackage)
                executeRootCommand(CMD_MKDIR.format(shellEscape(targetBase)))

                // 4. 执行递归复制（带看门狗）
                progressCallback(5, 0, totalFiles, "开始复制...", 0f)
                executeRootRecursiveCopy(androidDir, targetPackage)

                // 5. 验证结果
                progressCallback(config.progressPhaseVerifyingStart, totalFiles, totalFiles, "正在验证...", 0f)
                val verifiedCount = verificationManager.verify(androidDir, targetPackage, totalFiles, VerificationMode.ROOT)
                if (verifiedCount != totalFiles) {
                    return@withContext OrchestratorResult.Failure("Root 复制验证失败: $verifiedCount/$totalFiles")
                }

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
            } catch (e: CancellationException) {
                throw e
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
                    androidDir = sourceAndroidDir,
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

    override suspend fun executeCopyCommand(task: FileStatistics.CopyTask) {
        runCpCommand(task)
    }

    override fun executeMkdirCommand(path: String): String? {
        return executeRootCommand(CMD_MKDIR.format(shellEscape(path)))
    }

    override fun getStrategyType(): StrategyType = StrategyType.ROOT

    override fun cleanup() {
        try {
            scheduler?.stop()
            super.cleanup()
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
            watchdogJob = createWatchdog(updateInterval = 300, progressTracker = progressTracker)

            try {
                // 并行执行 cp 命令
                tasks.map { task ->
                    scope.async {
                        processWithAdaptiveLimit {
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
        // V20 收窄：目标必须落在 /storage/emulated/0/Android/(data|obb)/<targetPackage>/ 下，
        // 拒绝经 symlink 逃逸到其他包目录的写入（isSafeTargetPathForPackage 用 canonicalPath 解析）
        if (!isSafeTargetPathForPackage(task.targetDir, targetPackage)) {
            throw IllegalArgumentException("非法目标路径: ${task.targetDir}（不在目标包目录内）")
        }

        val sourcePath = task.sourceDir.canonicalPath
        val targetPath = File(task.targetDir).canonicalPath
        val sourceArg = shellEscape(sourcePath)
        val targetArg = shellEscape(targetPath)
        val cmd =
            if (task.isDirectory) {
                CMD_CP_DIR.format("$sourceArg/.", "$targetArg/")
            } else {
                CMD_CP_FILE.format(sourceArg, targetArg)
            }

        Log.v(TAG, "执行 CP: ${task.sourceDir.name} -> $task.targetDir")

        try {
            val process =
                ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true)
                    .start()
            activeProcesses.add(process)

            try {
                process.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (line.isNullOrEmpty()) continue

                        val current = progressCounter.incrementAndGet()
                        val fileName = extractFileNameFromCpOutput(line)

                        progressTracker.updateProgress(
                            processed = current,
                            message = fileName,
                            phase = TaskPhase.REPLACING,
                        )
                    }
                }

                val exitCode = process.waitFor()
                if (exitCode != 0) {
                    throw IllegalStateException("CP 命令退出码: $exitCode")
                }
            } finally {
                activeProcesses.remove(process)
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

}
