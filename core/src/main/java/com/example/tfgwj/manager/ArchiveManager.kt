package com.example.tfgwj.manager

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.example.tfgwj.model.ArchiveFile
import com.example.tfgwj.security.ArchiveEntryMetadata
import com.example.tfgwj.security.ArchiveEntryValidator
import com.example.tfgwj.security.ArchiveSafetyGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

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
    suspend fun scanArchives(directories: List<String> = emptyList()) =
        withContext(Dispatchers.IO) {
            if (_isScanning.value) {
                Log.w(TAG, "Scan already in progress")
                return@withContext
            }

            _isScanning.value = true
            shouldCancelScan.set(false)
            _archiveFiles.value = emptyList()

            val scanDirs =
                if (directories.isEmpty()) {
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
    private fun scanDirectoryRecursively(
        dir: File,
        foundFiles: MutableList<ArchiveFile>,
    ) {
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
        outputPath: String? = null,
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
        var stagingDir: File? = null

        try {
            Log.d(TAG, "Extracting archive: ${archiveFile.fileName} to $outputDir")
            when (archiveFile.fileType.lowercase()) {
                "rar" -> {
                    Log.w(TAG, "RAR format rejected: no safe in-process extractor available")
                    _extractionResult.value = ExtractionResult(false, null, archiveFile, "解压失败")
                    return@withContext
                }
                "tar", "tgz", "tar.gz", "bz2", "xz" -> {
                    Log.w(TAG, "${archiveFile.fileType.uppercase()} format not supported by ArchiveManager, use UniversalExtractor")
                    _extractionResult.value = ExtractionResult(false, null, archiveFile, "解压失败")
                    return@withContext
                }
            }

            stagingDir = ArchiveSafetyGuard.newStagingDirectory(outputDirFile)
            val success =
                when (archiveFile.fileType.lowercase()) {
                    "zip", "jar" -> extractZip(archiveFile.file, stagingDir, password)
                    "gz", "gzip" -> extractGz(archiveFile.file, stagingDir)
                    "7z" -> extract7z(archiveFile.file, stagingDir, password)
                    else -> {
                        Log.e(TAG, "Unsupported archive type: ${archiveFile.fileType}")
                        false
                    }
                }

            if (success) {
                if (outputDirFile.exists()) {
                    ArchiveSafetyGuard.discardDirectory(outputDirFile)
                }
                if (!stagingDir.renameTo(outputDirFile)) {
                    stagingDir.copyRecursively(outputDirFile, overwrite = true)
                    ArchiveSafetyGuard.discardDirectory(stagingDir)
                }
                stagingDir = null
                _extractionResult.value = ExtractionResult(true, outputDir, archiveFile, "解压成功")
            } else {
                ArchiveSafetyGuard.discardDirectory(stagingDir)
                stagingDir = null
                _extractionResult.value = ExtractionResult(false, null, archiveFile, "解压失败")
            }
            _extractionProgress.value = 1f
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting archive", e)
            stagingDir?.let { ArchiveSafetyGuard.discardDirectory(it) }
            _extractionResult.value =
                ExtractionResult(
                    success = false,
                    outputPath = null,
                    archiveFile = archiveFile,
                    message = "解压错误: ${e.message}",
                )
        } finally {
            _isExtracting.value = false
        }
    }

    /**
     * 解压ZIP文件
     * 先流式扫描并校验所有条目，再重新打开归档写入，避免恶意条目在发现前留下部分内容。
     */
    private fun extractZip(
        zipFile: File,
        outputDir: File,
        password: String?,
    ): Boolean {
        return try {
            // 第一遍：校验所有条目。
            FileInputStream(zipFile).use { fis ->
                ZipInputStream(fis).use { zis ->
                    val seenEntries = HashSet<String>()
                    val validationBuffer = ByteArray(8192)
                    var validatedTotalBytes = 0L
                    var probe = zis.nextEntry
                    while (probe != null) {
                        val normalizedName = probe.name.replace('\\', '/')
                        require(seenEntries.add(normalizedName)) { "重复压缩包条目: ${probe.name}" }
                        ArchiveSafetyGuard.validateEntry(probe.name)
                        var entryBytes = 0L
                        var len = 0
                        while (!probe.isDirectory && zis.read(validationBuffer).also { len = it } > 0) {
                            entryBytes += len
                            require(entryBytes <= ArchiveSafetyGuard.MAX_ENTRY_SIZE_BYTES) {
                                "压缩包单文件超过大小限制: ${probe.name}"
                            }
                            validatedTotalBytes = ArchiveSafetyGuard.addBytesWithinLimit(validatedTotalBytes, len.toLong())
                        }
                        zis.closeEntry()
                        probe = zis.nextEntry
                    }
                }
            }

            // 第二遍：真正写入。
            FileInputStream(zipFile).use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry: ZipEntry?
                    while (zis.nextEntry.also { entry = it } != null) {
                        if (shouldCancelExtract.get()) return false

                        val entryFile = ArchiveEntryValidator.resolveWithin(outputDir, entry!!.name)

                        if (entry!!.isDirectory) {
                            entryFile.mkdirs()
                        } else {
                            entryFile.parentFile?.mkdirs()
                            FileOutputStream(entryFile).use { fos ->
                                val buffer = ByteArray(8192)
                                var entryBytes = 0L
                                var totalWritten = 0L
                                var bytesRead = 0
                                while (zis.read(buffer).also { bytesRead = it } != -1) {
                                    entryBytes += bytesRead
                                    require(entryBytes <= ArchiveSafetyGuard.MAX_ENTRY_SIZE_BYTES) {
                                        "压缩包单文件超过大小限制: ${entry!!.name}"
                                    }
                                    totalWritten = ArchiveSafetyGuard.addBytesWithinLimit(totalWritten, bytesRead.toLong())
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
     * 解压 7z 文件。使用受统一条目校验保护的内置实现，避免将压缩包路径和
     * 密码拼接为 Shizuku shell 命令。
     */
    private suspend fun extract7z(
        archiveFile: File,
        outputDir: File,
        password: String?,
    ): Boolean {
        val result = UniversalExtractor.getInstance().extract(
            archiveFile.absolutePath,
            outputDir.absolutePath,
            password,
        )
        if (!result.success) Log.e(TAG, "7z extraction failed: ${result.errorMessage}")
        return result.success
    }

    /**
     * RAR 暂未接入可验证的逐条目安全解压实现，因此禁止将其交给任意 shell
     * 命令执行；调用方会得到失败结果而非部分解压的未知状态。
     */
    private fun extractRar(
        archiveFile: File,
        outputDir: File,
        password: String?,
    ): Boolean {
        Log.w(TAG, "RAR extraction rejected until a safe in-process extractor is available: ${archiveFile.name}")
        return false
    }

    /**
     * 解压GZ文件（使用原生Java GZIPInputStream）
     */
    private fun extractGz(
        gzFile: File,
        outputDir: File,
    ): Boolean {
        return try {
            val outputFile = File(outputDir, gzFile.nameWithoutExtension)
            ArchiveSafetyGuard.validateEntry(outputFile.name)
            FileInputStream(gzFile).use { fis ->
                GZIPInputStream(BufferedInputStream(fis)).use { gzInput ->
                    FileOutputStream(outputFile).use { fos ->
                        val buffer = ByteArray(8192)
                        var written = 0L
                        var bytesRead = 0
                        while (gzInput.read(buffer).also { bytesRead = it } != -1) {
                            written = ArchiveSafetyGuard.addBytesWithinLimit(written, bytesRead.toLong())
                            require(written <= ArchiveSafetyGuard.MAX_ENTRY_SIZE_BYTES) {
                                "压缩包单文件超过大小限制: ${outputFile.name}"
                            }
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
    val message: String,
)
