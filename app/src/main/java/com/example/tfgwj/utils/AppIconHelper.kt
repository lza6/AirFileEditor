package com.example.tfgwj.utils

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.content.ContextCompat

/**
 * 应用图标工具类
 * 用于获取应用图标
 */
object AppIconHelper {
    
    private const val TAG = "AppIconHelper"
    
    /**
     * 获取应用图标
     * @param context 上下文
     * @param packageName 应用包名
     * @return 应用图标，如果获取失败返回默认图标
     */
    suspend fun getAppIcon(context: Context, packageName: String): Drawable? = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationIcon(appInfo)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "应用未安装: $packageName")
            null
        } catch (e: Exception) {
            Log.e(TAG, "获取应用图标失败", e)
            null
        }
    }
    
    /**
     * 获取应用名称
     */
    suspend fun getAppName(context: Context, packageName: String): String = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }
    
    /**
     * 检查应用是否已安装
     */
    fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getApplicationInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
    
    /**
     * 获取和平精英图标
     */
    suspend fun getPubgIcon(context: Context): Drawable? {
        return getAppIcon(context, PermissionChecker.PUBG_PACKAGE_NAME)
    }
    
    /**
     * 获取和平精英应用名称
     */
    suspend fun getPubgAppName(context: Context): String {
        return getAppName(context, PermissionChecker.PUBG_PACKAGE_NAME)
    }

    /**
     * 获取微信图标
     */
    suspend fun getWechatIcon(context: Context): Drawable? {
        return getAppIcon(context, "com.tencent.mm")
    }
}
