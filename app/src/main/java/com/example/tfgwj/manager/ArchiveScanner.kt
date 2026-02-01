package com.example.tfgwj.manager

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 压缩包扫描器
 * 扫描 /storage/emulated/0/ 下的压缩包（排除 Android 目录）
 */
class ArchiveScanner private constructor() {
    
    companion object {
        private const val TAG = "ArchiveScanner"
        private const val STORAGE_ROOT = "/storage/emulated/0"
        private const val CACHE_FILE_NAME = ".archive_scan_cache"
        
        // 支持的压缩包格式
        private val ARCHIVE_EXTENSIONS = setOf("zip", "7z", "rar")
        
        // 排除的目录
        private val EXCLUDED_DIRS = setOf("Android", ".android_secure")
        
        @Volatile
        private var instance: ArchiveScanner? = null
        
        fun getInstance(): ArchiveScanner {
            return instance ?: synchronized(this) {
                instance ?: ArchiveScanner().also { instance = it }
            }
        }
    }
    
    /**
     * 压缩包信息
     */
    data class ArchiveInfo(
        val name: String,           // 文件名
        val path: String,           // 完整路径
        val sizeBytes: Long,        // 大小（字节）
        val sizeText: String,       // 大小显示文本
        val extension: String,      // 扩展名
        val lastModified: Long      // 最后修改时间
    )
    
    // 扫描结果
    private val _archives = MutableStateFlow<List<ArchiveInfo>>(emptyList())
    val archives: StateFlow<List<ArchiveInfo>> = _archives.asStateFlow()
    
    // 扫描状态
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    
    // 扫描进度 (0-1)
    private val _scanProgress = MutableStateFlow(0f)
    val scanProgress: StateFlow<Float> = _scanProgress.asStateFlow()
    
    // 扫描状态文字
    private val _scanStatus = MutableStateFlow("")
    val scanStatus: StateFlow<String> = _scanStatus.asStateFlow()
    
    // 缓存的路径集合
    private var cachedPaths = mutableSetOf<String>()
    
    /**
     * 扫描压缩包
     * @param useCache 是否使用缓存加速
     */
    suspend fun scanArchives(useCache: Boolean = true): List<ArchiveInfo> = withContext(Dispatchers.IO) {
        if (_isScanning.value) {
            return@withContext _archives.value
        }
        
        _isScanning.value = true
        _scanProgress.value = 0f
        _scanStatus.value = "正在扫描..."
        
        val results = mutableListOf<ArchiveInfo>()
        
        try {
            // 加载缓存
            if (useCache) {
                loadCache()
            }
            
            val rootDir = File(STORAGE_ROOT)
            val topLevelDirs = rootDir.listFiles { file -> 
                file.isDirectory && file.name !in EXCLUDED_DIRS
            } ?: emptyArray()
            
            val totalDirs = topLevelDirs.size
            
            topLevelDirs.forEachIndexed { index, dir ->
                _scanStatus.value = "正在扫描: ${dir.name} (${results.size} 个)"
                
                // 扫描该目录
                scanDirectory(dir, results)
                
                _scanProgress.value = (index + 1).toFloat() / totalDirs
            }
            
            // 也扫描根目录下的压缩包
            rootDir.listFiles { file -> 
                file.isFile && file.extension.lowercase() in ARCHIVE_EXTENSIONS
            }?.forEach { file ->
                results.add(createArchiveInfo(file))
            }
            
            // 按最后修改时间排序（最新的在前）
            results.sortByDescending { it.lastModified }
            
            _archives.value = results
            
            // 保存缓存
            saveCache(results.map { it.path }.toSet())
            
            Log.d(TAG, "扫描完成，找到 ${results.size} 个压缩包")
            _scanStatus.value = "找到 ${results.size} 个压缩包"
            
        } catch (e: Exception) {
            Log.e(TAG, "扫描压缩包失败", e)
            _scanStatus.value = "扫描失败: ${e.message}"
        } finally {
            _isScanning.value = false
            _scanProgress.value = 1f
        }
        
        results
    }
    
    /**
     * 递归扫描目录
     */
    private fun scanDirectory(dir: File, results: MutableList<ArchiveInfo>) {
        try {
            dir.listFiles()?.forEach { file ->
                when {
                    file.isFile && file.extension.lowercase() in ARCHIVE_EXTENSIONS -> {
                        results.add(createArchiveInfo(file))
                    }
                    file.isDirectory && file.name !in EXCLUDED_DIRS -> {
                        // 递归扫描子目录（限制深度避免性能问题）
                        if (getDepth(file) <= 3) {
                            scanDirectory(file, results)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "扫描目录失败: ${dir.absolutePath}", e)
        }
    }
    
    /**
     * 获取目录深度
     */
    private fun getDepth(file: File): Int {
        val rootPath = STORAGE_ROOT
        val relativePath = file.absolutePath.removePrefix(rootPath)
        return relativePath.count { it == File.separatorChar }
    }
    
    /**
     * 创建压缩包信息
     */
    private fun createArchiveInfo(file: File): ArchiveInfo {
        return ArchiveInfo(
            name = file.name,
            path = file.absolutePath,
            sizeBytes = file.length(),
            sizeText = formatSize(file.length()),
            extension = file.extension.lowercase(),
            lastModified = file.lastModified()
        )
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
    
    /**
     * 加载缓存
     */
    private fun loadCache() {
        try {
            val cacheFile = File(STORAGE_ROOT, CACHE_FILE_NAME)
            if (cacheFile.exists()) {
                cachedPaths = cacheFile.readLines().toMutableSet()
                Log.d(TAG, "加载缓存: ${cachedPaths.size} 条记录")
            }
        } catch (e: Exception) {
            Log.w(TAG, "加载缓存失败", e)
        }
    }
    
    /**
     * 保存缓存
     */
    private fun saveCache(paths: Set<String>) {
        try {
            val cacheFile = File(STORAGE_ROOT, CACHE_FILE_NAME)
            cacheFile.writeText(paths.joinToString("\n"))
            cachedPaths = paths.toMutableSet()
            Log.d(TAG, "保存缓存: ${paths.size} 条记录")
        } catch (e: Exception) {
            Log.w(TAG, "保存缓存失败", e)
        }
    }
    
    /**
     * 清除缓存
     */
    fun clearCache() {
        try {
            val cacheFile = File(STORAGE_ROOT, CACHE_FILE_NAME)
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
            cachedPaths.clear()
        } catch (e: Exception) {
            Log.w(TAG, "清除缓存失败", e)
        }
    }
    
    /**
     * 筛选压缩包（按关键字）
     */
    fun filterArchives(keyword: String): List<ArchiveInfo> {
        return if (keyword.isBlank()) {
            _archives.value
        } else {
            _archives.value.filter { 
                it.name.contains(keyword, ignoreCase = true) 
            }
        }
    }
}
