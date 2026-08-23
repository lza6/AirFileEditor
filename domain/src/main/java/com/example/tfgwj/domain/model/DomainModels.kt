package com.example.tfgwj.domain.model

/**
 * 核心文件访问模式
 */
enum class AccessMode {
    ROOT,
    SHIZUKU,
    NATIVE,
    NONE
}

/**
 * 环境验证状态
 */
enum class EnvironmentStatus {
    UNKNOWN,
    CHECKING,
    VALID,
    INVALID
}

/**
 * 任务执行阶段（V13 收口：终态包含 CANCELLED，唯一权威状态源）
 */
enum class TaskPhase {
    IDLE,
    PREPARING,
    REPLACING,
    VERIFYING,
    COMPLETED,
    FAILURE,
    CANCELLED
}

/**
 * 权限状态汇总
 */
data class PermissionStatus(
    val hasStoragePermission: Boolean = false,
    val hasShizukuPermission: Boolean = false,
    val bestMode: AccessMode = AccessMode.NONE,
    val isShizukuAvailable: Boolean = false,
    val isShizukuServiceConnected: Boolean = false,
    val message: String = ""
)
