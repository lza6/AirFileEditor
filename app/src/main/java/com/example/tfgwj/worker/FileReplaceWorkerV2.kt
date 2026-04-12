package com.example.tfgwj.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.tfgwj.manager.ReplaceProgressManager
import com.example.tfgwj.manager.StealthManager
import com.example.tfgwj.worker.orchestrator.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_VERIFIED_FILES = "verified_files"
        const val KEY_MODE = "mode"

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
                .build()
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var config: CopyConfig
    private lateinit var orchestrator: FileReplaceOrchestrator

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            Log.d(TAG, "🔥 [Perf] Worker V2 启动")

            val sourcePath = inputData.getString(KEY_SOURCE_PATH) ?: return@withContext Result.failure()
            val targetPackage = inputData.getString(KEY_TARGET_PACKAGE) ?: return@withContext Result.failure()
            val incrementalUpdate = inputData.getBoolean(KEY_INCREMENTAL_UPDATE, false)

            Log.d(TAG, "========== V2 文件替换开始 ==========")
            Log.d(TAG, "源路径: $sourcePath")
            Log.d(TAG, "目标包名: $targetPackage")
            Log.d(TAG, "增量更新: $incrementalUpdate")

            // 检查取消状态
            if (isStopped) {
                Log.d(TAG, "⚠️ 任务已被取消")
                return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to "任务已取消"))
            }

            try {
                // 初始化配置
                config = CopyConfig.getDefault(applicationContext)

                // 验证源目录
                val sourceDir = File(sourcePath)
                val androidDir = File(sourceDir, "Android")
                if (!androidDir.exists()) {
                    Log.e(TAG, "❌ Android 目录不存在: $sourcePath/Android")
                    return@withContext Result.failure(
                        workDataOf(KEY_ERROR_MESSAGE to "源文件夹中没有 Android 目录"),
                    )
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
                ReplaceProgressManager.reset()
                ReplaceProgressManager.startMeasure()

                // 执行替换（使用 Orchestrator）
                val result =
                    orchestrator.execute(
                        androidDir = androidDir,
                        targetPackage = targetPackage,
                        incrementalUpdate = incrementalUpdate,
                    ) { progress, processed, total, message, speed ->
                        // 进度回调（双级节流已由 ProgressTracker 处理）
                        // WorkManager 进度更新（主线程）
                        ReplaceProgressManager.updateState(
                            processed = processed,
                            total = total,
                            currentFile = message,
                            progress = progress,
                            speed = speed,
                            phase = "REPLACING",
                        )
                    }

                // 处理结果
                when (result) {
                    is OrchestratorResult.Success -> {
                        Log.i(TAG, "✅ V2 替换成功: ${result.processedCount}/${result.totalFiles}")

                        // 如果需要，执行隐匿协议
                        if (inputData.getBoolean(KEY_ENABLE_STEALTH, false)) {
                            Log.d(TAG, "🕵️‍♂️ 触发 Phantom Stealth")
                            delay(500)
                            StealthManager.execute(applicationContext)
                        }

                        Result.success(
                            workDataOf(
                                KEY_PROCESSED to result.processedCount,
                                KEY_TOTAL to result.totalFiles,
                                KEY_VERIFIED_FILES to result.verifiedCount.toString(),
                                KEY_MODE to (result.metadata["mode"] ?: "V2"),
                            ),
                        )
                    }
                    is OrchestratorResult.Failure -> {
                        Log.e(TAG, "❌ V2 替换失败: ${result.message}", result.cause)
                        Result.failure(
                            workDataOf(KEY_ERROR_MESSAGE to result.message),
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ V2 执行异常", e)
                Result.failure(workDataOf(KEY_ERROR_MESSAGE to "执行异常: ${e.message}"))
            } finally {
                orchestrator.cleanup()
                Log.d(TAG, "========== V2 替换结束 ==========")
            }
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
        // WorkManager 进度更新（主线程）
        withContext(Dispatchers.Main) {
            setProgressAsync(
                workDataOf(
                    KEY_PROGRESS to progress,
                    KEY_PROCESSED to processed,
                    KEY_TOTAL to total,
                    KEY_CURRENT_FILE to message,
                    "speed" to speed,
                ),
            )
        }

        // ReplaceProgressManager 实时更新
        ReplaceProgressManager.updateState(
            processed = processed,
            total = total,
            currentFile = message,
            progress = progress,
            speed = speed,
            phase = "REPLACING",
        )
    }
}
