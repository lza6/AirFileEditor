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
    
    // 支持的应用包名
    const val PUBG_PACKAGE_NAME = "com.tencent.tmgp.pubgmhd"  // 和平精英
    const val YOUTUBE_PACKAGE_NAME = "com.google.android.youtube"  // YouTube
    
    // 应用信息数据类
    data class AppInfo(
        val packageName: String,
        val displayName: String,
        val configPathTemplate: String  // Config 路径模板，用 {packageName} 占位符
    )
    
    // 支持的应用列表
    val SUPPORTED_APPS = mapOf(
        PUBG_PACKAGE_NAME to AppInfo(
            PUBG_PACKAGE_NAME,
            "和平精英",
            "/storage/emulated/0/Android/data/{packageName}/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/Config/Android"
        ),
        YOUTUBE_PACKAGE_NAME to AppInfo(
            YOUTUBE_PACKAGE_NAME,
            "YouTube",
            "/storage/emulated/0/Android/data/{packageName}/files/"
        )
    )
    
    // 内部存储根路径
    private const val STORAGE_ROOT = "/storage/emulated/0"
    
    // 和平精英数据目录（保持向后兼容）
    private const val PUBG_DATA_PATH = "$STORAGE_ROOT/Android/data/$PUBG_PACKAGE_NAME"
    
    // 听风改文件缓存目录
    const val CACHE_DIR = "$STORAGE_ROOT/听风改文件"
    
    // 测试文件名前缀
    private const val TEST_FILE_PREFIX = "听风验证环境_"
    
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
     * 通过尝试在应用目录创建测试文件来判断
     * 
     * @param packageName 应用包名（默认为和平精英）
     * @param stopAppFirst 是否先停止应用
     * @return CheckResult 检测结果
     */
    suspend fun checkPermissionAccess(packageName: String = PUBG_PACKAGE_NAME, stopAppFirst: Boolean = true): CheckResult = withContext(Dispatchers.IO) {
        val androidVersion = Build.VERSION.SDK_INT
        
        Log.d(TAG, "开始权限检测，应用: $packageName, Android 版本: $androidVersion")
        
        // 先停止应用（防止应用占用目录）
        if (stopAppFirst) {
            stopApp(packageName)
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
        val testResult = testDirectoryAccess(packageName)
        
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
     * 停止应用
     */
    private fun stopApp(packageName: String) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("am", "force-stop", packageName))
            process.waitFor()
            Log.d(TAG, "已尝试停止应用: $packageName")
        } catch (e: Exception) {
            Log.w(TAG, "停止应用失败（可能无权限）: ${e.message}")
        }
    }
    
    /**
     * 测试是否可以直接访问应用私有目录
     * Root 设备使用 Root 命令，非 Root 设备使用普通 API
     */
    private fun testDirectoryAccess(packageName: String = PUBG_PACKAGE_NAME): Boolean {
        return try {
            // 1. 检测是否有 Root 权限
            val hasRoot = RootChecker.isRooted()
            if (hasRoot) {
                Log.d(TAG, "检测到 Root 权限 (${RootChecker.getRootManagerType()})，使用 Root 命令测试目录访问")
                
                // Root 模式：使用 Root 命令创建测试文件
                val testDataPath = getAppDataPath(packageName)
                val timestamp = System.currentTimeMillis()
                val testFileName = "${TEST_FILE_PREFIX}${timestamp}.tmp"
                val testFilePath = "$testDataPath/$testFileName"
                
                Log.d(TAG, "========== Root 模式测试目录访问 ==========")
                Log.d(TAG, "目标应用: $packageName")
                Log.d(TAG, "应用目录: $testDataPath")
                Log.d(TAG, "验证文件名: $testFileName")
                Log.d(TAG, "完整路径: $testFilePath")
                
                // 先确保目录存在
                                    Log.d(TAG, "📝 [Root 命令] 确保目录存在...")
                                    RootChecker.executeRootCommand("mkdir -p \"$testDataPath\"")
                                    Log.d(TAG, "✅ [Root 命令] 确保目录存在: $testDataPath")
                                    
                                    // 清理旧的验证文件（使用 find 命令，更可靠）
                                    Log.d(TAG, "🗑️ [Root 命令] 清理旧验证文件...")
                                    val listOldResult = RootChecker.executeRootCommand("ls \"$testDataPath/${TEST_FILE_PREFIX}*.tmp\" 2>/dev/null")
                                    Log.d(TAG, "   旧的验证文件: ${listOldResult ?: "无"}")
                                    
                                    val cleanResult = RootChecker.executeRootCommand("find \"$testDataPath\" -maxdepth 1 -name \"${TEST_FILE_PREFIX}*.tmp\" -delete 2>/dev/null && echo 'CLEANED' || echo 'NONE_TO_CLEAN'")
                                    Log.d(TAG, "   clean 输出: $cleanResult")
                                    
                                    // 验证清理结果
                                    val verifyCleanResult = RootChecker.executeRootCommand("ls \"$testDataPath/${TEST_FILE_PREFIX}*.tmp\" 2>/dev/null")
                                    if (verifyCleanResult != null && verifyCleanResult.isNotEmpty()) {
                                        Log.w(TAG, "⚠️ 清理失败，仍然存在旧验证文件: $verifyCleanResult")
                                    } else {
                                        Log.d(TAG, "✅ [Root 命令] 清理旧验证文件成功")
                                    }                
                // 使用 Root 命令创建测试文件
                Log.d(TAG, "📝 使用 Root 命令创建测试文件...")
                RootChecker.executeRootCommand("touch \"$testFilePath\"")
                
                // 检查文件是否创建成功
                Log.d(TAG, "🔍 检查文件是否存在...")
                val checkResult = RootChecker.executeRootCommand("ls -la \"$testFilePath\"")
                Log.d(TAG, "检查命令输出: $checkResult")
                
                if (checkResult != null && checkResult.contains(testFileName)) {
                    Log.d(TAG, "✅ Root 模式测试成功：可以创建文件")
                    // 及时删除测试文件
                    RootChecker.executeRootCommand("rm -f \"$testFilePath\"")
                    return true
                } else {
                    Log.d(TAG, "❌ Root 模式测试失败：无法创建文件")
                    Log.d(TAG, "   完整路径: $testFilePath")
                    return false
                }
            }
            
            // 2. 非 Root 模式：使用普通 API 测试
            Log.d(TAG, "非 Root 模式，使用普通 API 测试目录访问")
            val testDataPath = getAppDataPath(packageName)
            val testDir = File(testDataPath)
            val testFileName = "${TEST_FILE_PREFIX}${System.currentTimeMillis()}.tmp"
            val testFile = File(testDir, testFileName)
            
            Log.d(TAG, "========== 普通模式测试目录访问 ==========")
            Log.d(TAG, "目标应用: $packageName")
            Log.d(TAG, "应用目录: $testDataPath")
            Log.d(TAG, "验证文件名: $testFileName")
            Log.d(TAG, "完整路径: ${testFile.absolutePath}")
            
            // 检查目录是否存在
            if (!testDir.exists()) {
                // 尝试创建目录
                val dirCreated = testDir.mkdirs()
                Log.d(TAG, "目录不存在，尝试创建: $dirCreated")
                if (!dirCreated) {
                    Log.d(TAG, "无法创建目录")
                    return false
                }
            }
            
            // 清理之前的验证文件，避免无限累积
            val oldFiles = testDir.listFiles()?.filter { 
                it.name.startsWith(TEST_FILE_PREFIX) && it.name.endsWith(".tmp") 
            }
            if (oldFiles != null && oldFiles.isNotEmpty()) {
                Log.d(TAG, "发现 ${oldFiles.size} 个旧验证文件:")
                oldFiles.forEach { file ->
                    val deleted = file.delete()
                    Log.d(TAG, "  - ${file.name} (${file.absolutePath}) ${if (deleted) "✅ 已删除" else "❌ 删除失败"}")
                }
            }
            
            // 尝试创建测试文件
            Log.d(TAG, "📝 创建测试文件...")
            val fileCreated = testFile.createNewFile()
            Log.d(TAG, "创建结果: $fileCreated")
            Log.d(TAG, "文件绝对路径: ${testFile.absolutePath}")
            
            if (fileCreated || testFile.exists()) {
                Log.d(TAG, "✅ 普通模式测试成功：可以创建文件")
                testFile.delete() // 及时删除测试文件
                true
            } else {
                Log.d(TAG, "❌ 普通模式测试失败：无法创建测试文件")
                Log.d(TAG, "   完整路径: ${testFile.absolutePath}")
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
     * 获取应用数据目录路径
     */
    fun getAppDataPath(packageName: String): String {
        return "$STORAGE_ROOT/Android/data/$packageName"
    }
    
    /**
     * 获取应用 Config 目录路径
     */
    fun getAppConfigPath(packageName: String): String {
        val appInfo = SUPPORTED_APPS[packageName]
        return if (appInfo != null) {
            // 使用预定义的模板
            appInfo.configPathTemplate.replace("{packageName}", packageName)
        } else {
            // 使用默认模板
            "$STORAGE_ROOT/Android/data/$packageName/files/"
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
     * 获取和平精英 Config 目录路径（保持向后兼容）
     */
    fun getPubgConfigPath(): String {
        return getAppConfigPath(PUBG_PACKAGE_NAME)
    }
    
    /**
     * 获取应用显示名称
     */
    fun getAppDisplayName(packageName: String): String {
        return SUPPORTED_APPS[packageName]?.displayName ?: packageName
    }
    
    /**
     * 检查是否为支持的应用
     */
    fun isSupportedApp(packageName: String): Boolean {
        return SUPPORTED_APPS.containsKey(packageName)
    }
    
    /**
     * 获取所有支持的应用列表
     */
    fun getSupportedAppsList(): List<AppInfo> {
        return SUPPORTED_APPS.values.toList()
    }
}

