package com.example.tfgwj.ui

import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.lifecycleScope
import com.example.tfgwj.MainActivity
import com.example.tfgwj.utils.AppLogger
import com.example.tfgwj.utils.PermissionChecker
import com.example.tfgwj.utils.PermissionManager
import kotlinx.coroutines.launch

/**
 * 权限控制器 — 从 MainActivity 提取的权限检测与请求逻辑
 */
class PermissionController(
    private val activity: MainActivity,
    private val permissionManager: PermissionManager,
    private val manageStorageLauncher: ActivityResultLauncher<android.content.Intent>,
    private val storagePermissionLauncher: ActivityResultLauncher<Array<String>>,
) {
    fun checkAllPermissions() {
        activity.lifecycleScope.launch {
            val status = permissionManager.checkAllPermissions()
            if (status.hasManageStorage) AppLogger.reInitAfterPermission(activity)
        }
    }

    fun requestPermissions() {
        activity.lifecycleScope.launch {
            val status = permissionManager.checkAllPermissions()
            if (!status.hasManageStorage) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    permissionManager.requestManageStoragePermission(activity, manageStorageLauncher)
                } else {
                    permissionManager.requestStoragePermission(storagePermissionLauncher)
                }
            } else if (
                !status.hasShizukuPermission &&
                status.availableModes.contains(PermissionChecker.AccessMode.SHIZUKU)
            ) {
                // 与 PermissionCard 的"授权 Shizuku"按钮文案同源：availableModes 含 SHIZUKU
                // 即可发起授权；不能只看 bestMode==SHIZUKU——未连接服务时 bestMode 恒为 NONE，
                // 会导致按钮永远无响应。
                permissionManager.requestShizukuPermission { if (it) checkAllPermissions() }
            }
        }
    }
}
