package com.example.tfgwj.ui.mvi

import com.example.tfgwj.utils.PermissionChecker

/**
 * V11.0.0 用户意图 (完整版)
 * 覆盖 MainActivity 所有用户交互
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
    data class UpdateMode(val mode: PermissionChecker.AccessMode) : ReplacingIntent()

    object RefreshEnvironment : ReplacingIntent()

    object RequestStoragePermission : ReplacingIntent()

    object RequestShizukuPermission : ReplacingIntent()

    // 主包管理
    data class SelectMainPack(val path: String) : ReplacingIntent()

    object ScanMainPacks : ReplacingIntent()

    object CheckEnvironment : ReplacingIntent()

    object LaunchGame : ReplacingIntent()

    object CleanEnvironment : ReplacingIntent()

    // 文件时间管理
    object RandomizeFileTime : ReplacingIntent()

    data class LockFileTime(val timestamp: Long) : ReplacingIntent()

    object UnlockFileTime : ReplacingIntent()

    object ApplyLockedTime : ReplacingIntent()

    // 小包管理
    object ScanArchives : ReplacingIntent()

    object RefreshPatches : ReplacingIntent()

    data class SelectPatch(val version: String) : ReplacingIntent()

    object ExtractAndUpdate : ReplacingIntent()

    // 日志操作
    object CopyLogs : ReplacingIntent()

    object ClearLogs : ReplacingIntent()

    // OTA 更新
    object CheckForUpdates : ReplacingIntent()

    data class InstallUpdate(val apkPath: String) : ReplacingIntent()
}
