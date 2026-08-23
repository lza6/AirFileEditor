package com.example.tfgwj.security

import java.io.File

/**
 * 统一限制压缩包解压规模与条目冲突，避免压缩炸弹和隐式覆盖。
 */
data class ArchiveEntryMetadata(
    val name: String,
    val size: Long = -1L,
)

object ArchiveSafetyGuard {
    const val MAX_ENTRY_SIZE_BYTES: Long = 4L * 1024L * 1024L * 1024L
    const val MAX_TOTAL_SIZE_BYTES: Long = 16L * 1024L * 1024L * 1024L

    fun validateEntries(entries: Iterable<ArchiveEntryMetadata>) {
        val names = HashSet<String>()
        var totalSize = 0L

        entries.forEach { entry ->
            require(ArchiveEntryValidator.isSafeEntryName(entry.name)) {
                "非法压缩包条目: ${entry.name}"
            }
            val normalizedName = entry.name.replace('\\', '/')
            require(names.add(normalizedName)) { "重复压缩包条目: ${entry.name}" }

            if (entry.size >= 0L) {
                require(entry.size <= MAX_ENTRY_SIZE_BYTES) {
                    "压缩包单文件超过大小限制: ${entry.name}"
                }
                totalSize = addBytesWithinLimit(totalSize, entry.size)
            }
        }
    }

    fun addBytesWithinLimit(currentBytes: Long, addedBytes: Long): Long {
        require(currentBytes >= 0L && addedBytes >= 0L) { "压缩包大小计算非法" }
        val next = try {
            Math.addExact(currentBytes, addedBytes)
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("压缩包总解压大小超过限制")
        }
        require(next <= MAX_TOTAL_SIZE_BYTES) { "压缩包总解压大小超过限制" }
        return next
    }

    fun validateEntry(name: String, size: Long = -1L) {
        require(ArchiveEntryValidator.isSafeEntryName(name)) { "非法压缩包条目: $name" }
        if (size >= 0L) {
            require(size <= MAX_ENTRY_SIZE_BYTES) { "压缩包单文件超过大小限制: $name" }
        }
    }

    fun newStagingDirectory(finalDir: File): File {
        val parent = requireNotNull(finalDir.parentFile) { "解压目标缺少父目录" }
        val staging = File(parent, "${finalDir.name}.__extracting_${java.util.UUID.randomUUID()}")
        require(staging.mkdirs()) { "无法创建解压暂存目录" }
        return staging
    }

    fun discardDirectory(directory: File) {
        runCatching { directory.deleteRecursively() }
    }
}
