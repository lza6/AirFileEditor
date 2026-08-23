package com.example.tfgwj.domain.repository

import com.example.tfgwj.domain.model.*
import kotlinx.coroutines.flow.Flow

/**
 * 配置与文件操作仓储接口
 */
interface ConfigRepository {
    // 环境相关
    suspend fun checkEnvironment(packageName: String, forceRefresh: Boolean): PermissionStatus
    fun getPermissionStatus(): Flow<PermissionStatus>

    // 任务相关
    suspend fun startReplace(sourcePath: String, targetPackage: String, incremental: Boolean): Result<String>
    suspend fun cancelReplace(): Result<Unit>
    suspend fun dismissReplaceResult(): Result<Unit>
    fun getTaskProgress(): Flow<TaskProgress>

    // 备份与历史（事务闭环）
    fun getReplaceHistory(): Flow<List<ReplaceHistoryItem>>
    suspend fun restoreFromBackup(backupPath: String, targetPackage: String): Result<Unit>

    // 包管理
    suspend fun scanMainPacks(targetPackage: String): List<String>
    suspend fun scanPatchVersions(): List<com.example.tfgwj.domain.repository.PatchVersion>
    suspend fun scanArchives(): List<ArchiveInfo>
    suspend fun extractArchive(archivePath: String, password: String?, versionName: String?): Result<String>

    // 文件时间
    suspend fun randomizeTime(path: String): Result<Pair<Int, Long>>
    suspend fun setCustomTime(path: String, timestamp: Long): Result<Int>
    fun getFileTime(path: String): Long?
}

/**
 * 替换历史项（与 data 层 ReplaceHistoryItem 对齐的领域模型）
 */
data class ReplaceHistoryItem(
    val timestamp: Long,
    val packageName: String,
    val sourcePath: String,
    val targetPath: String,
    val totalFiles: Int,
    val successCount: Int,
    val failedCount: Int,
    val errors: List<String>,
    val backupPath: String? = null,
)

data class ArchiveInfo(
    val name: String,
    val path: String,
    val sizeText: String,
    val sizeBytes: Long
)

data class TaskProgress(
    val processed: Int,
    val total: Int,
    val progress: Int,
    val speed: Float,
    val currentFile: String,
    val phase: TaskPhase,
    val isReplacing: Boolean,
    val errorMessage: String? = null,
)

data class PatchVersion(
    val version: String,
    val path: String,
    val size: Long,
    val fileCount: Int
)
