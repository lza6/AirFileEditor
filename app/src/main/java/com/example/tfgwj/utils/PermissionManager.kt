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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.util.Scanner

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
        val hasRoot: Boolean = false,                    // 是否有 Root 权限
        val rootManagerType: String = "",               // Root 管理器类型
        val availableModes: List<PermissionChecker.AccessMode> = emptyList(), // 所有可用模式
        val bestMode: PermissionChecker.AccessMode = PermissionChecker.AccessMode.NONE, // 最佳模式

        val hasShizukuPermission: Boolean = false,      // Shizuku 已授权
        val isShizukuAvailable: Boolean = false,        // Shizuku 可用
        val isShizukuServiceConnected: Boolean = false, // Shizuku UserService 已连接
        val canAccessPrivateDir: Boolean = false,       // 可访问私有目录
        val lastSelectedMode: PermissionChecker.AccessMode = PermissionChecker.AccessMode.NONE, // 上次手动选择的模式
        val statusMessage: String = ""                  // 状态描述
    )
    
    private val _permissionStatus = MutableStateFlow(PermissionStatus())
    val permissionStatus: StateFlow<PermissionStatus> = _permissionStatus.asStateFlow()
    
    private val shizukuManager = ShizukuManager.getInstance(context)
    private val mutex = Mutex()
    
    // 缓存配置路径
    private val CONFIG_FILE_PATH = "${PermissionChecker.CACHE_DIR}/.config/env_status.json"
    
    /**
     * 检查所有权限状态
     */
    suspend fun checkAllPermissions(forceRefresh: Boolean = false): PermissionStatus = mutex.withLock {
        withContext(Dispatchers.IO) {
            Log.d(TAG, "检测所有权限 (forceRefresh=$forceRefresh)...")
            
            // 1. 如果不是强制刷新，尝试从持久化配置加载 (快径)
            if (!forceRefresh) {
                val cachedStatus = loadEnvConfig()
                if (cachedStatus != null) {
                    Log.i(TAG, "🚀 [快径] 已从持久化配置加载环境: ${cachedStatus.statusMessage}")
                    _permissionStatus.value = cachedStatus
                    return@withContext cachedStatus
                }
            }

            // 2. 执行常规检测 (慢径)
            Log.d(TAG, "🐢 [慢径] 开始物理验证环境...")
            
            // 基本存储权限
            val hasStorage = checkStoragePermission()
            
            // 所有文件访问权限
            val hasManageStorage = checkManageStoragePermission()
            
            // Shizuku 状态
            val isShizukuAvailable = shizukuManager.isAvailable.value
            val hasShizukuPerm = shizukuManager.isAuthorized.value
            val isServiceConnected = shizukuManager.isServiceConnected.value
            
            // 检测是否需要 Shizuku（通过多维验证）
            val checkResult = PermissionChecker.checkPermissionAccess(stopAppFirst = false, context = context)
            val availableModes = checkResult.availableModes.toMutableList()
            
            // 如果 Shizuku 已经授权并连接，确保它在可用列表中
            if (hasShizukuPerm && isServiceConnected && !availableModes.contains(PermissionChecker.AccessMode.SHIZUKU)) {
                availableModes.add(PermissionChecker.AccessMode.SHIZUKU)
            }
            
            val canAccessPrivate = checkResult.bestMode != PermissionChecker.AccessMode.NONE || (hasShizukuPerm && isServiceConnected)
            
            // 检测 Root 权限
            val hasRoot = RootChecker.isRooted()
            val rootManagerType = if (hasRoot) RootChecker.getRootManagerType() else ""
            
            // 生成状态消息
            val message = buildStatusMessage(
                hasStorage, hasManageStorage, hasRoot, rootManagerType,
                checkResult.bestMode, availableModes,
                hasShizukuPerm, isShizukuAvailable, isServiceConnected, canAccessPrivate
            )
            
            // 2.5 确定最佳模式
            val lastModeName = loadEnvConfig()?.lastSelectedMode ?: PermissionChecker.AccessMode.NONE
            val finalBestMode = if (lastModeName != PermissionChecker.AccessMode.NONE && availableModes.contains(lastModeName)) {
                Log.i(TAG, "🎯 优先使用用户历史手动选择的模式: $lastModeName")
                lastModeName
            } else {
                checkResult.bestMode
            }

            val status = PermissionStatus(
                hasStoragePermission = hasStorage,
                hasManageStorage = hasManageStorage,
                hasRoot = hasRoot,
                rootManagerType = rootManagerType,
                availableModes = availableModes,
                bestMode = finalBestMode,
                hasShizukuPermission = hasShizukuPerm,
                isShizukuAvailable = isShizukuAvailable,
                isShizukuServiceConnected = isServiceConnected,
                canAccessPrivateDir = canAccessPrivate,
                lastSelectedMode = lastModeName,
                statusMessage = message
            )
            
            // 3. 将有效结果持久化
            if (canAccessPrivate) {
                saveEnvConfig(status)
            }
            
            _permissionStatus.value = status
            Log.d(TAG, "权限状态已更新并持久化: $status")
            
            status
        }
    }

    /**
     * 保存环境配置到持久化存储
     */
    private fun saveEnvConfig(status: PermissionStatus) {
        try {
            val dir = File(PermissionChecker.CACHE_DIR, ".config")
            if (!dir.exists()) dir.mkdirs()
            
            val json = JSONObject().apply {
                put("hasRoot", status.hasRoot)
                put("rootManagerType", status.rootManagerType)
                put("bestMode", status.bestMode.name)
                put("canAccessPrivateDir", status.canAccessPrivateDir)
                put("androidVersion", Build.VERSION.SDK_INT)
                put("brand", Build.BRAND)
                put("model", Build.MODEL)
                put("lastSelectedMode", status.lastSelectedMode.name)
                put("timestamp", System.currentTimeMillis())
            }
            
            FileWriter(CONFIG_FILE_PATH).use { it.write(json.toString()) }
            Log.d(TAG, "环境配置已保存到: $CONFIG_FILE_PATH, 上次选择: ${status.lastSelectedMode}")
        } catch (e: Exception) {
            Log.w(TAG, "保存环境配置失败: ${e.message}")
        }
    }

    /**
     * 从持久化存储加载环境配置
     */
    private suspend fun loadEnvConfig(): PermissionStatus? {
        return try {
            val file = File(CONFIG_FILE_PATH)
            if (!file.exists()) return null
            
            val content = Scanner(file).useDelimiter("\\A").next()
            val json = JSONObject(content)
            
            // 校验设备信息，如果设备信息变了（比如系统更新或换手机），则失效
            val androidVersion = json.getInt("androidVersion")
            val brand = json.getString("brand")
            val model = json.getString("model")
            
            if (androidVersion != Build.VERSION.SDK_INT || brand != Build.BRAND || model != Build.MODEL) {
                Log.d(TAG, "环境配置已过期 (设备信息不匹配)")
                return null
            }

            // 重新获取动态状态（Shizuku 是否运行中等）
            val hasRoot = json.getBoolean("hasRoot")
            val canAccessPrivate = json.getBoolean("canAccessPrivateDir")
            val bestModeName = json.optString("bestMode", "NONE")
            val bestMode = PermissionChecker.AccessMode.valueOf(bestModeName)
            
            // 下面这些属性需要根据当前应用运行情况动态获取
            val hasStorage = checkStoragePermission()
            val hasManageStorage = checkManageStoragePermission()
            val isShizukuAvailable = shizukuManager.isAvailable.value
            val hasShizukuPerm = shizukuManager.isAuthorized.value
            val isServiceConnected = shizukuManager.isServiceConnected.value
            
            val lastModeName = json.optString("lastSelectedMode", "NONE")
            val lastSelectedMode = PermissionChecker.AccessMode.valueOf(lastModeName)

            // 由于是从持久化加载，我们需要重新获取当前环境下的可用模式和消息
            val checkResult = PermissionChecker.checkPermissionAccess(stopAppFirst = false, context = context)
            val message = buildStatusMessage(
                hasStorage, hasManageStorage, hasRoot, json.getString("rootManagerType"),
                checkResult.bestMode, checkResult.availableModes,
                hasShizukuPerm, isShizukuAvailable, isServiceConnected, canAccessPrivate
            )

            PermissionStatus(
                hasStoragePermission = hasStorage,
                hasManageStorage = hasManageStorage,
                hasRoot = hasRoot,
                rootManagerType = json.getString("rootManagerType"),
                availableModes = checkResult.availableModes,
                bestMode = checkResult.bestMode,
                hasShizukuPermission = hasShizukuPerm,
                isShizukuAvailable = isShizukuAvailable,
                isShizukuServiceConnected = isServiceConnected,
                canAccessPrivateDir = canAccessPrivate,
                lastSelectedMode = lastSelectedMode,
                statusMessage = message
            )
        } catch (e: Exception) {
            Log.w(TAG, "加载持久化配置失败: ${e.message}")
            null
        }
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
    fun requestShizukuPermission(callback: ((Boolean) -> Unit)? = null) {
        if (!shizukuManager.isAvailable.value) {
            Log.e(TAG, "无法请求 Shizuku 权限：Shizuku 未运行")
            callback?.invoke(false)
            return
        }

        shizukuManager.requestPermission { granted ->
            // 更新状态
            _permissionStatus.value = _permissionStatus.value.copy(
                hasShizukuPermission = granted,
                isShizukuServiceConnected = shizukuManager.isServiceConnected.value
            )
            callback?.invoke(granted)
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
     * 注意：Root 设备不需要存储权限即可访问私有目录
     */
    private fun buildStatusMessage(
        hasStorage: Boolean,
        hasManageStorage: Boolean,
        hasRoot: Boolean,
        rootManagerType: String,
        bestMode: PermissionChecker.AccessMode,
        availableModes: List<PermissionChecker.AccessMode>,
        hasShizukuPerm: Boolean,
        isShizukuAvailable: Boolean,
        isServiceConnected: Boolean,
        canAccessPrivate: Boolean
    ): String {
        return when {
            // 所有文件访问权限依然是基础
            !hasManageStorage -> "需要所有文件访问权限"
            
            // 如果具备可用模式
            bestMode != PermissionChecker.AccessMode.NONE -> {
                val modeStr = when (bestMode) {
                    PermissionChecker.AccessMode.ROOT -> "Root 模式"
                    PermissionChecker.AccessMode.NATIVE -> if (Build.VERSION.SDK_INT < 30) "系统原生支持" else "原生访问模式"
                    PermissionChecker.AccessMode.SHIZUKU -> "Shizuku 模式"
                    else -> "未知模式"
                }
                "✓ $modeStr (已物理验证)"
            }
            
            // 模式不可用时的具体排查
            hasRoot -> "已检出 Root ($rootManagerType)，但读写测试受限"
            
            isShizukuAvailable -> {
                when {
                    !hasShizukuPerm -> "正在等待 Shizuku 授权..."
                    !isServiceConnected -> "Shizuku 服务正在启动中..."
                    else -> "Shizuku 已开启，正在验证读写权限..."
                }
            }
            
            Build.VERSION.SDK_INT >= 30 -> {
                if (PermissionChecker.isHarmonyOS()) "检测到系统读写受限，建议尝试不同模式"
                else "Android 系统限制，请尝试连接 Shizuku 或 Root"
            }
            else -> "正在检查存储读写权限..."
        }
    }
    
    /**
     * 手动选择并验证模式
     */
    suspend fun manuallySelectMode(mode: PermissionChecker.AccessMode): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "用户手动选择模式: $mode")
        
        // 1. 更新状态为验证中
        _permissionStatus.value = _permissionStatus.value.copy(
            statusMessage = "正在验证 ${mode.name} 模式..."
        )
        
        // 2. 验证该模式
        val success = PermissionChecker.checkSinglePermissionAccess(mode, context = context)
        
        if (success) {
            Log.d(TAG, "✅ 手动验证成功: $mode")
            val current = _permissionStatus.value
            val newStatus = current.copy(
                bestMode = mode,
                lastSelectedMode = mode,
                canAccessPrivateDir = true,
                statusMessage = "✓ 已手动切换至 ${mode.name} 模式"
            )
            _permissionStatus.value = newStatus
            saveEnvConfig(newStatus)
            true
        } else {
            Log.w(TAG, "❌ 手动验证失败: $mode")
            _permissionStatus.value = _permissionStatus.value.copy(
                statusMessage = "⚠️ ${mode.name} 模式验证失败，请确认权限已开启"
            )
            false
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
        
        // 如果已经有验证过的最佳模式，直接通过
        if (status.bestMode != PermissionChecker.AccessMode.NONE && status.canAccessPrivateDir) {
            // 特殊处理 Shizuku，确保服务还连着
            if (status.bestMode == PermissionChecker.AccessMode.SHIZUKU) {
                return status.hasShizukuPermission && status.isShizukuServiceConnected
            }
            return true
        }
        
        return false
    }
}
