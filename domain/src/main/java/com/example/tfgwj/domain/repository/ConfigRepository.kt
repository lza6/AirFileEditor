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
    fun getTaskProgress(): Flow<TaskProgress>

    // 包管理
    suspend fun scanMainPacks(): List<String>
    suspend fun scanPatchVersions(): List<com.example.tfgwj.domain.repository.PatchVersion>
    suspend fun scanArchives(): List<ArchiveInfo>
    suspend fun extractArchive(archivePath: String, password: String?, versionName: String?): Result<String>

    // 文件时间
    suspend fun randomizeTime(path: String): Result<Pair<Int, Long>>
    suspend fun setCustomTime(path: String, timestamp: Long): Result<Int>
    fun getFileTime(path: String): Long?
}

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
    val isReplacing: Boolean
)

data class PatchVersion(
    val version: String,
    val path: String,
    val size: Long,
    val fileCount: Int
)
