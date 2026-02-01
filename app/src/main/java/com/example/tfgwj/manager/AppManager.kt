package com.example.tfgwj.manager

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.tfgwj.shizuku.ShizukuManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppManager(private val context: Context) {

    companion object {
        private const val TAG = "AppManager"

        @Volatile
        private var instance: AppManager? = null

        fun getInstance(context: Context): AppManager {
            return instance ?: synchronized(this) {
                instance ?: AppManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val shizukuManager = ShizukuManager.getInstance(context)
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    
    private val _isAppRunning = MutableStateFlow(false)
    val isAppRunning: StateFlow<Boolean> = _isAppRunning.asStateFlow()
    
    private val _currentPackageName = MutableStateFlow("")
    val currentPackageName: StateFlow<String> = _currentPackageName.asStateFlow()
    
    /**
     * 检查应用是否安装
     * @param packageName 应用包名
     * @return 是否安装
     */
    fun isAppInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            Log.d(TAG, "App not installed: $packageName")
            false
        }
    }
    
    /**
     * 检查应用是否运行
     * @param packageName 应用包名
     * @return 是否运行
     */
    fun checkAppRunning(packageName: String): Boolean {
        _currentPackageName.value = packageName
        
        // 优先使用Shizuku检查（更准确）
        if (shizukuManager.isAuthorized.value) {
            val isRunning = shizukuManager.isAppRunning(packageName)
            _isAppRunning.value = isRunning
            Log.d(TAG, "App running (Shizuku): $packageName = $isRunning")
            return isRunning
        }
        
        // 使用原生方式检查
        val isRunning = checkAppRunningNative(packageName)
        _isAppRunning.value = isRunning
        Log.d(TAG, "App running (Native): $packageName = $isRunning")
        return isRunning
    }
    
    /**
     * 使用原生方式检查应用是否运行
     * @param packageName 应用包名
     * @return 是否运行
     */
    private fun checkAppRunningNative(packageName: String): Boolean {
        return try {
            val runningProcesses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                activityManager.runningAppProcesses
            } else {
                return false
            }
            
            runningProcesses?.any { processInfo ->
                processInfo.processName == packageName || processInfo.pkgList.contains(packageName)
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check app running (native)", e)
            false
        }
    }
    
    /**
     * 停止应用
     * @param packageName 应用包名
     * @return 是否成功
     */
    fun stopApp(packageName: String): Boolean {
        _currentPackageName.value = packageName
        
        if (!isAppInstalled(packageName)) {
            Log.w(TAG, "App not installed: $packageName")
            return false
        }
        
        // 优先使用Shizuku停止（更强力）
        if (shizukuManager.isAuthorized.value) {
            val success = shizukuManager.stopApp(packageName)
            if (success) {
                _isAppRunning.value = false
                Log.d(TAG, "App stopped (Shizuku): $packageName")
                return true
            }
            Log.w(TAG, "Failed to stop app with Shizuku, trying native method")
        }
        
        // 使用原生方式停止
        val success = stopAppNative(packageName)
        if (success) {
            _isAppRunning.value = false
            Log.d(TAG, "App stopped (Native): $packageName")
        }
        return success
    }
    
    /**
     * 使用原生方式停止应用
     * @param packageName 应用包名
     * @return 是否成功
     */
    private fun stopAppNative(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                activityManager.killBackgroundProcesses(packageName)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop app (native)", e)
            false
        }
    }
    
    /**
     * 强制停止应用（Shizuku专用）
     * @param packageName 应用包名
     * @return 是否成功
     */
    fun forceStopApp(packageName: String): Boolean {
        _currentPackageName.value = packageName
        
        if (!shizukuManager.isAuthorized.value) {
            Log.w(TAG, "Shizuku not authorized, cannot force stop")
            return false
        }
        
        return try {
            // 使用am force-stop命令强制停止
            val success = shizukuManager.stopApp(packageName)
            if (success) {
                _isAppRunning.value = false
                Log.d(TAG, "App force stopped: $packageName")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to force stop app", e)
            false
        }
    }
    
    /**
     * 获取应用信息
     * @param packageName 应用包名
     * @return 应用信息
     */
    fun getAppInfo(packageName: String): AppInfo? {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(packageName, 0)
            val appInfo = packageInfo.applicationInfo ?: return null
            AppInfo(
                packageName = packageName,
                appName = appInfo.loadLabel(context.packageManager).toString(),
                versionName = packageInfo.versionName ?: "",
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                },
                icon = appInfo.loadIcon(context.packageManager)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get app info: $packageName", e)
            null
        }
    }
    
    /**
     * 获取所有已安装的应用
     * @return 应用列表
     */
    fun getInstalledApps(): List<AppInfo> {
        return try {
            val packages = context.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            packages.mapNotNull { appInfo ->
                try {
                    val packageInfo = context.packageManager.getPackageInfo(appInfo.packageName, 0)
                    AppInfo(
                        packageName = appInfo.packageName,
                        appName = appInfo.loadLabel(context.packageManager).toString(),
                        versionName = packageInfo.versionName ?: "",
                        versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            packageInfo.longVersionCode
                        } else {
                            @Suppress("DEPRECATION")
                            packageInfo.versionCode.toLong()
                        },
                        icon = appInfo.loadIcon(context.packageManager)
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get installed apps", e)
            emptyList()
        }
    }
    
    /**
     * 搜索游戏应用
     * @param keywords 搜索关键词
     * @return 匹配的应用列表
     */
    fun searchGameApps(keywords: List<String> = listOf("pubg", "game", "tencent")): List<AppInfo> {
        return getInstalledApps().filter { appInfo ->
            keywords.any { keyword ->
                appInfo.appName.contains(keyword, ignoreCase = true) ||
                appInfo.packageName.contains(keyword, ignoreCase = true)
            }
        }
    }
}

data class AppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val icon: android.graphics.drawable.Drawable
)