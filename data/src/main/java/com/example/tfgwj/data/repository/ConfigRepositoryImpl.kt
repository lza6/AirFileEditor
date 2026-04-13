package com.example.tfgwj.data.repository

import android.content.Context
import com.example.tfgwj.domain.model.*
import com.example.tfgwj.domain.repository.*
import com.example.tfgwj.manager.*
import com.example.tfgwj.shizuku.ShizukuManager
import com.example.tfgwj.utils.FileTimeModifier
import com.example.tfgwj.utils.PermissionChecker
import com.example.tfgwj.worker.FileReplaceWorkerV2
import com.example.tfgwj.manager.ArchiveScanner
import com.example.tfgwj.manager.ExtractManager
import com.example.tfgwj.manager.ReplaceProgressManager
import com.example.tfgwj.manager.MainPackManager
import com.example.tfgwj.manager.PatchManager
import kotlinx.coroutines.flow.*
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

    private val _permissionStatus = MutableStateFlow(PermissionStatus())

    override suspend fun checkEnvironment(packageName: String, forceRefresh: Boolean): PermissionStatus {
        val result = PermissionChecker.checkPermissionAccess(packageName, forceRefresh, context)
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
        return try {
            val workRequest = FileReplaceWorkerV2.createWorkRequestV2(sourcePath, targetPackage, incremental)
            androidx.work.WorkManager.getInstance(context).enqueue(workRequest)
            Result.success(workRequest.id.toString())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getTaskProgress(): Flow<TaskProgress> = ReplaceProgressManager.progressState.map {
        TaskProgress(
            processed = it.processed,
            total = it.total,
            progress = it.progress,
            speed = it.speed,
            currentFile = it.currentFile,
            phase = when (it.phase) {
                "IDLE" -> TaskPhase.IDLE
                "PREPARING" -> TaskPhase.PREPARING
                "REPLACING" -> TaskPhase.REPLACING
                "VERIFYING" -> TaskPhase.VERIFYING
                "COMPLETED" -> TaskPhase.COMPLETED
                "FAILURE" -> TaskPhase.FAILURE
                else -> TaskPhase.IDLE
            },
            isReplacing = it.isReplacing
        )
    }

    override suspend fun scanMainPacks(): List<String> {
        mainPackManager.scanMainPacks()
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
