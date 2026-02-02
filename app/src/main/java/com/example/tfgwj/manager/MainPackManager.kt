package com.example.tfgwj.manager

import android.util.Log
import com.example.tfgwj.utils.PermissionChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 主包管理器
 * 负责管理听风改文件目录下的主包
 */
class MainPackManager private constructor() {
    
    companion object {
        private const val TAG = "MainPackManager"
        
        @Volatile
        private var instance: MainPackManager? = null
        
        fun getInstance(): MainPackManager {
            return instance ?: synchronized(this) {
                instance ?: MainPackManager().also { instance = it }
            }
        }
    }
    
    /**
     * 主包信息
     */
    data class MainPackInfo(
        val name: String,           // 主包名称
        val path: String,           // 完整路径
        val sizeBytes: Long,        // 总大小（字节）
        val sizeText: String,       // 大小显示文本
        val hasAndroidDir: Boolean, // 是否包含 Android 目录
        val hasConfigDir: Boolean,  // 是否包含 Config 目录
        val lastModified: Long      // 最后修改时间
    )
    
    // 主包列表
    private val _mainPacks = MutableStateFlow<List<MainPackInfo>>(emptyList())
    val mainPacks: StateFlow<List<MainPackInfo>> = _mainPacks.asStateFlow()
    
    // 当前选中的主包
    private val _selectedMainPack = MutableStateFlow<MainPackInfo?>(null)
    val selectedMainPack: StateFlow<MainPackInfo?> = _selectedMainPack.asStateFlow()
    
    // 扫描状态
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    
    /**
     * 扫描听风改文件目录下的主包
     * 判断依据：包含 Android/data/.../files 目录
     * 
     * @param packageName 应用包名（默认为和平精英）
     */
    suspend fun scanMainPacks(packageName: String = PermissionChecker.PUBG_PACKAGE_NAME): List<MainPackInfo> = withContext(Dispatchers.IO) {
        if (_isScanning.value) return@withContext _mainPacks.value
        
        _isScanning.value = true
        val packs = mutableListOf<MainPackInfo>()
        
        try {
            val cacheDir = File(PermissionChecker.CACHE_DIR)
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            
            cacheDir.listFiles { file -> file.isDirectory }?.forEach { dir ->
                val info = analyzeMainPack(dir, packageName)
                if (info?.hasAndroidDir == true) {
                    packs.add(info)
                }
            }
            
            packs.sortBy { it.name }
            _mainPacks.value = packs
            
            Log.d(TAG, "扫描完成，找到 ${packs.size} 个主包")
            
        } catch (e: Exception) {
            Log.e(TAG, "扫描主包失败", e)
        } finally {
            _isScanning.value = false
        }
        
        packs
    }
    
    /**
     * 分析主包目录
     * 
     * @param dir 主包目录
     * @param packageName 应用包名（默认为和平精英）
     */
    private fun analyzeMainPack(dir: File, packageName: String = PermissionChecker.PUBG_PACKAGE_NAME): MainPackInfo? {
        return try {
            // 查找 Android 目录（支持嵌套路径，如 /主包名字/主包名字/Android）
            val androidDir = findAndroidDirectory(dir)
            val hasAndroid = androidDir != null && androidDir.exists() && androidDir.isDirectory
            
            if (!hasAndroid) {
                Log.d(TAG, "目录 ${dir.name} 未找到 Android 目录")
                return null
            }
            
            // 根据 Android 目录的实际路径和包名来判断 Config 目录
            val configPath = PermissionChecker.getAppConfigPath(packageName)
            // 将 configPath 中的绝对路径替换为相对于 androidDir 的路径
            val relativeConfigPath = if (configPath.startsWith("/storage/emulated/0/Android/data/")) {
                configPath.substringAfter("/storage/emulated/0/Android/data/")
            } else {
                "$packageName/files/"
            }
            
            val actualConfigPath = "${androidDir.absolutePath}/data/$relativeConfigPath"
            val configDir = File(actualConfigPath)
            val hasConfig = configDir.exists()
            
            // 统计目录大小（只统计主包根目录及其子目录）
            var totalSize = 0L
            dir.walkTopDown().maxDepth(5).forEach { file ->
                if (file.isFile) totalSize += file.length()
            }
            
            Log.d(TAG, "发现主包: ${dir.name}, Android 路径: ${androidDir.absolutePath}, 包名: $packageName")
            
            MainPackInfo(
                name = dir.name,
                path = dir.absolutePath,
                sizeBytes = totalSize,
                sizeText = formatSize(totalSize),
                hasAndroidDir = hasAndroid,
                hasConfigDir = hasConfig,
                lastModified = dir.lastModified()
            )
        } catch (e: Exception) {
            Log.e(TAG, "分析主包失败: ${dir.absolutePath}", e)
            null
        }
    }
    
    /**
     * 查找 Android 目录
     * 支持嵌套路径，如：
     * - /path/to/pack/Android
     * - /path/to/pack/pack/Android
     * - /path/to/pack/pack/pack/Android
     * 
     * @param baseDir 基础目录
     * @return Android 目录 File 对象，如果找不到返回 null
     */
    private fun findAndroidDirectory(baseDir: File): File? {
        // 1. 首先检查当前目录下是否有 Android 子目录
        val directAndroid = File(baseDir, "Android")
        if (directAndroid.exists() && directAndroid.isDirectory) {
            return directAndroid
        }
        
        // 2. 递归检查子目录（最多检查 3 层，防止无限递归）
        val maxDepth = 3
        val androidDir = findAndroidDirectoryRecursive(baseDir, 0, maxDepth)
        if (androidDir != null) {
            return androidDir
        }
        
        return null
    }
    
    /**
     * 递归查找 Android 目录
     */
    private fun findAndroidDirectoryRecursive(dir: File, currentDepth: Int, maxDepth: Int): File? {
        if (currentDepth >= maxDepth) {
            return null
        }
        
        if (!dir.exists() || !dir.isDirectory) {
            return null
        }
        
        // 检查当前目录是否是 Android 目录
        if (dir.name == "Android") {
            return dir
        }
        
        // 递归检查子目录
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                // 如果子目录就是 Android 目录，直接返回
                if (child.name == "Android") {
                    return child
                }
                // 否则继续递归
                val found = findAndroidDirectoryRecursive(child, currentDepth + 1, maxDepth)
                if (found != null) {
                    return found
                }
            }
        }
        
        return null
    }
    
    /**
     * 选择主包
     */
    fun selectMainPack(pack: MainPackInfo) {
        _selectedMainPack.value = pack
        Log.d(TAG, "已选择主包: ${pack.name}")
    }
    
    /**
     * 获取主包的 Config 目录路径
     * 
     * @param pack 主包信息
     * @param packageName 应用包名（默认为和平精英）
     */
    fun getConfigPath(pack: MainPackInfo, packageName: String = PermissionChecker.PUBG_PACKAGE_NAME): String {
        val androidDir = findAndroidDirectory(File(pack.path))
        if (androidDir != null) {
            val configPath = PermissionChecker.getAppConfigPath(packageName)
            // 将 configPath 中的绝对路径替换为相对于 androidDir 的路径
            val relativeConfigPath = if (configPath.startsWith("/storage/emulated/0/Android/data/")) {
                configPath.substringAfter("/storage/emulated/0/Android/data/")
            } else {
                "$packageName/files/"
            }
            return "${androidDir.absolutePath}/data/$relativeConfigPath"
        }
        // 降级方案：直接使用 pack.path
        return "${pack.path}/Android/data/$packageName/files/"
    }
    
    /**
     * 格式化文件大小
     */
    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L * 1024L -> {
                String.format("%.2f GB", bytes.toDouble() / (1024L * 1024L * 1024L))
            }
            bytes >= 1024L * 1024L -> {
                String.format("%.2f MB", bytes.toDouble() / (1024L * 1024L))
            }
            bytes >= 1024L -> {
                String.format("%.2f KB", bytes.toDouble() / 1024L)
            }
            else -> "$bytes B"
        }
    }
}
