package com.example.tfgwj.ui.mvi

import com.example.tfgwj.utils.PermissionChecker

/**
 * V11.0.0 Replacing 模块状态模型
 */
data class ReplacingState(
    val processedFiles: Int = 0,
    val totalFiles: Int = 0,
    val progress: Int = 0,
    val speedMBps: Float = 0f,
    val currentFileName: String = "",
    val phase: String = "IDLE", // IDLE, PREPARING, REPLACING, VERIFYING, COMPLETED, FAILURE
    val isReplacing: Boolean = false,
    val errorMessage: String? = null,

    // APM 指标 (V10 实装数据)
    val ioWaitMs: Long = 0,
    val ipcLatencyMs: Long = 0,
    val memoryUsagePercent: Double = 0.0,
    val activePermits: Int = 0,

    // 环境状态
    val currentMode: PermissionChecker.AccessMode = PermissionChecker.AccessMode.NONE,
    val targetPackage: String = ""
)
