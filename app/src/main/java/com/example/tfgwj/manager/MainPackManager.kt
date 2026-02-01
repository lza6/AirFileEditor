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
     */
    suspend fun scanMainPacks(): List<MainPackInfo> = withContext(Dispatchers.IO) {
        if (_isScanning.value) return@withContext _mainPacks.value
        
        _isScanning.value = true
        val packs = mutableListOf<MainPackInfo>()
        
        try {
            val cacheDir = File(PermissionChecker.CACHE_DIR)
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            
            cacheDir.listFiles { file -> file.isDirectory }?.forEach { dir ->
                val info = analyzeMainPack(dir)
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
     */
    private fun analyzeMainPack(dir: File): MainPackInfo? {
        return try {
            val androidDir = File(dir, "Android")
            val hasAndroid = androidDir.exists() && androidDir.isDirectory
            
            if (!hasAndroid) return null
            
            val configPath = "${dir.absolutePath}/Android/data/${PermissionChecker.PUBG_PACKAGE_NAME}/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/Config/Android"
            val configDir = File(configPath)
            val hasConfig = configDir.exists()
            
            var totalSize = 0L
            dir.walkTopDown().forEach { file ->
                if (file.isFile) totalSize += file.length()
            }
            
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
     * 选择主包
     */
    fun selectMainPack(pack: MainPackInfo) {
        _selectedMainPack.value = pack
        Log.d(TAG, "已选择主包: ${pack.name}")
    }
    
    /**
     * 获取主包的 Config 目录路径
     */
    fun getConfigPath(pack: MainPackInfo): String {
        return "${pack.path}/Android/data/${PermissionChecker.PUBG_PACKAGE_NAME}/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/Config/Android"
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
