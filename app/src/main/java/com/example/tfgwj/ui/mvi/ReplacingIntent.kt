package com.example.tfgwj.ui.mvi

import com.example.tfgwj.utils.PermissionChecker

/**
 * V11.0.0 用户意图
 */
sealed class ReplacingIntent {
    data class StartReplace(val sourcePath: String, val targetPackage: String) : ReplacingIntent()
    object CancelReplace : ReplacingIntent()
    object RetryReplace : ReplacingIntent()
    data class UpdateMode(val mode: PermissionChecker.AccessMode) : ReplacingIntent()
    object RefreshEnvironment : ReplacingIntent()
}
