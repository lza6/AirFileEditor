package com.example.tfgwj.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.tfgwj.BuildConfig
import com.example.tfgwj.IFileOperationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs

/**
 * Shizuku 管理器
 * 负责 Shizuku 权限管理和 UserService 绑定
 */
class ShizukuManager private constructor(private val context: Context) {
    
    // Watchdog Scope
    private val watchdogScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    companion object {
        private const val TAG = "ShizukuManager"

        @Volatile
        private var instance: ShizukuManager? = null

        fun getInstance(context: Context): ShizukuManager {
            return instance ?: synchronized(this) {
                instance ?: ShizukuManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // Shizuku 是否可用（已安装且正在运行）
    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    // Shizuku 是否已授权
    private val _isAuthorized = MutableStateFlow(false)
    val isAuthorized: StateFlow<Boolean> = _isAuthorized.asStateFlow()

    // UserService 是否已连接
    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    // 权限请求结果
    private val _permissionResult = MutableLiveData<Result<Boolean>>()
    val permissionResult: LiveData<Result<Boolean>> = _permissionResult

    // UserService 接口
    private var fileOperationService: IFileOperationService? = null

    // 权限请求回调
    private var permissionCallback: ((Boolean) -> Unit)? = null

    // 权限结果监听器（保存引用以便正确移除）
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        _isAuthorized.value = granted
        _permissionResult.value = Result.success(granted)
        permissionCallback?.invoke(granted)
        Log.d(TAG, "权限请求结果: $granted")
        
        // 授权成功后绑定 UserService
        if (granted) {
            bindUserService()
        }
    }

    // Binder 接收监听器
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku Binder 已接收")
        checkAvailability()
        checkPermission()
    }

    // Binder 死亡监听器
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku Binder 已死亡")
        _isAvailable.value = false
        _isAuthorized.value = false
        _isServiceConnected.value = false
        fileOperationService = null
    }

    // UserService 连接
    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "UserService 已连接")
            fileOperationService = IFileOperationService.Stub.asInterface(service)
            _isServiceConnected.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "UserService 已断开")
            fileOperationService = null
            _isServiceConnected.value = false
        }
    }

    // UserService 参数
    private val userServiceArgs = UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, FileOperationService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("file_operation")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    init {
        // 注册监听器
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        
        // 初始检查
        checkAvailability()
        
        // 启动 Watchdog (稳定性增强)
        startWatchdog()
    }
    
    /**
     * 启动 Watchdog
     * 实时监控 UserService 存活并在 IO 挂起时自动重连
     */
    private fun startWatchdog() {
        watchdogScope.launch {
            while (isActive) {
                try {
                    delay(5000) // 每 5 秒检查一次
                    
                    if (_isAuthorized.value && !_isServiceConnected.value) {
                         // 只有当 Shizuku 已经授权但服务未连接时才尝试重连
                         // 避免在未安装 Shizuku 时无限重试
                         if (Shizuku.pingBinder()) {
                             Log.w(TAG, "Watchdog: 检测到服务断开，尝试自动重连...")
                             bindUserService()
                         }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Watchdog 异常", e)
                }
            }
        }
    }

    /**
     * 检查 Shizuku 是否可用
     */
    fun checkAvailability() {
        try {
            _isAvailable.value = Shizuku.pingBinder()
            Log.d(TAG, "Shizuku 可用: ${_isAvailable.value}")
            
            if (_isAvailable.value) {
                checkPermission()
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查 Shizuku 可用性失败", e)
            _isAvailable.value = false
        }
    }

    /**
     * 检查 Shizuku 权限
     */
    fun checkPermission(): Boolean {
        if (!_isAvailable.value) return false

        return try {
            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            _isAuthorized.value = granted
            Log.d(TAG, "Shizuku 已授权: $granted")
            
            // 已授权则绑定 UserService
            if (granted && !_isServiceConnected.value) {
                bindUserService()
            }
            
            granted
        } catch (e: Exception) {
            Log.e(TAG, "检查权限失败", e)
            false
        }
    }

    /**
     * 请求 Shizuku 权限
     */
    fun requestPermission(callback: (Boolean) -> Unit) {
        this.permissionCallback = callback
        
        if (!_isAvailable.value) {
            Log.w(TAG, "Shizuku 不可用")
            callback(false)
            return
        }
        
        try {
            // 检查是否已经授权
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                _isAuthorized.value = true
                callback(true)
                bindUserService()
                return
            }
            
            // 检查是否被永久拒绝
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                Log.w(TAG, "用户已永久拒绝权限")
                callback(false)
                return
            }
            
            // 请求权限
            Shizuku.requestPermission(0)
        } catch (e: Exception) {
            Log.e(TAG, "请求权限失败", e)
            callback(false)
        }
    }

    /**
     * 绑定 UserService
     * 当 Shizuku 已授权但服务未连接时调用
     */
    fun bindUserService() {
        if (!_isAuthorized.value) {
            Log.w(TAG, "未授权，无法绑定 UserService")
            return
        }
        
        if (_isServiceConnected.value) {
            Log.d(TAG, "UserService 已连接，无需重复绑定")
            return
        }
        
        try {
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
            Log.d(TAG, "正在绑定 UserService...")
        } catch (e: Exception) {
            Log.e(TAG, "绑定 UserService 失败", e)
        }
    }

    /**
     * 解绑 UserService
     */
    fun unbindUserService() {
        try {
            Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
            fileOperationService = null
            _isServiceConnected.value = false
            Log.d(TAG, "已解绑 UserService")
        } catch (e: Exception) {
            Log.e(TAG, "解绑 UserService 失败", e)
        }
    }

    /**
     * 销毁管理器，移除所有监听器
     */
    fun destroy() {
        watchdogScope.cancel() // 停止 Watchdog
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        unbindUserService()
    }

    // ========== 文件操作方法（通过 UserService）==========

    /**
     * 创建目录
     */
    fun createDirectory(path: String): Boolean {
        return try {
            fileOperationService?.createDirectory(path) ?: run {
                Log.w(TAG, "UserService 未连接")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建目录失败: $path", e)
            false
        }
    }

    /**
     * 删除文件或目录
     */
    fun deleteFile(path: String): Boolean {
        return try {
            fileOperationService?.deleteFile(path) ?: run {
                Log.w(TAG, "UserService 未连接")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "删除失败: $path", e)
            false
        }
    }

    /**
     * 复制文件
     */
    fun copyFile(source: String, target: String): Boolean {
        return try {
            fileOperationService?.copyFile(source, target) ?: run {
                Log.w(TAG, "UserService 未连接")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "复制文件失败", e)
            false
        }
    }

    /**
     * 复制目录
     */
    fun copyDirectory(source: String, target: String): Boolean {
        return try {
            fileOperationService?.copyDirectory(source, target) ?: run {
                Log.w(TAG, "UserService 未连接")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "复制目录失败", e)
            false
        }
    }

    /**
     * 复制目录并带进度回调 (高性能)
     */
    fun copyDirectoryWithProgress(source: String, target: String, callback: com.example.tfgwj.ICopyCallback) {
        try {
            fileOperationService?.copyDirectoryWithProgress(source, target, callback) ?: run {
                Log.w(TAG, "UserService 未连接")
                callback.onError("UserService 未连接")
            }
        } catch (e: Exception) {
            Log.e(TAG, "复制目录失败", e)
            callback.onError(e.message)
        }
    }

    /**
     * 检查文件是否存在
     */
    fun fileExists(path: String): Boolean {
        return try {
            fileOperationService?.fileExists(path) ?: run {
                Log.w(TAG, "UserService 未连接")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查文件存在失败", e)
            false
        }
    }

    /**
     * 执行命令
     */
    fun executeCommand(command: String): Int {
        return try {
            fileOperationService?.executeCommand(command) ?: run {
                Log.w(TAG, "UserService 未连接")
                -1
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行命令失败", e)
            -1
        }
    }

    /**
     * 执行命令并获取输出
     */
    fun executeCommandWithOutput(command: String): String {
        return try {
            fileOperationService?.executeCommandWithOutput(command) ?: run {
                Log.w(TAG, "UserService 未连接")
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行命令失败", e)
            ""
        }
    }

    /**
     * 停止应用
     */
    fun stopApp(packageName: String): Boolean {
        return try {
            fileOperationService?.stopApp(packageName) ?: run {
                Log.w(TAG, "UserService 未连接")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "停止应用失败", e)
            false
        }
    }

    /**
     * 检查应用是否运行
     */
    fun isAppRunning(packageName: String): Boolean {
        return try {
            fileOperationService?.isAppRunning(packageName) ?: run {
                Log.w(TAG, "UserService 未连接")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查应用状态失败", e)
            false
        }
    }
    
    /**
     * 获取应用数据路径
     */
    fun getAppDataPath(packageName: String): String? {
        return try {
            val output = executeCommandWithOutput("pm path $packageName")
            val apkPath = output.removePrefix("package:").trim()
            if (apkPath.isNotEmpty()) {
                apkPath.substringBeforeLast("/base.apk")
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取应用数据路径失败", e)
            null
        }
    }

    /**
     * 清理目录（带进度回调）
     */
    fun cleanDirectoryWithProgress(basePath: String, whiteList: Array<String>, callback: com.example.tfgwj.IDeleteCallback) {
        try {
            fileOperationService?.cleanDirectoryWithProgress(basePath, whiteList, callback) ?: run {
                Log.w(TAG, "UserService 未连接")
                callback.onError("UserService 未连接")
            }
        } catch (e: Exception) {
            Log.e(TAG, "清理目录失败", e)
            callback.onError(e.message)
        }
    }
}
