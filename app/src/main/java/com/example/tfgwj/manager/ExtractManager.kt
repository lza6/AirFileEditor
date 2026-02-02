package com.example.tfgwj.manager

import android.util.Log
import com.example.tfgwj.utils.PermissionChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.progress.ProgressMonitor
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * 解压管理器
 * 支持解压 ZIP 格式压缩包到听风改文件目录
 */
class ExtractManager private constructor() {
    
    companion object {
        private const val TAG = "ExtractManager"
        
        // 智能计算的最佳缓冲区大小
        private val optimalBufferSize: Int
            get() = com.example.tfgwj.utils.IoOptimizer.getOptimalBufferSize()
        
        @Volatile
        private var instance: ExtractManager? = null
        
        fun getInstance(): ExtractManager {
            return instance ?: synchronized(this) {
                instance ?: ExtractManager().also { instance = it }
            }
        }
    }
    
    /**
     * 解压结果
     */
    data class ExtractResult(
        val success: Boolean,
        val outputPath: String = "",
        val extractedCount: Int = 0,
        val errorMessage: String? = null
    )
    
    // 解压状态
    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()
    
    // 解压进度 (0-100)
    private val _extractProgress = MutableStateFlow(0)
    val extractProgress: StateFlow<Int> = _extractProgress.asStateFlow()
    
    // 状态文字
    private val _extractStatus = MutableStateFlow("")
    val extractStatus: StateFlow<String> = _extractStatus.asStateFlow()
    
    /**
     * 需要密码异常
     */
    class PasswordRequiredException : Exception("Password required")
    
    /**
     * 取消解压操作
     */
    fun cancelExtraction() {
        _isExtracting.value = false
        _extractStatus.value = "已取消"
    }

    /**
     * 解压压缩包到听风改文件目录
     * @param archivePath 压缩包路径
     * @param password 密码（可选）
     * @param outputDirName 输出目录名（默认使用压缩包名）
     */
    suspend fun extractToCache(
        archivePath: String,
        password: String? = null,
        outputDirName: String? = null
    ): ExtractResult = withContext(Dispatchers.IO) {
        if (_isExtracting.value) {
            return@withContext ExtractResult(false, errorMessage = "正在解压中")
        }
        
        _isExtracting.value = true
        _extractProgress.value = 0
        _extractStatus.value = "准备解压..."
        
        try {
            val archiveFile = File(archivePath)
            if (!archiveFile.exists()) {
                return@withContext ExtractResult(false, errorMessage = "压缩包不存在")
            }
            
            // 确定输出目录
            val dirName = outputDirName 
                ?: archiveFile.nameWithoutExtension
            val outputDir = File(PermissionChecker.CACHE_DIR, dirName)
            
            // 如果目录已存在，先清理
            if (outputDir.exists()) {
                outputDir.deleteRecursively()
            }
            outputDir.mkdirs()
            
            _extractStatus.value = "正在解压: ${archiveFile.name}"
            
            // 根据后缀选择解压方式
            val result = if (archivePath.endsWith(".7z", ignoreCase = true)) {
                extract7z(archivePath, outputDir.absolutePath, password)
            } else {
                extractZip(archivePath, outputDir.absolutePath, password)
            }
            
            if (result.success) {
                _extractStatus.value = "解压完成: ${result.extractedCount} 个文件"
                Log.d(TAG, "解压成功: $archivePath -> ${outputDir.absolutePath}")
            } else {
                _extractStatus.value = "解压失败: ${result.errorMessage}"
            }
            
            result
            
        } catch (e: PasswordRequiredException) {
            Log.w(TAG, "解压需要密码")
            _extractStatus.value = "需要密码"
            ExtractResult(false, errorMessage = "需要密码")
        } catch (e: Exception) {
            Log.e(TAG, "解压失败", e)
            _extractStatus.value = "解压失败: ${e.message}"
            ExtractResult(false, errorMessage = e.message)
        } finally {
            _isExtracting.value = false
            _extractProgress.value = 100
        }
    }
    
    /**
     * 解压压缩包到主包目录
     * @param archivePath 压缩包路径
     * @param mainPackPath 主包路径
     * @param password 密码（可选）
     */
    suspend fun extractToMainPack(
        archivePath: String,
        mainPackPath: String,
        password: String? = null
    ): ExtractResult = withContext(Dispatchers.IO) {
        if (_isExtracting.value) {
            return@withContext ExtractResult(false, errorMessage = "正在解压中")
        }
        
        _isExtracting.value = true
        _extractProgress.value = 0
        _extractStatus.value = "准备解压到主包..."
        
        try {
            val archiveFile = File(archivePath)
            if (!archiveFile.exists()) {
                return@withContext ExtractResult(false, errorMessage = "压缩包不存在")
            }
            
            val outputDir = File(mainPackPath)
            
            // 确保目标目录存在
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }
            
            _extractStatus.value = "正在解压: ${archiveFile.name} -> ${outputDir.name}"
            
            // 根据后缀选择解压方式
            val result = if (archivePath.endsWith(".7z", ignoreCase = true)) {
                extract7z(archivePath, outputDir.absolutePath, password)
            } else {
                extractZip(archivePath, outputDir.absolutePath, password)
            }
            
            if (result.success) {
                _extractStatus.value = "解压完成: ${result.extractedCount} 个文件"
                Log.d(TAG, "解压成功: $archivePath -> ${outputDir.absolutePath}")
            } else {
                _extractStatus.value = "解压失败: ${result.errorMessage}"
            }
            
            result
            
        } catch (e: PasswordRequiredException) {
            Log.w(TAG, "解压需要密码")
            _extractStatus.value = "需要密码"
            ExtractResult(false, errorMessage = "需要密码")
        } catch (e: Exception) {
            Log.e(TAG, "解压失败", e)
            _extractStatus.value = "解压失败: ${e.message}"
            ExtractResult(false, errorMessage = e.message)
        } finally {
            _isExtracting.value = false
            _extractProgress.value = 100
        }
    }
    
    /**
     * 使用 Commons Compress 解压 7z 文件
     */
    private fun extract7z(
        archivePath: String,
        outputPath: String,
        password: String?
    ): ExtractResult {
        return try {
            val file = File(archivePath)
            val archiveSize = file.length()
            val startTime = System.currentTimeMillis()
            
            Log.d(TAG, "开始解压 7z 文件: ${file.name}, 大小: ${archiveSize} bytes")
            
            // 如果有密码，需要传入 char[]
            @Suppress("DEPRECATION")
            val sevenZFile = if (password.isNullOrEmpty()) {
                SevenZFile(file)
            } else {
                SevenZFile(file, password.toCharArray())
            }
            
            var extractedCount = 0
            var extractedSize = 0L
            var lastProgressUpdate = 0L
            var lastTimeUpdate = 0L
            var entry = sevenZFile.nextEntry
            
            // 使用 IoOptimizer 的缓冲区
            val bufferSize = optimalBufferSize
            val buffer = com.example.tfgwj.utils.IoOptimizer.acquireBuffer()
            
            Log.d(TAG, "开始解压 7z 文件，缓冲区: ${buffer.size / 1024}KB")
            
            try {
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val outFile = File(outputPath, entry.name)
                        outFile.parentFile?.mkdirs()
                        
                        Log.d(TAG, "正在解压文件: ${entry.name}, 大小: ${entry.size} bytes")
                        
                        // 使用智能缓冲区提高写入性能
                        BufferedOutputStream(FileOutputStream(outFile), buffer.size).use { bos ->
                            var len: Int
                            while (sevenZFile.read(buffer).also { len = it } != -1) {
                                bos.write(buffer, 0, len)
                                extractedSize += len
                                
                                val now = System.currentTimeMillis()
                                
                                // 进度更新条件：
                                // 1. 每256KB更新一次进度（降低阈值）
                                // 2. 或每2秒更新一次（缩短时间间隔）
                                val shouldUpdateProgress = (extractedSize - lastProgressUpdate > 256 * 1024) || 
                                                           (now - lastTimeUpdate > 2000)
                                
                                if (shouldUpdateProgress) {
                                    val progress = if (archiveSize > 0) {
                                        ((extractedSize * 100) / archiveSize).toInt()
                                    } else {
                                        100
                                    }
                                    _extractProgress.value = progress.coerceIn(0, 100)
                                    lastProgressUpdate = extractedSize
                                    lastTimeUpdate = now
                                    
                                    // 计算速度
                                    val elapsed = now - startTime
                                    if (elapsed > 0) {
                                        val elapsedSeconds = elapsed / 1000.0
                                        val speedKb = (extractedSize / 1024) / elapsedSeconds
                                        _extractStatus.value = "正在解压: ${entry.name} (${String.format("%.1f", speedKb)} KB/s)"
                                    }
                                }
                            }
                        }
                        
                        extractedCount++
                        
                        // 每个文件完成后也更新一次状态（确保至少显示一些更新）
                        val now = System.currentTimeMillis()
                        if (now - lastTimeUpdate > 1000) {
                            val progress = if (archiveSize > 0) {
                                ((extractedSize * 100) / archiveSize).toInt()
                            } else {
                                100
                            }
                            _extractProgress.value = progress.coerceIn(0, 100)
                            lastTimeUpdate = now
                            
                            val elapsed = now - startTime
                            if (elapsed > 0) {
                                val elapsedSeconds = elapsed / 1000.0
                                val speedKb = (extractedSize / 1024) / elapsedSeconds
                                _extractStatus.value = "已完成: $extractedCount 个文件 (${String.format("%.1f", speedKb)} KB/s)"
                            }
                        }
                        
                        Log.d(TAG, "已完成: ${entry.name}, 总计: $extractedCount 个文件")
                    }
                    entry = sevenZFile.nextEntry
                }
            } finally {
                com.example.tfgwj.utils.IoOptimizer.releaseBuffer(buffer)
            }
            
            sevenZFile.close()
            
            Log.d(TAG, "7z 解压完成: 提取了 $extractedCount 个文件")
            
            ExtractResult(
                success = true,
                outputPath = outputPath,
                extractedCount = extractedCount
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "7z 解压错误", e)
            // 捕获密码错误
            if (e.message?.contains("Password") == true || e.message?.contains("Bad 7z signature") == true) {
                 if (password.isNullOrEmpty()) throw PasswordRequiredException()
                 return ExtractResult(false, errorMessage = "密码错误")
            }
            ExtractResult(false, errorMessage = e.message ?: "7z 解压错误")
        }
    }

    /**
     * 使用 zip4j 解压 ZIP 文件（稳定版 - 单线程）
     */
    private fun extractZip(
        archivePath: String,
        outputPath: String,
        password: String?
    ): ExtractResult {
        return try {
            val archiveFile = File(archivePath)
            val archiveSize = archiveFile.length()
            val startTime = System.currentTimeMillis()
            
            Log.d(TAG, "开始解压 ZIP 文件: ${archivePath}, 大小: ${archiveSize} bytes")
            
            val zipFile = ZipFile(archivePath)
            
            // 设置密码
            if (!password.isNullOrEmpty()) {
                zipFile.setPassword(password.toCharArray())
            }
            
            // 检查是否加密
            if (zipFile.isEncrypted && password.isNullOrEmpty()) {
                throw PasswordRequiredException()
            }
            
            // 获取所有文件头
            val fileHeaders = zipFile.fileHeaders
            val totalFiles = fileHeaders.size
            
            Log.d(TAG, "共 $totalFiles 个文件需要解压（单线程模式）")
            
            if (totalFiles == 0) {
                Log.w(TAG, "压缩包为空或只包含目录")
                return ExtractResult(
                    success = true,
                    outputPath = outputPath,
                    extractedCount = 0,
                    errorMessage = "压缩包中没有文件（可能只包含目录）"
                )
            }
            
            var extractedCount = 0
            var extractedSize = 0L
            
            // 逐个文件解压（单线程，确保稳定性）
            for (header in fileHeaders) {
                if (!header.isDirectory) {
                    val outputFile = File(outputPath, header.fileName)
                    outputFile.parentFile?.mkdirs()
                    
                    // 使用 IoOptimizer 的缓冲区
                    val buffer = com.example.tfgwj.utils.IoOptimizer.acquireBuffer()
                    try {
                        BufferedOutputStream(FileOutputStream(outputFile), buffer.size).use { bos ->
                            val inputStream = zipFile.getInputStream(header)
                            var len: Int
                            while (inputStream.read(buffer).also { len = it } != -1) {
                                bos.write(buffer, 0, len)
                                extractedSize += len
                            }
                            inputStream.close()
                            bos.flush()
                        }
                    } finally {
                        com.example.tfgwj.utils.IoOptimizer.releaseBuffer(buffer)
                    }
                    
                    extractedCount++
                    
                    // 更新进度
                    val progress = (extractedCount * 100) / totalFiles
                    _extractProgress.value = progress
                    
                    // 计算速度
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed > 0) {
                        val elapsedSeconds = elapsed / 1000.0
                        val speedKb = (extractedSize / 1024) / elapsedSeconds
                        _extractStatus.value = "正在解压: ${header.fileName} (${String.format("%.1f", speedKb)} KB/s)"
                    } else {
                        _extractStatus.value = "正在解压: ${header.fileName}"
                    }
                }
            }
            
            Log.d(TAG, "ZIP 解压成功: 提取了 $extractedCount 个文件")
            
            ExtractResult(
                success = true,
                outputPath = outputPath,
                extractedCount = extractedCount
            )
            
        } catch (e: ZipException) {
            Log.e(TAG, "ZIP 解压异常", e)
            when {
                e.message?.contains("Wrong Password", ignoreCase = true) == true -> {
                    ExtractResult(false, errorMessage = "密码错误")
                }
                e.type == ZipException.Type.WRONG_PASSWORD -> {
                     ExtractResult(false, errorMessage = "密码错误")    
                }
                e.message?.contains("encrypted") == true || e.type == ZipException.Type.TASK_CANCELLED_EXCEPTION -> {
                    if (password.isNullOrEmpty()) throw PasswordRequiredException()
                     ExtractResult(false, errorMessage = "需要密码")
                }
                else -> {
                    ExtractResult(false, errorMessage = e.message)
                }
            }
        } catch (e: PasswordRequiredException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "解压错误", e)
            ExtractResult(false, errorMessage = e.message)
        }
    }
    
    /**
     * 检查压缩包是否需要密码
     */
    fun isPasswordRequired(archivePath: String): Boolean {
        return try {
            if (archivePath.endsWith(".7z", ignoreCase = true)) {
                 // 7z 比较难快速判断，尝试无密码打开
                 try {
                     @Suppress("DEPRECATION")
                     val szf = SevenZFile(File(archivePath))
                     szf.close()
                     false
                 } catch (e: Exception) {
                     // 假设报错就是需要密码 (不太严谨但可用)
                     true
                 }
            } else {
                val zipFile = ZipFile(archivePath)
                zipFile.isEncrypted
            }
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 获取压缩包内文件列表
     */
    fun getFileList(archivePath: String, password: String? = null): List<String> {
        return try {
            val zipFile = ZipFile(archivePath)
            if (!password.isNullOrEmpty()) {
                zipFile.setPassword(password.toCharArray())
            }
            zipFile.fileHeaders.map { it.fileName }
        } catch (e: Exception) {
            Log.e(TAG, "获取文件列表失败", e)
            emptyList()
        }
    }
}
