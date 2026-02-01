package com.example.tfgwj.manager

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.progress.ProgressMonitor
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.*

/**
 * 通用解压器
 * 支持 ZIP、7z、RAR、TAR、GZ、XZ 等格式
 */
class UniversalExtractor private constructor() {
    
    companion object {
        private const val TAG = "UniversalExtractor"
        private const val BUFFER_SIZE = 8192
        
        // 支持的格式
        val SUPPORTED_EXTENSIONS = setOf(
            "zip", "7z", "rar", "tar", "gz", "tgz", "xz", "tar.gz", "tar.xz"
        )
        
        @Volatile
        private var instance: UniversalExtractor? = null
        
        fun getInstance(): UniversalExtractor {
            return instance ?: synchronized(this) {
                instance ?: UniversalExtractor().also { instance = it }
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
    
    // 状态
    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()
    
    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()
    
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()
    
    private val _currentFile = MutableStateFlow("")
    val currentFile: StateFlow<String> = _currentFile.asStateFlow()
    
    /**
     * 解压到指定目录
     */
    suspend fun extract(
        archivePath: String,
        outputDir: String,
        password: String? = null
    ): ExtractResult = withContext(Dispatchers.IO) {
        if (_isExtracting.value) {
            return@withContext ExtractResult(false, errorMessage = "正在解压中")
        }
        
        _isExtracting.value = true
        _progress.value = 0
        _status.value = "准备解压..."
        _currentFile.value = ""
        
        try {
            val file = File(archivePath)
            if (!file.exists()) {
                return@withContext ExtractResult(false, errorMessage = "文件不存在")
            }
            
            val extension = getExtension(file.name).lowercase()
            _status.value = "正在解压: ${file.name}"
            
            // 确保输出目录存在
            File(outputDir).mkdirs()
            
            val result = when (extension) {
                "zip" -> extractZip(archivePath, outputDir, password)
                "7z" -> extract7z(archivePath, outputDir, password)
                "tar" -> extractTar(archivePath, outputDir)
                "gz", "tgz" -> extractGzip(archivePath, outputDir)
                "xz" -> extractXz(archivePath, outputDir)
                "tar.gz" -> extractTarGz(archivePath, outputDir)
                "tar.xz" -> extractTarXz(archivePath, outputDir)
                "rar" -> ExtractResult(false, errorMessage = "RAR 格式暂不支持，请使用 ZIP 或 7z")
                else -> ExtractResult(false, errorMessage = "不支持的格式: $extension")
            }
            
            if (result.success) {
                _status.value = "解压完成: ${result.extractedCount} 个文件"
            } else {
                _status.value = "解压失败: ${result.errorMessage}"
            }
            
            result
            
        } catch (e: Exception) {
            Log.e(TAG, "解压失败", e)
            ExtractResult(false, errorMessage = e.message ?: "未知错误")
        } finally {
            _isExtracting.value = false
            _progress.value = 100
        }
    }
    
    /**
     * ZIP 解压
     */
    private fun extractZip(path: String, outputDir: String, password: String?): ExtractResult {
        return try {
            val zipFile = ZipFile(path)
            
            if (!password.isNullOrEmpty()) {
                zipFile.setPassword(password.toCharArray())
            }
            
            if (zipFile.isEncrypted && password.isNullOrEmpty()) {
                return ExtractResult(false, errorMessage = "需要密码")
            }
            
            zipFile.isRunInThread = true
            val monitor = zipFile.progressMonitor
            
            zipFile.extractAll(outputDir)
            
            while (monitor.state == ProgressMonitor.State.BUSY) {
                _progress.value = monitor.percentDone
                _currentFile.value = monitor.fileName ?: ""
                Thread.sleep(100)
            }
            
            val count = File(outputDir).walkTopDown().count { it.isFile }
            ExtractResult(true, outputDir, count)
            
        } catch (e: ZipException) {
            when {
                e.message?.contains("Wrong Password") == true -> ExtractResult(false, errorMessage = "密码错误")
                else -> ExtractResult(false, errorMessage = e.message)
            }
        }
    }
    
    /**
     * 7z 解压
     */
    private fun extract7z(path: String, outputDir: String, password: String?): ExtractResult {
        return try {
            val sevenZFile = if (!password.isNullOrEmpty()) {
                SevenZFile.builder().setFile(File(path)).setPassword(password).get()
            } else {
                SevenZFile.builder().setFile(File(path)).get()
            }
            
            var count = 0
            var entry: SevenZArchiveEntry? = sevenZFile.nextEntry
            val totalEntries = sevenZFile.entries.count()
            
            sevenZFile.close()
            val sevenZ = if (!password.isNullOrEmpty()) {
                SevenZFile.builder().setFile(File(path)).setPassword(password).get()
            } else {
                SevenZFile.builder().setFile(File(path)).get()
            }
            
            entry = sevenZ.nextEntry
            while (entry != null) {
                val outFile = File(outputDir, entry.name)
                _currentFile.value = entry.name
                
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var len: Int
                        while (sevenZ.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                    }
                    count++
                }
                
                _progress.value = if (totalEntries > 0) (count * 100 / totalEntries) else 0
                entry = sevenZ.nextEntry
            }
            
            sevenZ.close()
            ExtractResult(true, outputDir, count)
            
        } catch (e: Exception) {
            Log.e(TAG, "7z 解压失败", e)
            ExtractResult(false, errorMessage = e.message)
        }
    }
    
    /**
     * TAR 解压
     */
    private fun extractTar(path: String, outputDir: String): ExtractResult {
        return extractArchive(TarArchiveInputStream(FileInputStream(path)), outputDir)
    }
    
    /**
     * GZIP 解压
     */
    private fun extractGzip(path: String, outputDir: String): ExtractResult {
        return try {
            val file = File(path)
            val outputName = file.name.removeSuffix(".gz").removeSuffix(".tgz")
            val outputFile = File(outputDir, if (outputName.endsWith(".tar")) outputName else outputName)
            
            GzipCompressorInputStream(BufferedInputStream(FileInputStream(path))).use { gzIn ->
                FileOutputStream(outputFile).use { fos ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var len: Int
                    while (gzIn.read(buffer).also { len = it } > 0) {
                        fos.write(buffer, 0, len)
                    }
                }
            }
            
            // 如果是 .tar.gz，继续解压 tar
            if (outputFile.name.endsWith(".tar")) {
                val result = extractTar(outputFile.absolutePath, outputDir)
                outputFile.delete()
                result
            } else {
                ExtractResult(true, outputDir, 1)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "GZIP 解压失败", e)
            ExtractResult(false, errorMessage = e.message)
        }
    }
    
    /**
     * XZ 解压
     */
    private fun extractXz(path: String, outputDir: String): ExtractResult {
        return try {
            val file = File(path)
            val outputName = file.name.removeSuffix(".xz")
            val outputFile = File(outputDir, outputName)
            
            XZCompressorInputStream(BufferedInputStream(FileInputStream(path))).use { xzIn ->
                FileOutputStream(outputFile).use { fos ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var len: Int
                    while (xzIn.read(buffer).also { len = it } > 0) {
                        fos.write(buffer, 0, len)
                    }
                }
            }
            
            // 如果是 .tar.xz，继续解压 tar
            if (outputFile.name.endsWith(".tar")) {
                val result = extractTar(outputFile.absolutePath, outputDir)
                outputFile.delete()
                result
            } else {
                ExtractResult(true, outputDir, 1)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "XZ 解压失败", e)
            ExtractResult(false, errorMessage = e.message)
        }
    }
    
    /**
     * TAR.GZ 解压
     */
    private fun extractTarGz(path: String, outputDir: String): ExtractResult {
        return try {
            val tarIn = TarArchiveInputStream(
                GzipCompressorInputStream(BufferedInputStream(FileInputStream(path)))
            )
            extractArchive(tarIn, outputDir)
        } catch (e: Exception) {
            ExtractResult(false, errorMessage = e.message)
        }
    }
    
    /**
     * TAR.XZ 解压
     */
    private fun extractTarXz(path: String, outputDir: String): ExtractResult {
        return try {
            val tarIn = TarArchiveInputStream(
                XZCompressorInputStream(BufferedInputStream(FileInputStream(path)))
            )
            extractArchive(tarIn, outputDir)
        } catch (e: Exception) {
            ExtractResult(false, errorMessage = e.message)
        }
    }
    
    /**
     * 通用 Archive 解压
     */
    private fun extractArchive(archiveIn: ArchiveInputStream<*>, outputDir: String): ExtractResult {
        return try {
            var count = 0
            var entry: ArchiveEntry? = archiveIn.nextEntry
            
            while (entry != null) {
                val outFile = File(outputDir, entry.name)
                _currentFile.value = entry.name
                
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var len: Int
                        while (archiveIn.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                    }
                    count++
                }
                
                entry = archiveIn.nextEntry
            }
            
            archiveIn.close()
            ExtractResult(true, outputDir, count)
            
        } catch (e: Exception) {
            Log.e(TAG, "Archive 解压失败", e)
            ExtractResult(false, errorMessage = e.message)
        }
    }
    
    /**
     * 获取文件扩展名
     */
    private fun getExtension(fileName: String): String {
        val name = fileName.lowercase()
        return when {
            name.endsWith(".tar.gz") -> "tar.gz"
            name.endsWith(".tar.xz") -> "tar.xz"
            name.endsWith(".tgz") -> "gz"
            else -> name.substringAfterLast('.', "")
        }
    }
    
    /**
     * 检查是否需要密码
     */
    fun isPasswordRequired(path: String): Boolean {
        val extension = getExtension(File(path).name)
        return when (extension) {
            "zip" -> {
                try {
                    ZipFile(path).isEncrypted
                } catch (e: Exception) {
                    false
                }
            }
            "7z" -> {
                // 7z 无法预先检测，尝试打开
                try {
                    SevenZFile.builder().setFile(File(path)).get().close()
                    false
                } catch (e: Exception) {
                    e.message?.contains("password") == true
                }
            }
            else -> false
        }
    }
    
    /**
     * 检查格式是否支持
     */
    fun isSupported(fileName: String): Boolean {
        val ext = getExtension(fileName)
        return ext in SUPPORTED_EXTENSIONS || ext == "tar.gz" || ext == "tar.xz"
    }
}
