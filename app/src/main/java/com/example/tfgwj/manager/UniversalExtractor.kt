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
import com.example.tfgwj.utils.IoRateCalculator
import com.example.tfgwj.utils.PauseControl
import java.util.zip.ZipInputStream
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
    
    private val _currentSpeed = MutableStateFlow(0f)
    val currentSpeed: StateFlow<Float> = _currentSpeed.asStateFlow()
    
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
     * ZIP 解压 (流式架构 - 内存占用极低)
     */
    private fun extractZip(path: String, outputDir: String, password: String?): ExtractResult {
        return try {
            // Zip4j 不完全支持纯流式 IO (需要 RandomAccessFile 读取 Central Directory)
            // 为了极致内存优化，标准 Zip 我们使用 Java 原生 ZipInputStream (它就是纯流式的)
            // 但原生 ZipInputStream 不支持密码。如果有密码，回退到 Zip4j (通过 FileAPI)
            
            if (!password.isNullOrEmpty()) {
                return extractZipWithPassword(path, outputDir, password)
            }
            
            val ioRateCalculator = IoRateCalculator()
            var extractedCount = 0
            var totalProcessedBytes: Long = 0
            val file = File(path)
            val totalSize = file.length()
            
            FileInputStream(path).use { fis ->
                BufferedInputStream(fis).use { bis ->
                    ZipInputStream(bis).use { zis ->
                        var entry = zis.nextEntry
                        val buffer = ByteArray(BUFFER_SIZE)
                        
                        while (entry != null) {
                             // 1. 检查暂停
                            kotlinx.coroutines.runBlocking { PauseControl.waitIfPaused() }
                            
                            val fileName = entry.name
                            val outFile = File(outputDir, fileName)
                            
                            // 2. Zip Slip 安全检查
                            val canonicalDest = File(outputDir).canonicalPath
                            val canonicalEntry = outFile.canonicalPath
                            if (!canonicalEntry.startsWith(canonicalDest + File.separator)) {
                                throw SecurityException("Zip Slip 检测: $fileName")
                            }
                            
                            _currentFile.value = fileName
                            
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos ->
                                    var len: Int
                                    while (zis.read(buffer).also { len = it } > 0) {
                                        fos.write(buffer, 0, len)
                                        totalProcessedBytes += len
                                        
                                        // 更新速度和进度
                                        val speed = ioRateCalculator.update(totalProcessedBytes)
                                        if (speed > 0) _currentSpeed.value = speed
                                        
                                        // 估算进度 (字节级)
                                        if (totalSize > 0) {
                                            val progress = (totalProcessedBytes * 100 / totalSize).toInt()
                                            if (progress != _progress.value) _progress.value = progress
                                        }
                                    }
                                }
                                extractedCount++
                            }
                            
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
            }
            
            ExtractResult(true, outputDir, extractedCount)
        } catch (e: Exception) {
            Log.e(TAG, "Zip 流式解压失败", e)
            ExtractResult(false, errorMessage = e.message)
        }
    }
    
    /**
     * 带密码的 Zip 解压 (使用 Zip4j)
     */
    private fun extractZipWithPassword(path: String, outputDir: String, password: String): ExtractResult {
        return try {
            val zipFile = ZipFile(path)
            if (zipFile.isEncrypted) {
                zipFile.setPassword(password.toCharArray())
            }
            
            zipFile.isRunInThread = true // 实际上我们会阻塞等待，但需要它来支持 ProgressMonitor
            val monitor = zipFile.progressMonitor
            val ioRateCalculator = IoRateCalculator()
            
            // 安全检查
            zipFile.fileHeaders.forEach { header ->
                val outFile = File(outputDir, header.fileName)
                val canonicalDest = File(outputDir).canonicalPath
                val canonicalEntry = outFile.canonicalPath
                if (!canonicalEntry.startsWith(canonicalDest + File.separator)) {
                     throw SecurityException("Zip Slip: ${header.fileName}")
                }
            }
            
            // 异步解压但阻塞等待进度
            Thread { 
                try { zipFile.extractAll(outputDir) } catch(e:Exception){} 
            }.start()
            
            while (monitor.state == ProgressMonitor.State.BUSY) {
                kotlinx.coroutines.runBlocking { PauseControl.waitIfPaused() }
                
                _progress.value = monitor.percentDone
                _currentFile.value = monitor.fileName ?: ""
                
                // Zip4j 不直接提供字节处理量，很难计算精确速度，这里暂时用已处理大小估算
                val processed = monitor.workCompleted
                val speed = ioRateCalculator.update(processed)
                _currentSpeed.value = speed
                
                Thread.sleep(100)
            }
            
            if (monitor.result == ProgressMonitor.Result.SUCCESS) {
                val count = File(outputDir).walkTopDown().count { it.isFile }
                ExtractResult(true, outputDir, count)
            } else {
                ExtractResult(false, errorMessage = monitor.exception?.message ?: "解压失败")
            }
            
        } catch (e: Exception) {
            ExtractResult(false, errorMessage = e.message)
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
                // Zip Slip 检查
                val outFile = File(outputDir, entry.name)
                val canonicalDest = File(outputDir).canonicalPath
                val canonicalEntry = outFile.canonicalPath
                if (!canonicalEntry.startsWith(canonicalDest)) {
                    throw SecurityException("检测到 Zip Slip 攻击尝试: ${entry.name}")
                }
                
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
