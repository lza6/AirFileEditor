package com.example.tfgwj.utils

import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 权限检测器
 * 用于检测是否需要 Shizuku 来访问应用私有目录
 */
object PermissionChecker {
    
    private const val TAG = "PermissionChecker"
    
    // 和平精英包名
    const val PUBG_PACKAGE_NAME = "com.tencent.tmgp.pubgmhd"
    
    // 内部存储根路径
    private const val STORAGE_ROOT = "/storage/emulated/0"
    
    // 和平精英数据目录
    private const val PUBG_DATA_PATH = "$STORAGE_ROOT/Android/data/$PUBG_PACKAGE_NAME"
    
    // 听风改文件缓存目录
    const val CACHE_DIR = "$STORAGE_ROOT/听风改文件"
    
    // 测试文件名
    private const val TEST_FILE_NAME = ".permission_test_tfgwj"
    
    /**
     * 检测结果
     */
    data class CheckResult(
        val needsShizuku: Boolean,          // 是否需要 Shizuku
        val canAccessDirectly: Boolean,     // 是否可以直接访问
        val androidVersion: Int,            // Android 版本
        val message: String                 // 描述信息
    )
    
    /**
     * 检测是否需要 Shizuku
     * 通过尝试在和平精英目录创建测试文件来判断
     * 
     * @param stopAppFirst 是否先停止和平精英应用
     * @return CheckResult 检测结果
     */
    suspend fun checkPermissionAccess(stopAppFirst: Boolean = true): CheckResult = withContext(Dispatchers.IO) {
        val androidVersion = Build.VERSION.SDK_INT
        
        Log.d(TAG, "开始权限检测，Android 版本: $androidVersion")
        
        // 先停止和平精英（防止应用占用目录）
        if (stopAppFirst) {
            stopPubgApp()
        }
        
        // Android 10 及以下通常可以直接访问
        if (androidVersion < Build.VERSION_CODES.R) {
            Log.d(TAG, "Android $androidVersion (< 11)，无需 Shizuku")
            return@withContext CheckResult(
                needsShizuku = false,
                canAccessDirectly = true,
                androidVersion = androidVersion,
                message = "Android ${Build.VERSION.RELEASE} 无需 Shizuku"
            )
        }
        
        // Android 11+ 需要测试实际访问能力
        val testResult = testDirectoryAccess()
        
        return@withContext if (testResult) {
            Log.d(TAG, "可以直接访问私有目录（可能是 root 或特殊系统）")
            CheckResult(
                needsShizuku = false,
                canAccessDirectly = true,
                androidVersion = androidVersion,
                message = "可直接访问（root/特殊系统）"
            )
        } else {
            Log.d(TAG, "无法直接访问私有目录，需要 Shizuku")
            CheckResult(
                needsShizuku = true,
                canAccessDirectly = false,
                androidVersion = androidVersion,
                message = "需要 Shizuku 授权"
            )
        }
    }
    
    /**
     * 停止和平精英应用
     */
    private fun stopPubgApp() {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("am", "force-stop", PUBG_PACKAGE_NAME))
            process.waitFor()
            Log.d(TAG, "已尝试停止和平精英")
        } catch (e: Exception) {
            Log.w(TAG, "停止应用失败（可能无权限）: ${e.message}")
        }
    }
    
    /**
     * 测试是否可以直接访问和平精英私有目录
     */
    private fun testDirectoryAccess(): Boolean {
        return try {
            val testDir = File(PUBG_DATA_PATH)
            val testFile = File(testDir, TEST_FILE_NAME)
            
            Log.d(TAG, "测试目录: $PUBG_DATA_PATH")
            
            // 检查目录是否存在
            if (!testDir.exists()) {
                // 尝试创建目录
                val dirCreated = testDir.mkdirs()
                if (!dirCreated) {
                    Log.d(TAG, "无法创建目录")
                    return false
                }
            }
            
            // 尝试创建测试文件
            val fileCreated = testFile.createNewFile()
            
            if (fileCreated || testFile.exists()) {
                // 清理测试文件
                testFile.delete()
                Log.d(TAG, "测试成功：可以创建文件")
                true
            } else {
                Log.d(TAG, "无法创建测试文件")
                false
            }
            
        } catch (e: SecurityException) {
            Log.d(TAG, "安全异常：${e.message}")
            false
        } catch (e: Exception) {
            Log.d(TAG, "访问测试失败：${e.message}")
            false
        }
    }
    
    /**
     * 确保听风改文件缓存目录存在
     */
    fun ensureCacheDir(): Boolean {
        return try {
            val cacheDir = File(CACHE_DIR)
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建缓存目录失败", e)
            false
        }
    }
    
    /**
     * 获取和平精英 Config 目录路径
     */
    fun getPubgConfigPath(): String {
        return "$PUBG_DATA_PATH/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/Config/Android"
    }
}

