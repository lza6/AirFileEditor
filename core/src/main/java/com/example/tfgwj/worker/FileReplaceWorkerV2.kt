package com.example.tfgwj.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.tfgwj.domain.model.TaskPhase
import com.example.tfgwj.manager.StealthManager
import com.example.tfgwj.worker.orchestrator.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * FileReplaceWorker V2 (V8.0.0 架构演进)
 *
 * 模块化重构版本，使用 Orchestrator 模式：
 * - RootCopyOrchestrator
 * - ShizukuCopyOrchestrator
 * - NormalCopyOrchestrator
 *
 * 核心改进：
 * 1. 职责分离：复制逻辑、进度跟踪、验证逻辑完全解耦
 * 2. 配置外化：使用 CopyConfig 统一管理参数
 * 3. 路径常量：使用 PathConstants 消除硬编码
 * 4. 向后兼容：保留 V1 接口，通过版本标志切换
 *
 * @version V8.0.0 - Architecture Evolution
 */
class FileReplaceWorkerV2(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    companion object {
        private const val TAG = "FileReplaceWorkerV2"
        const val WORK_TAG = "file_replace"
        const val UNIQUE_WORK_NAME = "file_replace_v2"

        // 输入参数键
        const val KEY_SOURCE_PATH = "source_path"
        const val KEY_TARGET_PACKAGE = "target_package"
        const val KEY_INCREMENTAL_UPDATE = "incremental_update"
        const val KEY_ENABLE_STEALTH = "enable_stealth"
        const val KEY_VERSION = "version" // V1 或 V2

        // 进度键
        const val KEY_PROGRESS = "progress"
        const val KEY_CURRENT_FILE = "current_file"
        const val KEY_PROCESSED = "processed"
        const val KEY_TOTAL = "total"
        const val KEY_FAILED_FILES = "failed_files"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_VERIFIED_FILES = "verified_files"
        const val KEY_MODE = "mode"
        const val KEY_BACKUP_PATH = "backup_path"

        /**
         * 创建工作请求（V2 版本）
         */
        fun createWorkRequestV2(
            sourcePath: String,
            targetPackage: String,
            incrementalUpdate: Boolean = false,
            enableStealth: Boolean = false,
        ): OneTimeWorkRequest {
            val inputData =
                Data.Builder()
                    .putString(KEY_SOURCE_PATH, sourcePath)
                    .putString(KEY_TARGET_PACKAGE, targetPackage)
                    .putBoolean(KEY_INCREMENTAL_UPDATE, incrementalUpdate)
                    .putBoolean(KEY_ENABLE_STEALTH, enableStealth)
                    .putInt(KEY_VERSION, 2)
                    .build()

            return OneTimeWorkRequestBuilder<FileReplaceWorkerV2>()
                .setInputData(inputData)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(WORK_TAG)
                .build()
        }
    }

    private lateinit var config: CopyConfig
    @Volatile
    private var orchestrator: FileReplaceOrchestrator? = null
    private val taskController = TaskControllerImpl()

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            Log.d(TAG, "🔥 [Perf] Worker V2 启动")

            val sourcePath = inputData.getString(KEY_SOURCE_PATH)
                ?: return@withContext failed("缺少源路径")
            val targetPackage = inputData.getString(KEY_TARGET_PACKAGE)
                ?: return@withContext failed("缺少目标包名")
            val incrementalUpdate = inputData.getBoolean(KEY_INCREMENTAL_UPDATE, false)

            if (sourcePath.isBlank()) {
                return@withContext failed("源路径不能为空")
            }

            if (!PathConstants.isValidPackageName(targetPackage)) {
                Log.e(TAG, "❌ 非法包名: $targetPackage")
                return@withContext failed("非法目标包名")
            }

            val taskId = id.toString()
            Log.d(TAG, "========== V2 文件替换开始 ==========")
            Log.d(TAG, "源路径: $sourcePath")
            Log.d(TAG, "目标包名: $targetPackage")
            Log.d(TAG, "增量更新: $incrementalUpdate")

            // 检查取消状态
            if (isStopped) {
                Log.d(TAG, "⚠️ 任务已被取消")
                taskController.cancel()
                return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to "任务已取消"))
            }

            var processedFiles = 0
            var completed = false
            var backupPath: String? = null
            com.example.tfgwj.performance.PerformanceMonitor.startTask(taskId)
            try {
                // 初始化配置
                config = CopyConfig.getDefault(applicationContext)

                // 验证源目录
                val sourceDir = File(sourcePath)
                val androidDir = File(sourceDir, "Android")
                if (!sourceDir.isDirectory || !androidDir.isDirectory) {
                    Log.e(TAG, "❌ Android 目录不存在: $sourcePath/Android")
                    return@withContext failed("源文件夹中没有 Android 目录")
                }

                // 检测环境并创建对应的 Orchestrator
                val envStatus = com.example.tfgwj.utils.PermissionChecker.checkPermissionAccess(targetPackage, false)
                val bestMode = envStatus.bestMode

                Log.d(TAG, "📦 最佳模式: $bestMode")

                orchestrator =
                    when (bestMode) {
                        com.example.tfgwj.utils.PermissionChecker.AccessMode.ROOT ->
                            RootCopyOrchestrator(applicationContext, config)
                        com.example.tfgwj.utils.PermissionChecker.AccessMode.SHIZUKU ->
                            ShizukuCopyOrchestrator(
                                applicationContext,
                                config,
                                com.example.tfgwj.shizuku.ShizukuManager.getInstance(applicationContext),
                            )
                        com.example.tfgwj.utils.PermissionChecker.AccessMode.NATIVE ->
                            NormalCopyOrchestrator(applicationContext, config)
                        else -> {
                            Log.w(TAG, "⚠️ 无可用模式，尝试 Native 降级")
                            NormalCopyOrchestrator(applicationContext, config)
                        }
                    }

                // 重置进度管理器
                taskController.reset()
                taskController.startMeasure()

                // 任务前快照：对目标 data/obb 目录做备份，失败可恢复
                val backupManager = com.example.tfgwj.manager.BackupManager.getInstance(applicationContext)
                backupPath =
                    runCatching {
                        var dataBackup: String? = null
                        var obbBackup: String? = null
                        val dataTarget = java.io.File(PathConstants.buildTargetDataPath(targetPackage))
                        if (dataTarget.exists()) {
                            dataBackup = backupManager.createBackup(dataTarget.absolutePath, targetPackage)
                        }
                        val obbTarget = java.io.File(PathConstants.buildTargetObbPath(targetPackage))
                        if (obbTarget.exists()) {
                            obbBackup = backupManager.createBackup(obbTarget.absolutePath, "${targetPackage}_obb")
                        }
                        // 优先返回 data 备份作为恢复入口
                        dataBackup ?: obbBackup
                    }.onFailure { Log.w(TAG, "⚠️ 任务前快照失败（继续替换但不保证可恢复）", it) }
                        .getOrNull()

                // 执行替换（使用 Orchestrator）
                val result =
                    requireNotNull(orchestrator).execute(
                        androidDir = androidDir,
                        targetPackage = targetPackage,
                        incrementalUpdate = incrementalUpdate,
                    ) { progress, processed, total, message, speed ->
                        if (isStopped) throw CancellationException("替换任务已取消")
                        setProgressAsync(
                            workDataOf(
                                KEY_PROGRESS to progress,
                                KEY_PROCESSED to processed,
                                KEY_TOTAL to total,
                                KEY_CURRENT_FILE to message,
                                KEY_MODE to "V2",
                            ),
                        )
                        // 进度回调（双级节流已由 ProgressTracker 处理）
                        // WorkManager 进度更新（主线程）
                        taskController.updateState(
                            processed = processed,
                            total = total,
                            currentFile = message,
                            progress = progress,
                            speed = speed,
                            phase = TaskPhase.REPLACING,
                        )
                    }

                // 处理结果
                val workerResult = when (result) {
                    is OrchestratorResult.Success -> {
                        Log.i(TAG, "✅ V2 替换成功: ${result.processedCount}/${result.totalFiles}")

                        // 如果需要，执行隐匿协议
                        if (inputData.getBoolean(KEY_ENABLE_STEALTH, false)) {
                            Log.d(TAG, "🕵️‍♂️ 触发 Phantom Stealth")
                            delay(500)
                            StealthManager.execute(applicationContext)
                        }

                        taskController.finish()
                        processedFiles = result.processedCount
                        completed = true
                        Result.success(
                            workDataOf(
                                KEY_PROCESSED to result.processedCount,
                                KEY_TOTAL to result.totalFiles,
                                KEY_VERIFIED_FILES to result.verifiedCount,
                                KEY_MODE to (result.metadata["mode"] ?: "V2"),
                                KEY_BACKUP_PATH to (backupPath ?: ""),
                            ),
                        )
                    }
                    is OrchestratorResult.Failure -> {
                        Log.e(TAG, "❌ V2 替换失败: ${result.message}", result.cause)
                        failed(result.message)
                    }
                }
                workerResult
            } catch (e: CancellationException) {
                Log.i(TAG, "替换任务已取消")
                taskController.cancel()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "❌ V2 执行异常", e)
                failed("执行异常: ${e.message ?: "未知错误"}")
            } finally {
                // 审计闭环：历史记录写入由 :data 层 ConfigRepositoryImpl.startReplace 完成后处理，
                // 不在 :core 层 Worker 中直接调用 :data 模块（依赖方向为 :data → :core）。
                // runCatching {
                //     val historyManager = com.example.tfgwj.data.ReplaceHistoryManager.getInstance(applicationContext)
                //     val historyItem =
                //         com.example.tfgwj.data.ReplaceHistoryItem(
                //             timestamp = System.currentTimeMillis(),
                //             packageName = targetPackage,
                //             sourcePath = sourcePath,
                //             targetPath = PathConstants.buildTargetDataPath(targetPackage),
                //             totalFiles = processedFiles,
                //             successCount = if (completed) processedFiles else 0,
                //             failedCount = if (completed) 0 else 1,
                //             errors = if (completed) emptyList<String>() else listOf(taskController.state.value.errorMessage ?: "任务未完成"),
                //             backupPath = backupPath,
                //         )
                //     historyManager.addHistory(historyItem)
                // }.onFailure { failure: Throwable -> Log.w(TAG, "⚠️ 写入替换历史失败", failure) }
                Log.d(TAG, "替换任务结束: package=$targetPackage, source=$sourcePath, processed=$processedFiles, completed=$completed, backup=$backupPath")

                com.example.tfgwj.performance.PerformanceMonitor.endTask(
                    taskId = taskId,
                    success = completed,
                    filesProcessed = processedFiles,
                )
                orchestrator?.cleanup()
                Log.d(TAG, "========== V2 替换结束 ==========")
            }
        }

    private fun failed(message: String): Result {
        taskController.fail(message)
        return Result.failure(workDataOf(KEY_ERROR_MESSAGE to message))
    }

    /**
     * 更新进度状态（双级节流）
     * 保持与 V1 兼容的进度上报机制
     */
    private suspend fun updateProgressState(
        progress: Int,
        processed: Int,
        total: Int,
        message: String,
        speed: Float,
    ) {
        // WorkManager 进度更新（setProgressAsync 内部已切线程，无需再包 Main dispatcher）
        setProgressAsync(
            workDataOf(
                KEY_PROGRESS to progress,
                KEY_PROCESSED to processed,
                KEY_TOTAL to total,
                KEY_CURRENT_FILE to message,
                "speed" to speed,
            ),
        )

        // TaskController 实时更新
        taskController.updateState(
            processed = processed,
            total = total,
            currentFile = message,
            progress = progress,
            speed = speed,
            phase = TaskPhase.REPLACING,
        )
    }
}
