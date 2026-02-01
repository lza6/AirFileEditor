package com.example.tfgwj.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import com.example.tfgwj.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 权限管理器
 * 统一管理应用所需的各种权限
 */
class PermissionManager(private val context: Context) {
    
    companion object {
        private const val TAG = "PermissionManager"
        
        // 权限请求码
        const val REQUEST_STORAGE = 1001
        const val REQUEST_MANAGE_STORAGE = 1002
    }
    
    /**
     * 权限状态
     */
    data class PermissionStatus(
        val hasStoragePermission: Boolean = false,      // 基本存储权限
        val hasManageStorage: Boolean = false,          // 所有文件访问权限
        val needsShizuku: Boolean = false,              // 是否需要 Shizuku
        val hasShizukuPermission: Boolean = false,      // Shizuku 已授权
        val isShizukuAvailable: Boolean = false,        // Shizuku 可用
        val isShizukuServiceConnected: Boolean = false, // Shizuku UserService 已连接
        val canAccessPrivateDir: Boolean = false,       // 可访问私有目录
        val statusMessage: String = ""                  // 状态描述
    )
    
    private val _permissionStatus = MutableStateFlow(PermissionStatus())
    val permissionStatus: StateFlow<PermissionStatus> = _permissionStatus.asStateFlow()
    
    private val shizukuManager = ShizukuManager.getInstance(context)
    
    /**
     * 检查所有权限状态
     */
    suspend fun checkAllPermissions(): PermissionStatus = withContext(Dispatchers.IO) {
        Log.d(TAG, "检查所有权限...")
        
        // 基本存储权限
        val hasStorage = checkStoragePermission()
        
        // 所有文件访问权限
        val hasManageStorage = checkManageStoragePermission()
        
        // Shizuku 状态
        val isShizukuAvailable = shizukuManager.isAvailable.value
        val hasShizukuPerm = shizukuManager.isAuthorized.value
        val isServiceConnected = shizukuManager.isServiceConnected.value
        
        // 检测是否需要 Shizuku（通过实际创建文件测试）
        val checkResult = PermissionChecker.checkPermissionAccess(stopAppFirst = false)
        val needsShizuku = checkResult.needsShizuku
        val canAccessPrivate = checkResult.canAccessDirectly || (hasShizukuPerm && isServiceConnected)
        
        // 生成状态消息
        val message = buildStatusMessage(
            hasStorage, hasManageStorage, needsShizuku, 
            hasShizukuPerm, isShizukuAvailable, isServiceConnected, canAccessPrivate
        )
        
        val status = PermissionStatus(
            hasStoragePermission = hasStorage,
            hasManageStorage = hasManageStorage,
            needsShizuku = needsShizuku,
            hasShizukuPermission = hasShizukuPerm,
            isShizukuAvailable = isShizukuAvailable,
            isShizukuServiceConnected = isServiceConnected,
            canAccessPrivateDir = canAccessPrivate,
            statusMessage = message
        )
        
        _permissionStatus.value = status
        Log.d(TAG, "权限状态: $status")
        
        status
    }
    
    /**
     * 检查基本存储权限
     */
    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            true // Android 11+ 不需要旧的存储权限
        } else {
            ContextCompat.checkSelfPermission(
                context, 
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    /**
     * 检查所有文件访问权限
     */
    private fun checkManageStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // Android 10 及以下不需要
        }
    }
    
    /**
     * 请求存储权限
     */
    fun requestStoragePermission(launcher: ActivityResultLauncher<Array<String>>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            launcher.launch(arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ))
        }
    }
    
    /**
     * 请求所有文件访问权限
     */
    fun requestManageStoragePermission(activity: Activity, launcher: ActivityResultLauncher<Intent>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:${context.packageName}")
                launcher.launch(intent)
            } catch (e: Exception) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                launcher.launch(intent)
            }
        }
    }
    
    /**
     * 请求 Shizuku 权限
     */
    fun requestShizukuPermission(callback: (Boolean) -> Unit) {
        shizukuManager.requestPermission { granted ->
            // 更新状态
            _permissionStatus.value = _permissionStatus.value.copy(
                hasShizukuPermission = granted,
                isShizukuServiceConnected = shizukuManager.isServiceConnected.value
            )
            callback(granted)
        }
    }
    
    /**
     * 更新 Shizuku 状态
     * 如果 Shizuku 已授权但服务未连接，会自动尝试绑定
     */
    fun updateShizukuStatus() {
        shizukuManager.checkAvailability()
        
        val isAvailable = shizukuManager.isAvailable.value
        val isAuthorized = shizukuManager.isAuthorized.value
        val isServiceConnected = shizukuManager.isServiceConnected.value
        
        Log.d(TAG, "更新 Shizuku 状态: available=$isAvailable, authorized=$isAuthorized, connected=$isServiceConnected")
        
        // 如果已授权但服务未连接，自动尝试绑定
        if (isAvailable && isAuthorized && !isServiceConnected) {
            Log.d(TAG, "Shizuku 已授权，自动绑定 UserService...")
            shizukuManager.bindUserService()
        }
        
        _permissionStatus.value = _permissionStatus.value.copy(
            isShizukuAvailable = isAvailable,
            hasShizukuPermission = isAuthorized,
            isShizukuServiceConnected = shizukuManager.isServiceConnected.value
        )
    }
    
    /**
     * 生成状态消息
     */
    private fun buildStatusMessage(
        hasStorage: Boolean,
        hasManageStorage: Boolean,
        needsShizuku: Boolean,
        hasShizukuPerm: Boolean,
        isShizukuAvailable: Boolean,
        isServiceConnected: Boolean,
        canAccessPrivate: Boolean
    ): String {
        return when {
            !hasStorage -> "需要存储权限"
            !hasManageStorage -> "需要所有文件访问权限"
            !needsShizuku -> "可直接访问（无需 Shizuku）"
            !isShizukuAvailable -> "需要安装并启动 Shizuku"
            !hasShizukuPerm -> "需要 Shizuku 授权"
            !isServiceConnected -> "Shizuku 服务连接中..."
            canAccessPrivate -> "✓ 已就绪"
            else -> "权限检查完成"
        }
    }
    
    /**
     * 确保所有必要权限
     * @return true 如果所有权限都已满足
     */
    suspend fun ensurePermissions(): Boolean {
        val status = checkAllPermissions()
        
        if (!status.hasManageStorage) {
            return false
        }
        
        if (status.needsShizuku) {
            return status.hasShizukuPermission && status.isShizukuServiceConnected
        }
        
        return true
    }
}
