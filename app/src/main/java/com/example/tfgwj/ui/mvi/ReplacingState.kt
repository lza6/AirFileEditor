package com.example.tfgwj.ui.mvi

import com.example.tfgwj.domain.model.AccessMode
import com.example.tfgwj.domain.model.EnvironmentStatus
import com.example.tfgwj.domain.model.TaskPhase
import com.example.tfgwj.utils.PermissionChecker

/**
 * V11.0.0 完整应用状态模型
 * 整合 MainActivity 所有状态
 */
data class ReplacingState(
    // 替换任务状态
    val processedFiles: Int = 0,
    val totalFiles: Int = 0,
    val progress: Int = 0,
    val speedMBps: Float = 0f,
    val currentFileName: String = "",
    val phase: TaskPhase = TaskPhase.IDLE,
    val isReplacing: Boolean = false,
    val isPaused: Boolean = false,
    val errorMessage: String? = null,

    // APM 指标 (V10)
    val ioWaitMs: Long = 0,
    val ipcLatencyMs: Long = 0,
    val memoryUsagePercent: Double = 0.0,
    val activePermits: Int = 0,

    // 权限与环境
    val currentMode: PermissionChecker.AccessMode = PermissionChecker.AccessMode.NONE,
    val hasStoragePermission: Boolean = false,
    val hasShizukuPermission: Boolean = false,
    val environmentStatus: EnvironmentStatus = EnvironmentStatus.UNKNOWN,

    // 主包状态
    val selectedMainPackPath: String? = null,
    val mainPackAppName: String? = null,
    val mainPackIcon: Any? = null,
    val targetPackage: String = "",
    val availableMainPacks: List<String> = emptyList(),

    // 文件时间
    val lockedTime: Long? = null,
    val currentFileTime: Long? = null,

    // 小包状态
    val patchVersions: List<PatchVersion> = emptyList(),
    val selectedPatchVersion: String? = null,
    val isScanning: Boolean = false,

    // 日志
    val logContent: String = "",
    val logSize: String = "0 KB",

    // OTA 更新
    val hasUpdate: Boolean = false,
    val updateVersion: String? = null,
    val updateDownloadProgress: Int = 0
)

data class PatchVersion(
    val version: String,
    val path: String,
    val size: Long,
    val fileCount: Int
)
