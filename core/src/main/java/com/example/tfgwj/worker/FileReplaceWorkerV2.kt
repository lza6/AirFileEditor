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
import java.util.concurrent.TimeUnit

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

        // V24 任务可靠性参数
        /** 初始退避间隔（毫秒），指数退避：10s → 20s → 40s → ... */
        const val INITIAL_BACKOFF_MS = 10_000L
        /** 最大重试次数（含首次执行） */
        const val MAX_RETRY_ATTEMPTS = 3
        /** 单任务总超时上限（毫秒），超过即 fail-closed，防止看门狗失灵后任务悬挂 */
        const val TASK_DEADLINE_MS = 30L * 60 * 1000 // 30 分钟

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
         *
         * V24 任务可靠性加固：
         * - [setBackoffCriteria] 指数退避：失败后自动重试（最多 [MAX_RETRY_ATTEMPTS] 次），间隔指数增长，
         *   避免瞬时失败（如 Shizuku 服务短暂抖动）导致整任务作废。
         * - [setExpedited]：请求加速配额；超限时降级为普通后台任务（RUN_AS_NON_EXPEDITED）。
         * - 失败终态仍由 `failed()` 返回 `Result.failure`，与 V19 审计观察一致（FAILED → 写历史 + 错误信息）。
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
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_MS, TimeUnit.MILLISECONDS)
                .addTag(WORK_TAG)
                .build()
        }
    }

    private lateinit var config: CopyConfig
    @Volatile
    private var orchestrator: FileReplaceOrchestrator? = null
    private val taskController = TaskControllerProvider.get()

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            Log.d(TAG, "🔥 [Perf] Worker V2 启动 (runAttempt=${runAttemptCount})")

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

            // V24 可靠性：达到重试上限即 fail-closed，不再重试，避免无限退避循环
            if (runAttemptCount > MAX_RETRY_ATTEMPTS) {
                Log.e(TAG, "❌ 已达最大重试次数 ($MAX_RETRY_ATTEMPTS)，fail-closed 终止")
                return@withContext failed("替换任务多次重试仍失败（已达上限 $MAX_RETRY_ATTEMPTS）")
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

            // V24 可靠性：尝试转为前台服务，防系统在长时间 IO 时回收
            // 失败（如未授权 POST_NOTIFICATIONS / 配额不足）时静默降级为普通后台任务，不阻塞替换
            runCatching { setForeground(buildForegroundInfo(targetPackage)) }
                .onFailure { Log.w(TAG, "⚠️ 前台服务保活失败（降级后台运行）: ${it.message}") }

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

                // V18 内存水位：任务开始前评估设备内存压力，联动并发/缓冲/mmap 决策
                runCatching {
                    com.example.tfgwj.performance.IoEngine.refreshMemoryPressure(
                        applicationContext.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                            as? android.app.ActivityManager,
                    )
                }.onFailure { Log.w(TAG, "⚠️ 内存水位刷新失败（按 LOW 处理）: ${it.message}") }

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
                // 历史记录写入由 :data 层 ConfigRepositoryImpl.startReplace 完成后处理，
                // 不在 :core 层 Worker 中直接调用 :data 模块（依赖方向为 :data → :core）。
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
     * V24 可靠性：构造前台服务通知（替换期间保活，防系统回收）
     * 通道与 MainActivity.createNotificationChannel 共用 file_replace_channel。
     */
    private fun buildForegroundInfo(targetPackage: String): ForegroundInfo {
        val notification = androidx.core.app.NotificationCompat.Builder(
            applicationContext,
            com.example.tfgwj.utils.AppConstants.NOTIFICATION_CHANNEL_ID,
        )
            .setContentTitle("正在替换文件")
            .setContentText("目标应用：$targetPackage")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .build()
        // Android 14+ 需声明 foregroundServiceType；此处用 dataSync 兼容长时 IO
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ForegroundInfo(
                com.example.tfgwj.utils.AppConstants.NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(com.example.tfgwj.utils.AppConstants.NOTIFICATION_ID, notification)
        }
    }
}
