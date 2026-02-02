package com.example.tfgwj.utils

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.tfgwj.shizuku.ShizukuManager
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
    
    // 访问模式枚举
    enum class AccessMode {
        ROOT,       // Root 权限
        SHIZUKU,    // Shizuku 权限
        NATIVE,     // 原生 API 权限
        NONE        // 无权限
    }
    
    /**
     * 检测结果
     */
    data class CheckResult(
        val availableModes: List<AccessMode>, // 所有可用的模式，按优先级排序
        val bestMode: AccessMode,             // 当前推荐的最佳模式
        val androidVersion: Int,              // Android 版本
        val message: String,                  // 描述信息
    )
    
    /**
     * 检测是否需要 Shizuku
     * 通过尝试在应用目录创建测试文件来判断
     * 
     * @param packageName 应用包名（默认为和平精英）
     * @param stopAppFirst 是否先停止应用
     * @return CheckResult 检测结果
     */
    suspend fun checkPermissionAccess(packageName: String = PUBG_PACKAGE_NAME, stopAppFirst: Boolean = true, context: android.content.Context? = null): CheckResult = withContext(Dispatchers.IO) {
        val androidVersion = Build.VERSION.SDK_INT
        val availableModes = mutableListOf<AccessMode>()
        
        Log.d(TAG, "开始全能模式权限检测，应用: $packageName, Android 版本: $androidVersion")
        
        // 先停止应用（可选）
        if (stopAppFirst) {
            stopApp(packageName)
        }
        
        // 1. 测试 Root 模式
        val hasRoot = RootChecker.isRooted()
        if (hasRoot) {
            // 注意：Root 可能受限，所以这里测试一下
            if (testRootAccess(packageName)) {
                availableModes.add(AccessMode.ROOT)
                Log.d(TAG, "✅ [ROOT] 验证通过")
            } else {
                Log.w(TAG, "⚠️ [ROOT] 虽然有 Root 但无法访问目标目录")
            }
        }
        
        // 2. 测试原生 Native 模式
        // Android 11+ 通常受限，但 Android < 11 或 HarmonyOS 或开了管理外部存储权限的某些系统可能通
        val hasNativeAccess = testDirectoryAccessNative(packageName)
        if (hasNativeAccess) {
            availableModes.add(AccessMode.NATIVE)
            Log.d(TAG, "✅ [NATIVE] 验证通过")
        } else {
            Log.d(TAG, "❌ [NATIVE] 原生访问受限")
        }
        
        // 3. Shizuku 模式检测
        val shizukuManager = ShizukuManager.getInstance(context)
        val isShizukuInstalled = shizukuManager.isAvailable.value
        
        // 如果是 Android 11+，Shizuku 是一个潜在方案
        if (androidVersion >= Build.VERSION_CODES.R) {
            availableModes.add(AccessMode.SHIZUKU)
            Log.d(TAG, "ℹ️ [SHIZUKU] 识别为 Android 11+ 潜在方案")
        }
        
        // 判定最佳模式 (智能排序)
        // 优先级：Native (最快，无进程开销) > Root (强大但有开销) > Shizuku (仅在已连接时推荐)
        val bestMode = when {
            availableModes.contains(AccessMode.NATIVE) -> AccessMode.NATIVE
            availableModes.contains(AccessMode.ROOT) -> AccessMode.ROOT
            availableModes.contains(AccessMode.SHIZUKU) && 
                shizukuManager.isAuthorized.value && 
                shizukuManager.isServiceConnected.value -> AccessMode.SHIZUKU
            else -> AccessMode.NONE
        }
        
        // 构造消息 (更灵活的智能提示)
        val isHarmonyOS = isHarmonyOS()
        val message = when {
            bestMode == AccessMode.NATIVE -> {
                if (androidVersion < 30) "系统原生支持 (兼容性极佳)" 
                else if (isHarmonyOS) "HarmonyOS 环境 (已验证直接访问)" 
                else "原生访问模式 (测试通过)"
            }
            bestMode == AccessMode.ROOT -> "Root 极速模式 (已授权)"
            bestMode == AccessMode.SHIZUKU -> "Shizuku 极速模式 (已连接)"
            hasRoot -> "已发现 Root 权限，点击开启"
            isShizukuInstalled && shizukuManager.isAuthorized.value -> "Shizuku 已授权，请连接服务"
            isShizukuInstalled -> "Shizuku 已安装，点击申请授权"
            androidVersion >= 30 -> {
                if (isHarmonyOS) "HarmonyOS 已受限，请尝试 Root 或 Shizuku"
                else "Android 系统限制，推荐尝试高级模式"
            }
            else -> "建议尝试手动选择授权模式"
        }
        
        return@withContext CheckResult(
            availableModes = availableModes.distinct(),
            bestMode = bestMode,
            androidVersion = androidVersion,
            message = message
        )
    }

    /**
     * 单个模式验证（用于手动选择模式后的即时验证）
     */
    suspend fun checkSinglePermissionAccess(
        mode: AccessMode,
        packageName: String = PUBG_PACKAGE_NAME,
        context: Context? = null
    ): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "正在验证单项模式: $mode")
        when (mode) {
            AccessMode.ROOT -> {
                RootChecker.isRooted() && testRootAccess(packageName)
            }
            AccessMode.SHIZUKU -> {
                // 先确保 Shizuku 已经授权并连接
                val shizuku = ShizukuManager.getInstance(context)
                shizuku.isAvailable.value && shizuku.isAuthorized.value && shizuku.isServiceConnected.value && 
                testDirectoryAccessShizuku(packageName, context)
            }
            AccessMode.NATIVE -> {
                testDirectoryAccessNative(packageName)
            }
            AccessMode.NONE -> false
        }
    }

    /**
     * 获取模式的详细描述和建议（用于 UI 显示）
     */
    fun getModeDescription(mode: AccessMode, androidVersion: Int): Pair<String, String> {
        return when (mode) {
            AccessMode.ROOT -> {
                "Root 模式" to "原理：通过超级用户权限直接访问系统文件。\n建议：已解锁 Bootloader 并获取 Root 权限的用户首选，兼容性最强。"
            }
            AccessMode.SHIZUKU -> {
                "Shizuku 模式" to "原理：利用 ADB 系统服务权限进行文件操作。\n建议：Android 11 及以上版本且未 Root 用户的推荐选择，稳定且安全。"
            }
            AccessMode.NATIVE -> {
                "普通模式" to "原理：使用系统原生 API 访问公开目录数据。\n建议：Android 10 及以下版本，或部分 HarmonyOS/系统已授权目录访问权限时使用。"
            }
            AccessMode.NONE -> {
                "无模式" to "当前环境无法正常访问目标目录，请尝试开启 Shizuku 或 Root。"
            }
        }
    }
    
    // 专门用于 Root 测试的方法
    private fun testRootAccess(packageName: String): Boolean {
        val testDataPath = getAppDataPath(packageName)
        val timestamp = System.currentTimeMillis()
        val testFileName = "${TEST_FILE_PREFIX}root_${timestamp}.tmp"
        val testFilePath = "$testDataPath/$testFileName"
        
        return try {
            RootChecker.executeRootCommand("mkdir -p \"$testDataPath\" && touch \"$testFilePath\"")
            val checkResult = RootChecker.executeRootCommand("ls \"$testFilePath\"")
            val success = checkResult != null && checkResult.contains(testFileName)
            if (success) {
                RootChecker.executeRootCommand("rm -f \"$testFilePath\"")
            }
            success
        } catch (e: Exception) {
            false
        }
    }
    
    // 专门用于原生测试的方法
    private fun testDirectoryAccessNative(packageName: String): Boolean {
        return try {
            // 测试多个可能的私有路径
            val pathsToTest = arrayOf(
                "/storage/emulated/0/Android/data/$packageName/files",
                "/storage/emulated/0/Android/data/$packageName",
                "/storage/emulated/0/Android/obb/$packageName"
            )
            
            for (path in pathsToTest) {
                val testDir = File(path)
                
                // 优化：如果目录不存在，尝试先建立它以验证权限，或者检查父目录
                if (!testDir.exists()) {
                    try {
                        if (testDir.mkdirs()) {
                            Log.d(TAG, "Native 探测：成功创建测试目录 $path")
                        }
                    } catch (e: Exception) {}
                }

                if (testDir.exists() && testDir.canWrite()) {
                    // 尝试创建临时文件 (Write Test)
                    val testFileName = "${TEST_FILE_PREFIX}native_${System.currentTimeMillis()}.tmp"
                    val testFile = File(testDir, testFileName)
                    
                    try {
                        if (testFile.createNewFile()) {
                            testFile.delete()
                            Log.d(TAG, "Native 探测成功: $path")
                            return true
                        }
                    } catch (e: Exception) {
                        // 继续尝试下一个路径
                    }
                }
            }
            
            // 最后的兜底检查：如果应用具有 MANAGE_EXTERNAL_STORAGE 权限且处于某些定制系统（如 HarmonyOS 旧版或某些平板）
            // 可能可以直接通过 shell ls 看到目录
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 专门用于 Shizuku 测试的方法
     */
    private fun testDirectoryAccessShizuku(packageName: String, context: Context?): Boolean {
        return try {
            val shizuku = ShizukuManager.getInstance(context)
            if (!shizuku.isAuthorized.value || !shizuku.isServiceConnected.value) {
                return false
            }

            val testDataPath = getAppDataPath(packageName)
            val testFileName = "${TEST_FILE_PREFIX}shizuku_${System.currentTimeMillis()}.tmp"
            val testFilePath = "$testDataPath/$testFileName"

            // 1. 尝试创建目录（如果不存在）
            shizuku.createDirectory(testDataPath)

            // 2. 尝试使用 touch 创建文件
            // 注意：Shizuku 执行命令通常是在 shell 权限下
            val exitCode = shizuku.executeCommand("touch \"$testFilePath\"")
            
            if (exitCode == 0) {
                // 3. 验证文件是否存在且可感知
                val exists = shizuku.fileExists(testFilePath)
                if (exists) {
                    shizuku.deleteFile(testFilePath)
                    Log.d(TAG, "Shizuku 探测成功: $testDataPath")
                    return true
                }
            }
            
            Log.w(TAG, "Shizuku 探测失败 (ExitCode: $exitCode): $testDataPath")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku 探测异常", e)
            false
        }
    }
    
    fun isHarmonyOS(): Boolean {
        return try {
            val clz = Class.forName("com.huawei.system.BuildEx")
            val method = clz.getMethod("getOsBrand")
            "harmony".equals(method.invoke(clz) as String, ignoreCase = true)
        } catch (e: Exception) {
            false
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

