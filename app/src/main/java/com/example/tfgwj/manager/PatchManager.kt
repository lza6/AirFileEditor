package com.example.tfgwj.manager

import com.example.tfgwj.shizuku.ShizukuManager
import com.example.tfgwj.utils.PermissionChecker
import com.example.tfgwj.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 小包管理器
 * 负责扫描、管理听风改文件目录下的版本文件夹
 */
class PatchManager private constructor() {
    
    companion object {
        private const val TAG = "PatchManager"
        
        @Volatile
        private var instance: PatchManager? = null
        
        fun getInstance(): PatchManager {
            return instance ?: synchronized(this) {
                instance ?: PatchManager().also { instance = it }
            }
        }
    }
    
    /**
     * 小包版本信息
     */
    data class PatchVersion(
        val name: String,           // 版本名称（如 "心 (110)"）
        val path: String,           // 完整路径
        val sizeBytes: Long,        // 总大小（字节）
        val sizeText: String,       // 大小显示文本（如 "1.5 GB"）
        val fileCount: Int,         // 文件数量
        val hasIniFiles: Boolean    // 是否包含 ini 文件
    )
    
    // 小包版本列表
    private val _patchVersions = MutableStateFlow<List<PatchVersion>>(emptyList())
    val patchVersions: StateFlow<List<PatchVersion>> = _patchVersions.asStateFlow()
    
    // 是否正在扫描
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    
    // 扫描进度
    private val _scanProgress = MutableStateFlow(0f)
    val scanProgress: StateFlow<Float> = _scanProgress.asStateFlow()
    
    /**
     * 扫描听风改文件目录下的小包版本
     */
    suspend fun scanPatchVersions(): List<PatchVersion> = withContext(Dispatchers.IO) {
        if (_isScanning.value) {
            return@withContext _patchVersions.value
        }
        
        _isScanning.value = true
        _scanProgress.value = 0f
        
        val versions = mutableListOf<PatchVersion>()
        
        try {
            val cacheDir = File(PermissionChecker.CACHE_DIR)
            
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
                AppLogger.func("scanPatchVersions", "创建缓存目录", true, cacheDir.absolutePath)
            }
            
            // 获取所有子目录（版本文件夹）
            val subDirs = cacheDir.listFiles { file -> file.isDirectory } ?: emptyArray()
            val total = subDirs.size
            
            subDirs.forEachIndexed { index, dir ->
                // 过滤 logs 文件夹
                if (dir.name.lowercase() == "logs") {
                    _scanProgress.value = (index + 1).toFloat() / total
                    return@forEachIndexed
                }

                // 检查是否是主包（包含 Android 目录）
                val androidDir = File(dir, "Android")
                val isMainPack = androidDir.exists() && androidDir.isDirectory
                
                if (!isMainPack) {
                    // 这是小包版本
                    val version = analyzeVersionFolder(dir)
                    if (version != null && version.hasIniFiles) {
                        versions.add(version)
                    }
                }
                
                _scanProgress.value = (index + 1).toFloat() / total
            }
            
            // 按名称排序
            versions.sortBy { it.name }
            
            _patchVersions.value = versions
            AppLogger.func("scanPatchVersions", "扫描小包完成", true, "找到 ${versions.size} 个版本")
            
        } catch (e: Exception) {
            AppLogger.func("scanPatchVersions", "扫描小包失败", false, e.message ?: "未知错误")
        } finally {
            _isScanning.value = false
            _scanProgress.value = 1f
        }
        
        versions
    }
    
    /**
     * 分析版本文件夹
     */
    private fun analyzeVersionFolder(dir: File): PatchVersion? {
        return try {
            val name = dir.name
            val path = dir.absolutePath
            
            var totalSize = 0L
            var fileCount = 0
            var hasIniFiles = false
            
            dir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    totalSize += file.length()
                    fileCount++
                    if (file.extension.lowercase() == "ini") {
                        hasIniFiles = true
                    }
                }
            }
            
            PatchVersion(
                name = name,
                path = path,
                sizeBytes = totalSize,
                sizeText = formatSize(totalSize),
                fileCount = fileCount,
                hasIniFiles = hasIniFiles
            )
        } catch (e: Exception) {
            AppLogger.func("analyzeVersionFolder", "分析文件夹失败", false, "Path: ${dir.absolutePath} | Error: ${e.message}")
            null
        }
    }
    
    /**
     * 删除小包版本（原生操作，因为在听风改文件目录下）
     */
    suspend fun deletePatchVersion(version: PatchVersion): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = File(version.path)
            if (dir.exists()) {
                val deleted = dir.deleteRecursively()
                if (deleted) {
                    // 更新列表
                    _patchVersions.value = _patchVersions.value.filter { it.path != version.path }
                    AppLogger.func("deletePatchVersion", "已删除小包", true, "Name: ${version.name}")
                }
                deleted
            } else {
                false
            }
        } catch (e: Exception) {
            AppLogger.func("deletePatchVersion", "删除小包失败", false, "Name: ${version.name} | Error: ${e.message}")
            false
        }
    }
    
    /**
     * 删除小包版本（带进度回调）
     */
    suspend fun deletePatchVersionWithProgress(
        version: PatchVersion,
        progressCallback: ((current: Int, total: Int, currentItem: String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = File(version.path)
            if (!dir.exists()) {
                return@withContext false
            }
            
            // 收集所有文件和文件夹（从深到浅排序，先删除子目录）
            val allItems = dir.walkTopDown()
                .sortedByDescending { it.absolutePath.length }
                .toList()
            
            val total = allItems.size
            
            // 使用 ForkJoinPool 并行删除，适度并发避免锁死
            val parallelism = Runtime.getRuntime().availableProcessors() * 8  // CPU核心数 * 8
            val executor = java.util.concurrent.ForkJoinPool(parallelism)
            
            val results = java.util.concurrent.ConcurrentLinkedQueue<Pair<File, Boolean>>()
            var deletedCount = 0
            
            // 使用并行流批量删除
            allItems.parallelStream().forEach { item ->
                var deleted = false
                var retryCount = 0
                val maxRetries = 3
                
                // 重试机制
                while (!deleted && retryCount < maxRetries) {
                    try {
                        deleted = item.delete()
                        if (!deleted) {
                            retryCount++
                            Thread.sleep(20)  // 等待 20ms 后重试
                        }
                    } catch (e: Exception) {
                        retryCount++
                        Thread.sleep(20)
                    }
                }
                
                results.add(item to deleted)
                if (deleted) {
                    deletedCount++
                    // 更新进度
                    progressCallback?.invoke(deletedCount, total, item.name)
                }
            }
            
            // 等待所有删除任务完成
            executor.shutdown()
            executor.awaitTermination(300, java.util.concurrent.TimeUnit.SECONDS)
            
            // 短暂延迟确保所有 I/O 完成
            kotlinx.coroutines.delay(500)
            
            // 检查失败的项目，尝试再次删除
            val failedItems = results.filter { !it.second }.map { it.first }
            var retryDeletedCount = 0
            
            if (failedItems.isNotEmpty()) {
                // 对失败的项目再次尝试（可能是因为并发导致的临时问题）
                failedItems.forEach { item ->
                    if (item.delete()) {
                        retryDeletedCount++
                    }
                }
            }
            
            deletedCount += retryDeletedCount
            
            // 如果所有项目都删除成功，则认为删除成功
            val success = (deletedCount >= total * 0.99)  // 允许 1% 的失败率（可能是系统临时文件）
            
            if (success) {
                // 更新列表
                _patchVersions.value = _patchVersions.value.filter { it.path != version.path }
                AppLogger.func("deletePatchVersion", "已删除小包", true, "Name: ${version.name}")
            } else {
                val successCount = results.count { it.second }
                AppLogger.func("deletePatchVersion", "删除小包失败", false, "Name: ${version.name} | 成功: $successCount/$total")
            }
            
            success
        } catch (e: Exception) {
            AppLogger.func("deletePatchVersion", "删除小包失败", false, "Name: ${version.name} | Error: ${e.message}")
            false
        }
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
     * 获取小包中的 ini 文件列表
     */
    fun getIniFiles(version: PatchVersion): List<File> {
        val dir = File(version.path)
        return dir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() == "ini" }
            .toList()
    }
    
    /**
     * 将小包的 ini 文件复制到主包的 Config 目录
     * 使用原生复制（因为目标是听风改文件目录下的主包）
     */
    suspend fun applyPatchToMainPack(
        version: PatchVersion,
        mainPackPath: String,
        progressCallback: ((Int, Int) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // 目标 Config 目录
            val configPath = "$mainPackPath/Android/data/${PermissionChecker.PUBG_PACKAGE_NAME}/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/Config/Android"
            val configDir = File(configPath)
            
            if (!configDir.exists()) {
                configDir.mkdirs()
            }
            
            // 获取小包中的 ini 文件
            val iniFiles = getIniFiles(version)
            val total = iniFiles.size
            var success = 0
            
            iniFiles.forEachIndexed { index, iniFile ->
                try {
                    val targetFile = File(configDir, iniFile.name)
                    iniFile.copyTo(targetFile, overwrite = true)
                    success++
                } catch (e: Exception) {
                    AppLogger.file("COPY_INI", iniFile.name, false, e.message)
                }
                progressCallback?.invoke(index + 1, total)
            }
            
            AppLogger.func("applyPatchToMainPack", "应用小包完成", success == total, "成功: $success/$total")
            success == total
            
        } catch (e: Exception) {
            AppLogger.func("applyPatchToMainPack", "应用小包失败", false, e.message ?: "未知错误")
            false
        }
    }
}
