package com.example.tfgwj.ui.mvi

/**
 * V11.0.0 用户意图 (完整版)
 * 覆盖 MainActivity 所有用户交互
 *
 * V17 收口：删除无消费方的死 Intent（落入 else -> {} 空分支），
 * 保持 MVI 契约最小化。仅保留有真实实体逻辑的交互。
 */
sealed class ReplacingIntent {
    // 替换任务控制
    data class StartReplace(val sourcePath: String, val targetPackage: String) : ReplacingIntent()

    object CancelReplace : ReplacingIntent()

    object RetryReplace : ReplacingIntent()

    object DismissTaskResult : ReplacingIntent()

    object PauseReplace : ReplacingIntent()

    object ResumeReplace : ReplacingIntent()

    // 权限与模式
    object CheckEnvironment : ReplacingIntent()

    // 文件时间管理
    object RandomizeFileTime : ReplacingIntent()

    data class LockFileTime(val timestamp: Long) : ReplacingIntent()

    object UnlockFileTime : ReplacingIntent()

    // 小包管理
    object RefreshPatches : ReplacingIntent()

    data class SelectPatch(val version: String) : ReplacingIntent()

    // 日志操作
    object ClearLogs : ReplacingIntent()
}
