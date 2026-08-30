package com.example.tfgwj.data.repository

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.tfgwj.domain.model.*
import com.example.tfgwj.domain.repository.*
import com.example.tfgwj.manager.*
import com.example.tfgwj.shizuku.ShizukuManager
import com.example.tfgwj.utils.FileTimeModifier
import com.example.tfgwj.utils.PermissionChecker
import com.example.tfgwj.worker.FileReplaceWorkerV2
import com.example.tfgwj.worker.orchestrator.PathConstants
import com.example.tfgwj.manager.ArchiveScanner
import com.example.tfgwj.manager.ExtractManager
import com.example.tfgwj.manager.MainPackManager
import com.example.tfgwj.manager.PatchManager
import com.example.tfgwj.worker.TaskControllerProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 仓储层实现类 (企业级解耦核心)
 * 整合底层所有 Manager 逻辑，提供纯净的 Domain 接口
 */
class ConfigRepositoryImpl(
    private val context: Context,
    private val shizukuManager: ShizukuManager,
    private val patchManager: PatchManager,
    private val mainPackManager: MainPackManager
) : ConfigRepository {

    // V19 审计写盘专用作用域：与主流程解耦，观察任务终态写历史；进程被杀时静默放弃
    private val auditScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    private val _permissionStatus = MutableStateFlow(PermissionStatus())
    private val taskController: com.example.tfgwj.domain.repository.TaskController = TaskControllerProvider.get()

    override suspend fun checkEnvironment(packageName: String, forceRefresh: Boolean): PermissionStatus {
        val result =
            PermissionChecker.checkPermissionAccess(
                packageName = packageName,
                stopAppFirst = forceRefresh,
                context = context,
                forceRefresh = forceRefresh,
            )
        val status = PermissionStatus(
            hasStoragePermission = true,
            hasShizukuPermission = shizukuManager.isAuthorized.value,
            bestMode = when (result.bestMode) {
                PermissionChecker.AccessMode.ROOT -> AccessMode.ROOT
                PermissionChecker.AccessMode.SHIZUKU -> AccessMode.SHIZUKU
                PermissionChecker.AccessMode.NATIVE -> AccessMode.NATIVE
                else -> AccessMode.NONE
            },
            isShizukuAvailable = shizukuManager.isAvailable.value,
            isShizukuServiceConnected = shizukuManager.isServiceConnected.value,
            message = result.message
        )
        _permissionStatus.value = status
        return status
    }

    override fun getPermissionStatus(): Flow<PermissionStatus> = _permissionStatus.asStateFlow()

    override suspend fun startReplace(sourcePath: String, targetPackage: String, incremental: Boolean): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
            val workRequest = FileReplaceWorkerV2.createWorkRequestV2(sourcePath, targetPackage, incremental)
            WorkManager.getInstance(context).enqueueUniqueWork(
                FileReplaceWorkerV2.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                workRequest,
            )
            // V19: 一次替换 = 一次审计记录。工作线程返回后，用独立 auditScope 观察任务终态
            // 并在其真正结束时（异步）写入替换历史，不阻塞 startReplace 的返回。
            launchAudit(sourcePath, targetPackage, workRequest)
            Result.success(workRequest.id.toString())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * V19 审计闭环：启动独立协程监听 [workRequest] 终态并把结果写入替换历史。
     * 使用独立的 [auditScope]（SupervisorJob + Dispatchers.IO），不阻塞 startReplace 返回；
     * 进程被杀/协程取消时静默放弃——历史不完整由下一次替换补齐，不影响替换本身。
     */
    private fun launchAudit(sourcePath: String, targetPackage: String, workRequest: androidx.work.OneTimeWorkRequest) {
        auditScope.launch {
            val workManager = WorkManager.getInstance(context)
            val historyManager = com.example.tfgwj.data.ReplaceHistoryManager.getInstance(context)
            val now = System.currentTimeMillis()

            // 终态：SUCCEEDED / FAILED / CANCELLED
            val info: WorkInfo = try {
                workManager.getWorkInfoByIdFlow(workRequest.id)
                    .first { it.state.isFinished }
            } catch (e: Exception) {
                // 观察协程被取消或 WorkManager 不可用 → 跳过本次历史写入（不影响替换本身）
                return@launch
            }

            val succeeded = info.state == WorkInfo.State.SUCCEEDED
            // Worker 成功时将 processed/total/verified/mode/backup 写进 outputData
            val processedCount = info.outputData.getInt(FileReplaceWorkerV2.KEY_PROCESSED, 0)
            val totalFiles = info.outputData.getInt(FileReplaceWorkerV2.KEY_TOTAL, 0)
            // 成功数优先取 verified（Worker 仅在验证通过后才 Success），缺失则回退 processed
            val verifiedCount = info.outputData.getInt(FileReplaceWorkerV2.KEY_VERIFIED_FILES, -1)
            val successCount = if (verifiedCount >= 0) verifiedCount else processedCount
            val backupPath = info.outputData.getString(FileReplaceWorkerV2.KEY_BACKUP_PATH)
            val errorMessage = info.outputData.getString(FileReplaceWorkerV2.KEY_ERROR_MESSAGE)

            runCatching {
                historyManager.addHistory(
                    buildHistoryItem(
                        sourcePath = sourcePath,
                        targetPackage = targetPackage,
                        now = now,
                        succeeded = succeeded,
                        processedCount = successCount,
                        totalFiles = totalFiles,
                        backupPath = backupPath,
                        errorMessage = errorMessage,
                    ),
                )
            }.onFailure { failure ->
                com.example.tfgwj.utils.AppLogger.w("ConfigRepositoryImpl", "写入替换历史失败: ${failure.message}")
            }
        }
    }

    /**
     * V19 审计记录映射（纯函数，可 JVM 单测）：
     * 把 Worker 终态输出映射为替换历史条目。成功写入 processed 计数、失败标记 1 个失败并附错误信息。
     */
    internal fun buildHistoryItem(
        sourcePath: String,
        targetPackage: String,
        now: Long,
        succeeded: Boolean,
        processedCount: Int,
        totalFiles: Int,
        backupPath: String?,
        errorMessage: String?,
    ): com.example.tfgwj.data.ReplaceHistoryItem {
        return com.example.tfgwj.data.ReplaceHistoryItem(
            timestamp = now,
            packageName = targetPackage,
            sourcePath = sourcePath,
            targetPath = PathConstants.buildTargetDataPath(targetPackage),
            totalFiles = totalFiles,
            successCount = processedCount,
            failedCount = if (succeeded) 0 else 1,
            errors = if (succeeded) emptyList() else listOfNotNull(errorMessage ?: "任务未成功完成", "WorkState=非成功"),
            backupPath = backupPath,
        )
    }

    override suspend fun cancelReplace(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(FileReplaceWorkerV2.UNIQUE_WORK_NAME)
            taskController.cancel()
        }
    }

    override suspend fun dismissReplaceResult(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { taskController.reset() }
    }

    override fun getTaskProgress(): Flow<TaskProgress> = taskController.state.map {
        TaskProgress(
            processed = it.processed,
            total = it.total,
            progress = it.progress,
            speed = it.speed,
            currentFile = it.currentFile,
            phase = it.phase,
            isReplacing = it.isReplacing,
            errorMessage = it.errorMessage,
        )
    }

    override fun getReplaceHistory(): Flow<List<com.example.tfgwj.domain.repository.ReplaceHistoryItem>> =
        com.example.tfgwj.data.ReplaceHistoryManager.getInstance(context).history.map { items ->
            items.map {
                com.example.tfgwj.domain.repository.ReplaceHistoryItem(
                    timestamp = it.timestamp,
                    packageName = it.packageName,
                    sourcePath = it.sourcePath,
                    targetPath = it.targetPath,
                    totalFiles = it.totalFiles,
                    successCount = it.successCount,
                    failedCount = it.failedCount,
                    errors = it.errors,
                    backupPath = it.backupPath,
                )
            }
        }

    override suspend fun restoreFromBackup(backupPath: String, targetPackage: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(PathConstants.isValidPackageName(targetPackage)) { "非法目标包名: $targetPackage" }
                val backupManager = com.example.tfgwj.manager.BackupManager.getInstance(context)
                val targetPath = PathConstants.buildTargetDataPath(targetPackage)
                val ok = backupManager.restoreBackup(backupPath, targetPath)
                if (!ok) error("恢复失败: $backupPath -> $targetPath")

                // 恢复本身也写审计记录
                val historyManager = com.example.tfgwj.data.ReplaceHistoryManager.getInstance(context)
                historyManager.addHistory(
                    com.example.tfgwj.data.ReplaceHistoryItem(
                        timestamp = System.currentTimeMillis(),
                        packageName = targetPackage,
                        sourcePath = backupPath,
                        targetPath = targetPath,
                        totalFiles = 0,
                        successCount = 0,
                        failedCount = 0,
                        errors = emptyList(),
                        backupPath = backupPath,
                    ),
                )
            }
        }

    override suspend fun scanMainPacks(targetPackage: String): List<String> {
        mainPackManager.scanMainPacks(targetPackage)
        return mainPackManager.mainPacks.value.map { it.path }
    }

    override suspend fun scanPatchVersions(): List<com.example.tfgwj.domain.repository.PatchVersion> {
        return patchManager.scanPatchVersions().map {
            com.example.tfgwj.domain.repository.PatchVersion(
                version = it.name,
                path = it.path,
                size = it.sizeBytes,
                fileCount = it.fileCount
            )
        }
    }

    override suspend fun scanArchives(): List<ArchiveInfo> {
        return ArchiveScanner.getInstance().scanArchives().map {
            ArchiveInfo(it.name, it.path, it.sizeText, it.sizeBytes)
        }
    }

    override suspend fun extractArchive(archivePath: String, password: String?, versionName: String?): Result<String> {
        return try {
            val result = ExtractManager.getInstance().extractToCache(archivePath, password, versionName)
            if (result.success) Result.success(result.outputPath)
            else Result.failure(Exception(result.errorMessage))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun randomizeTime(path: String): Result<Pair<Int, Long>> {
        return try {
            val result = FileTimeModifier.randomizeTime(path)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setCustomTime(path: String, timestamp: Long): Result<Int> {
        return try {
            val (count, _) = FileTimeModifier.setCustomTime(path, timestamp)
            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getFileTime(path: String): Long? = FileTimeModifier.getFileTime(path)
}
