package com.example.tfgwj.manager

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.example.tfgwj.model.ArchiveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.concurrent.atomic.AtomicBoolean

class ArchiveManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ArchiveManager"
        private const val SCAN_DEBOUNCE_MS = 500L
        
        @Volatile
        private var instance: ArchiveManager? = null
        
        fun getInstance(context: Context): ArchiveManager {
            return instance ?: synchronized(this) {
                instance ?: ArchiveManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val _archiveFiles = MutableStateFlow<List<ArchiveFile>>(emptyList())
    val archiveFiles: StateFlow<List<ArchiveFile>> = _archiveFiles.asStateFlow()
    
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    
    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()
    
    private val _extractionProgress = MutableStateFlow(0f)
    val extractionProgress: StateFlow<Float> = _extractionProgress.asStateFlow()
    
    private val _extractionResult = MutableStateFlow<ExtractionResult?>(null)
    val extractionResult: StateFlow<ExtractionResult?> = _extractionResult.asStateFlow()
    
    private val shouldCancelScan = AtomicBoolean(false)
    private val shouldCancelExtract = AtomicBoolean(false)
    
    /**
     * 扫描压缩包文件
     * @param directories 要扫描的目录列表
     */
    suspend fun scanArchives(directories: List<String> = emptyList()) = withContext(Dispatchers.IO) {
        if (_isScanning.value) {
            Log.w(TAG, "Scan already in progress")
            return@withContext
        }
        
        _isScanning.value = true
        shouldCancelScan.set(false)
        _archiveFiles.value = emptyList()
        
        val scanDirs = if (directories.isEmpty()) {
            getDefaultScanDirectories()
        } else {
            directories
        }
        
        val foundFiles = mutableListOf<ArchiveFile>()

        try {
            for (dirPath in scanDirs) {
                if (shouldCancelScan.get()) break

                val dir = File(dirPath)
                if (!dir.exists() || !dir.isDirectory) {
                    Log.d(TAG, "Directory does not exist: $dirPath")
                    continue
                }

                Log.d(TAG, "Scanning directory: $dirPath")
                scanDirectoryRecursively(dir, foundFiles)
            }
            
            _archiveFiles.value = foundFiles.sortedByDescending { it.fileSize }
            Log.d(TAG, "Scan completed: found ${foundFiles.size} archive files")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning archives", e)
        } finally {
            _isScanning.value = false
        }
    }
    
    /**
     * 递归扫描目录
     */
    private fun scanDirectoryRecursively(dir: File, foundFiles: MutableList<ArchiveFile>) {
        if (shouldCancelScan.get()) return
        
        dir.listFiles()?.forEach { file ->
            if (shouldCancelScan.get()) return
            
            if (file.isDirectory) {
                scanDirectoryRecursively(file, foundFiles)
            } else if (file.isFile && ArchiveFile.isSupportedArchive(file.name)) {
                foundFiles.add(ArchiveFile(file))
                Log.d(TAG, "Found archive: ${file.name}")
            }
        }
    }
    
    /**
     * 获取默认扫描目录
     */
    private fun getDefaultScanDirectories(): List<String> {
        val directories = mutableListOf<String>()
        
        try {
            // 下载目录
            context.getExternalFilesDir(null)?.parentFile?.parentFile?.parentFile?.let { root ->
                val downloadDir = File(root, "Download")
                if (downloadDir.exists()) {
                    directories.add(downloadDir.absolutePath)
                }
                
                // 123云盘目录
                val yunpanDir = File(root, "123云盘")
                if (yunpanDir.exists()) {
                    directories.add(yunpanDir.absolutePath)
                }
            }
            
            // 备用方案
            val downloadPath = "${context.getExternalFilesDir(null)?.absolutePath?.split("/Android")[0]}/Download"
            if (File(downloadPath).exists() && downloadPath !in directories) {
                directories.add(downloadPath)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting default scan directories", e)
        }
        
        return directories
    }
    
    /**
     * 解压压缩包
     * @param archiveFile 压缩包文件
     * @param password 密码（可选）
     * @param outputPath 输出路径（可选，默认使用缓存目录）
     */
    suspend fun extractArchive(
        archiveFile: ArchiveFile,
        password: String? = null,
        outputPath: String? = null
    ) = withContext(Dispatchers.IO) {
        if (_isExtracting.value) {
            Log.w(TAG, "Extraction already in progress")
            return@withContext
        }
        
        _isExtracting.value = true
        shouldCancelExtract.set(false)
        _extractionProgress.value = 0f
        _extractionResult.value = null
        
        val outputDir = outputPath ?: getDefaultExtractDirectory(archiveFile.fileName)
        val outputDirFile = File(outputDir)
        
        try {
            Log.d(TAG, "Extracting archive: ${archiveFile.fileName} to $outputDir")
            
            // 创建输出目录
            if (!outputDirFile.exists()) {
                outputDirFile.mkdirs()
            }
            
            // 根据文件类型选择解压方法
            val success = when (archiveFile.fileType.lowercase()) {
                "zip", "jar" -> extractZip(archiveFile.file, outputDirFile, password)
                "gz", "gzip" -> extractGz(archiveFile.file, outputDirFile)
                "7z" -> {
                    Toast.makeText(context, "7z格式需要Shizuku权限或系统安装7z命令", Toast.LENGTH_SHORT).show()
                    extract7z(archiveFile.file, outputDirFile, password)
                }
                "rar" -> {
                    Toast.makeText(context, "RAR格式需要Shizuku权限或系统安装unrar命令", Toast.LENGTH_SHORT).show()
                    extractRar(archiveFile.file, outputDirFile, password)
                }
                "tar", "tgz", "tar.gz", "bz2", "xz" -> {
                    Toast.makeText(context, "${archiveFile.fileType.uppercase()}格式暂不支持，请使用ZIP或GZ格式", Toast.LENGTH_SHORT).show()
                    false
                }
                else -> {
                    Log.e(TAG, "Unsupported archive type: ${archiveFile.fileType}")
                    Toast.makeText(context, "不支持的格式: ${archiveFile.fileType}", Toast.LENGTH_SHORT).show()
                    false
                }
            }
            
            val result = if (success) {
                ExtractionResult(
                    success = true,
                    outputPath = outputDir,
                    archiveFile = archiveFile,
                    message = "解压成功"
                )
            } else {
                ExtractionResult(
                    success = false,
                    outputPath = null,
                    archiveFile = archiveFile,
                    message = "解压失败"
                )
            }
            
            _extractionResult.value = result
            _extractionProgress.value = 1f
            
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting archive", e)
            _extractionResult.value = ExtractionResult(
                success = false,
                outputPath = null,
                archiveFile = archiveFile,
                message = "解压错误: ${e.message}"
            )
        } finally {
            _isExtracting.value = false
        }
    }
    
    /**
     * 解压ZIP文件
     */
    private fun extractZip(zipFile: File, outputDir: File, password: String?): Boolean {
        return try {
            val passwordBytes = password?.toByteArray()
            
            FileInputStream(zipFile).use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry: ZipEntry?
                    while (zis.nextEntry.also { entry = it } != null) {
                        if (shouldCancelExtract.get()) return false
                        
                        val entryFile = File(outputDir, entry!!.name)
                        
                        if (entry!!.isDirectory) {
                            entryFile.mkdirs()
                        } else {
                            entryFile.parentFile?.mkdirs()
                            FileOutputStream(entryFile).use { fos ->
                                val buffer = ByteArray(8192)
                                var bytesRead: Int
                                while (zis.read(buffer).also { bytesRead = it } != -1) {
                                    fos.write(buffer, 0, bytesRead)
                                }
                            }
                        }
                        zis.closeEntry()
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting ZIP", e)
            false
        }
    }
    
    /**
     * 解压7z文件（使用Shizuku调用系统7z命令）
     * 注意：需要系统安装7z命令（如termux）
     */
    private fun extract7z(archiveFile: File, outputDir: File, password: String?): Boolean {
        return try {
            Log.d(TAG, "Extracting 7z file using Shizuku: ${archiveFile.name}")

            val command = if (password != null) {
                "7z x \"${archiveFile.absolutePath}\" -o\"${outputDir.absolutePath}\" -p\"$password\" -y"
            } else {
                "7z x \"${archiveFile.absolutePath}\" -o\"${outputDir.absolutePath}\" -y"
            }

            val shizukuManager = com.example.tfgwj.shizuku.ShizukuManager.getInstance(context)
            if (shizukuManager.isAuthorized.value) {
                val exitCode = shizukuManager.executeCommand(command)
                if (exitCode == 0) {
                    Log.d(TAG, "7z extraction successful")
                    return true
                }
            }

            Log.e(TAG, "7z extraction failed. 提示：需要Shizuku权限或系统安装7z命令（如termux）")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting 7z: ${e.message}")
            false
        }
    }
    
    /**
     * 解压RAR文件（使用Shizuku调用系统unrar命令）
     * 注意：需要系统安装unrar命令
     */
    private fun extractRar(archiveFile: File, outputDir: File, password: String?): Boolean {
        return try {
            Log.d(TAG, "Extracting RAR file using Shizuku: ${archiveFile.name}")

            val command = if (password != null) {
                "unrar x -p\"$password\" -y \"${archiveFile.absolutePath}\" \"${outputDir.absolutePath}\""
            } else {
                "unrar x -y \"${archiveFile.absolutePath}\" \"${outputDir.absolutePath}\""
            }

            val shizukuManager = com.example.tfgwj.shizuku.ShizukuManager.getInstance(context)
            if (shizukuManager.isAuthorized.value) {
                val exitCode = shizukuManager.executeCommand(command)
                if (exitCode == 0) {
                    Log.d(TAG, "RAR extraction successful")
                    return true
                }
            }

            Log.e(TAG, "RAR extraction failed. 提示：需要Shizuku权限或系统安装unrar命令")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting RAR: ${e.message}")
            false
        }
    }
    
    /**
     * 解压GZ文件（使用原生Java GZIPInputStream）
     */
    private fun extractGz(gzFile: File, outputDir: File): Boolean {
        return try {
            val outputFile = File(outputDir, gzFile.nameWithoutExtension)
            FileInputStream(gzFile).use { fis ->
                GZIPInputStream(BufferedInputStream(fis)).use { gzInput ->
                    FileOutputStream(outputFile).use { fos ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (gzInput.read(buffer).also { bytesRead = it } != -1) {
                            fos.write(buffer, 0, bytesRead)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting GZ", e)
            false
        }
    }
    
    /**
     * 获取默认解压目录
     */
    private fun getDefaultExtractDirectory(archiveFileName: String): String {
        val baseDir = File(context.getExternalFilesDir(null), "听风改文件/extracted")
        val cleanName = archiveFileName.substringBeforeLast(".")
        return File(baseDir, cleanName).absolutePath
    }
    
    /**
     * 获取解压目录（用于缓存）
     */
    fun getCacheDirectory(): File {
        val cacheDir = File(context.getExternalFilesDir(null), "听风改文件")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        return cacheDir
    }
    
    /**
     * 取消扫描
     */
    fun cancelScan() {
        shouldCancelScan.set(true)
        Log.d(TAG, "Scan cancelled")
    }
    
    /**
     * 取消解压
     */
    fun cancelExtraction() {
        shouldCancelExtract.set(true)
        Log.d(TAG, "Extraction cancelled")
    }
    
    /**
     * 清除结果
     */
    fun clearResults() {
        _archiveFiles.value = emptyList()
        _extractionResult.value = null
        _extractionProgress.value = 0f
    }
    
    /**
     * 删除解压文件
     */
    fun deleteExtractedFiles(outputPath: String): Boolean {
        return try {
            val dir = File(outputPath)
            if (dir.exists() && dir.isDirectory) {
                deleteDirectoryRecursively(dir)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting extracted files", e)
            false
        }
    }
    
    /**
     * 递归删除目录
     */
    private fun deleteDirectoryRecursively(dir: File): Boolean {
        return try {
            if (dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    deleteDirectoryRecursively(file)
                }
            }
            dir.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete directory: ${dir.absolutePath}", e)
            false
        }
    }
}

data class ExtractionResult(
    val success: Boolean,
    val outputPath: String?,
    val archiveFile: ArchiveFile,
    val message: String
)