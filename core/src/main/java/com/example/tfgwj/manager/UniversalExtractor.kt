package com.example.tfgwj.manager

import android.util.Log
import com.example.tfgwj.security.ArchiveEntryMetadata
import com.example.tfgwj.security.ArchiveEntryValidator
import com.example.tfgwj.security.ArchiveSafetyGuard
import com.example.tfgwj.utils.IoRateCalculator
import com.example.tfgwj.utils.PauseControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.progress.ProgressMonitor
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.*
import java.util.zip.ZipInputStream

/**
 * 通用解压器
 * 支持 ZIP、7z、RAR、TAR、GZ、XZ 等格式
 */
class UniversalExtractor private constructor() {
    companion object {
        private const val TAG = "UniversalExtractor"
        private val BUFFER_SIZE: Int
            get() = com.example.tfgwj.utils.IoOptimizer.getOptimalBufferSize()

        // 支持的格式
        val SUPPORTED_EXTENSIONS =
            setOf(
                "zip",
                "7z",
                "rar",
                "tar",
                "gz",
                "tgz",
                "xz",
                "tar.gz",
                "tar.xz",
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
        val errorMessage: String? = null,
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
        password: String? = null,
    ): ExtractResult =
        withContext(Dispatchers.IO) {
            if (_isExtracting.value) {
                return@withContext ExtractResult(false, errorMessage = "正在解压中")
            }

            _isExtracting.value = true
            _progress.value = 0
            _status.value = "准备解压..."
            _currentFile.value = ""

            val finalDir = File(outputDir)
            var stagingDir: File? = null
            try {
                val file = File(archivePath)
                if (!file.exists()) {
                    return@withContext ExtractResult(false, errorMessage = "文件不存在")
                }

                val extension = getExtension(file.name).lowercase()
                if (extension == "rar") {
                    return@withContext ExtractResult(false, errorMessage = "RAR 格式暂不支持，请使用 ZIP 或 7z")
                }
                if (extension !in SUPPORTED_EXTENSIONS && extension != "tar.gz" && extension != "tar.xz") {
                    return@withContext ExtractResult(false, errorMessage = "不支持的格式: $extension")
                }

                _status.value = "正在解压: ${file.name}"
                stagingDir = ArchiveSafetyGuard.newStagingDirectory(finalDir)
                val stagingPath = stagingDir.absolutePath

                val result =
                    when (extension) {
                        "zip" -> extractZip(archivePath, stagingPath, password)
                        "7z" -> extract7z(archivePath, stagingPath, password)
                        "tar" -> extractTar(archivePath, stagingPath)
                        "gz", "tgz" -> extractGzip(archivePath, stagingPath)
                        "xz" -> extractXz(archivePath, stagingPath)
                        "tar.gz" -> extractTarGz(archivePath, stagingPath)
                        "tar.xz" -> extractTarXz(archivePath, stagingPath)
                        else -> ExtractResult(false, errorMessage = "不支持的格式: $extension")
                    }

                if (result.success) {
                    if (finalDir.exists()) {
                        ArchiveSafetyGuard.discardDirectory(finalDir)
                    }
                    if (!stagingDir.renameTo(finalDir)) {
                        stagingDir.copyRecursively(finalDir, overwrite = true)
                        ArchiveSafetyGuard.discardDirectory(stagingDir)
                    }
                    stagingDir = null
                    _status.value = "解压完成: ${result.extractedCount} 个文件"
                    result.copy(outputPath = outputDir)
                } else {
                    ArchiveSafetyGuard.discardDirectory(stagingDir)
                    stagingDir = null
                    _status.value = "解压失败: ${result.errorMessage}"
                    result
                }
            } catch (e: Exception) {
                Log.e(TAG, "解压失败", e)
                stagingDir?.let { ArchiveSafetyGuard.discardDirectory(it) }
                ExtractResult(false, errorMessage = e.message ?: "未知错误")
            } finally {
                _isExtracting.value = false
                _progress.value = 100
            }
        }

    /**
     * ZIP 解压 (流式架构 - 内存占用极低)
     */
    private fun extractZip(
        path: String,
        outputDir: String,
        password: String?,
    ): ExtractResult {
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

            // 第一遍：流式扫描、校验并读取所有条目，阻断重复条目和压缩炸弹。
            FileInputStream(path).use { fis ->
                BufferedInputStream(fis).use { bis ->
                    ZipInputStream(bis).use { zis ->
                        val seenEntries = HashSet<String>()
                        val validationBuffer = ByteArray(BUFFER_SIZE)
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
            }

            // 第二遍：真正写入。
            FileInputStream(path).use { fis ->
                BufferedInputStream(fis).use { bis ->
                    ZipInputStream(bis).use { zis ->
                        var entry = zis.nextEntry
                        val buffer = com.example.tfgwj.utils.IoOptimizer.acquireBuffer()
                        var totalWrittenBytes = 0L

                        try {
                            while (entry != null) {
                                // 1. 检查暂停
                                kotlinx.coroutines.runBlocking { PauseControl.waitIfPaused() }

                                val fileName = entry.name
                                val outFile = ArchiveEntryValidator.resolveWithin(File(outputDir), fileName)

                                _currentFile.value = fileName

                                if (entry.isDirectory) {
                                    outFile.mkdirs()
                                } else {
                                    outFile.parentFile?.mkdirs()
                                    // 增加 FileOutputStream 的缓冲区
                                    BufferedOutputStream(FileOutputStream(outFile)).use { bos ->
                                        var entryBytes = 0L
                                        var len = 0
                                        while (zis.read(buffer).also { len = it } > 0) {
                                            entryBytes += len
                                            require(entryBytes <= ArchiveSafetyGuard.MAX_ENTRY_SIZE_BYTES) {
                                                "压缩包单文件超过大小限制: $fileName"
                                            }
                                            totalWrittenBytes = ArchiveSafetyGuard.addBytesWithinLimit(totalWrittenBytes, len.toLong())
                                            bos.write(buffer, 0, len)
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
                                        bos.flush()
                                    }
                                    extractedCount++
                                }

                                zis.closeEntry()
                                entry = zis.nextEntry
                            }
                        } finally {
                            com.example.tfgwj.utils.IoOptimizer.releaseBuffer(buffer)
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
    private fun extractZipWithPassword(
        path: String,
        outputDir: String,
        password: String,
    ): ExtractResult {
        return try {
            val zipFile = ZipFile(path)
            if (zipFile.isEncrypted) {
                zipFile.setPassword(password.toCharArray())
            }

            // 先校验所有 Central Directory 条目，再允许 Zip4j 写入任何文件。
            val destinationDir = File(outputDir)
            ArchiveSafetyGuard.validateEntries(
                zipFile.fileHeaders.map { header ->
                    ArchiveEntryMetadata(header.fileName, header.uncompressedSize)
                },
            )

            // 同步执行可向调用方传播异常，不能再由后台线程吞掉失败后伪装成功。
            zipFile.isRunInThread = false
            zipFile.extractAll(outputDir)
            val count = destinationDir.walkTopDown().count { it.isFile }
            ExtractResult(true, outputDir, count)
        } catch (e: Exception) {
            ExtractResult(false, errorMessage = e.message)
        }
    }

    /**
     * 7z 解压
     * 先完整遍历条目做安全校验，再重新打开归档写入，避免恶意条目在发现前留下部分内容。
     */
    private fun extract7z(
        path: String,
        outputDir: String,
        password: String?,
    ): ExtractResult {
        return try {
            // 第一遍：统计条目并全部通过安全校验。
            val destinationDir = File(outputDir)
            val probeEntries = mutableListOf<ArchiveEntryMetadata>()
            val probe =
                if (!password.isNullOrEmpty()) {
                    SevenZFile.builder().setFile(File(path)).setPassword(password).get()
                } else {
                    SevenZFile.builder().setFile(File(path)).get()
                }
            try {
                var entry: SevenZArchiveEntry? = probe.nextEntry
                while (entry != null) {
                    probeEntries += ArchiveEntryMetadata(entry.name, entry.size)
                    entry = probe.nextEntry
                }
            } finally {
                probe.close()
            }
            ArchiveSafetyGuard.validateEntries(probeEntries)
            val totalEntries = probeEntries.size

            // 第二遍：真正写入。
            val sevenZ =
                if (!password.isNullOrEmpty()) {
                    SevenZFile.builder().setFile(File(path)).setPassword(password).get()
                } else {
                    SevenZFile.builder().setFile(File(path)).get()
                }

            var count = 0
            var totalWrittenBytes = 0L
            var entry: SevenZArchiveEntry? = sevenZ.nextEntry
            while (entry != null) {
                val outFile = ArchiveEntryValidator.resolveWithin(destinationDir, entry.name)
                _currentFile.value = entry.name

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var entryBytes = 0L
                        var len = 0
                        while (sevenZ.read(buffer).also { len = it } > 0) {
                            entryBytes += len
                            require(entryBytes <= ArchiveSafetyGuard.MAX_ENTRY_SIZE_BYTES) {
                                "压缩包单文件超过大小限制: ${entry.name}"
                            }
                            totalWrittenBytes = ArchiveSafetyGuard.addBytesWithinLimit(totalWrittenBytes, len.toLong())
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
    private fun extractTar(
        path: String,
        outputDir: String,
    ): ExtractResult {
        return extractArchive(outputDir) { TarArchiveInputStream(FileInputStream(path)) }
    }

    /**
     * GZIP 解压
     */
    private fun extractGzip(
        path: String,
        outputDir: String,
    ): ExtractResult {
        return try {
            val file = File(path)
            val outputName = file.name.removeSuffix(".gz").removeSuffix(".tgz")
            val outputFile = File(outputDir, if (outputName.endsWith(".tar")) outputName else outputName)

            ArchiveSafetyGuard.validateEntry(outputFile.name)
            GzipCompressorInputStream(BufferedInputStream(FileInputStream(path))).use { gzIn ->
                FileOutputStream(outputFile).use { fos ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var written = 0L
                    var len = 0
                    while (gzIn.read(buffer).also { len = it } > 0) {
                        written = ArchiveSafetyGuard.addBytesWithinLimit(written, len.toLong())
                        require(written <= ArchiveSafetyGuard.MAX_ENTRY_SIZE_BYTES) {
                            "压缩包单文件超过大小限制: ${outputFile.name}"
                        }
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
    private fun extractXz(
        path: String,
        outputDir: String,
    ): ExtractResult {
        return try {
            val file = File(path)
            val outputName = file.name.removeSuffix(".xz")
            val outputFile = File(outputDir, outputName)

            ArchiveSafetyGuard.validateEntry(outputFile.name)
            XZCompressorInputStream(BufferedInputStream(FileInputStream(path))).use { xzIn ->
                FileOutputStream(outputFile).use { fos ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var written = 0L
                    var len = 0
                    while (xzIn.read(buffer).also { len = it } > 0) {
                        written = ArchiveSafetyGuard.addBytesWithinLimit(written, len.toLong())
                        require(written <= ArchiveSafetyGuard.MAX_ENTRY_SIZE_BYTES) {
                            "压缩包单文件超过大小限制: ${outputFile.name}"
                        }
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
    private fun extractTarGz(
        path: String,
        outputDir: String,
    ): ExtractResult {
        return extractArchive(outputDir) {
            TarArchiveInputStream(
                GzipCompressorInputStream(BufferedInputStream(FileInputStream(path))),
            )
        }
    }

    /**
     * TAR.XZ 解压
     */
    private fun extractTarXz(
        path: String,
        outputDir: String,
    ): ExtractResult {
        return extractArchive(outputDir) {
            TarArchiveInputStream(
                XZCompressorInputStream(BufferedInputStream(FileInputStream(path))),
            )
        }
    }

    /**
     * 通用 Archive 解压：先重开流完成预检，再写入。
     */
    private fun extractArchive(
        outputDir: String,
        openStream: () -> ArchiveInputStream<*>,
    ): ExtractResult {
        return try {
            openStream().use { probeIn ->
                val entries = mutableListOf<ArchiveEntryMetadata>()
                var probe = probeIn.nextEntry
                while (probe != null) {
                    entries += ArchiveEntryMetadata(probe.name, probe.size)
                    probe = probeIn.nextEntry
                }
                ArchiveSafetyGuard.validateEntries(entries)
            }

            var count = 0
            var totalWrittenBytes = 0L
            val destinationDir = File(outputDir)
            openStream().use { archiveIn ->
                var entry: ArchiveEntry? = archiveIn.nextEntry
                while (entry != null) {
                    val outFile = ArchiveEntryValidator.resolveWithin(destinationDir, entry.name)
                    _currentFile.value = entry.name
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        val buffer = com.example.tfgwj.utils.IoOptimizer.acquireBuffer()
                        try {
                            BufferedOutputStream(FileOutputStream(outFile)).use { bos ->
                                var entryBytes = 0L
                                var len = 0
                                while (archiveIn.read(buffer).also { len = it } > 0) {
                                    entryBytes += len
                                    require(entryBytes <= ArchiveSafetyGuard.MAX_ENTRY_SIZE_BYTES) {
                                        "压缩包单文件超过大小限制: ${entry.name}"
                                    }
                                    totalWrittenBytes = ArchiveSafetyGuard.addBytesWithinLimit(totalWrittenBytes, len.toLong())
                                    bos.write(buffer, 0, len)
                                }
                                bos.flush()
                            }
                        } finally {
                            com.example.tfgwj.utils.IoOptimizer.releaseBuffer(buffer)
                        }
                        count++
                    }
                    entry = archiveIn.nextEntry
                }
            }
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
