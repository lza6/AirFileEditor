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

    // V20 压缩炸弹启发式阈值
    const val MAX_ENTRY_COUNT = 100_000          // 单压缩包最大条目数
    const val COMPRESSION_RATIO_THRESHOLD = 100.0 // 总解压大小/压缩包大小 最大压缩率
    const val SUSPICIOUS_TOTAL_BYTES = 512L * 1024L * 1024L // 触发压缩率检测的解压总量门槛

    /**
     * 压缩炸弹检测：
     * - 条目数暴增：超过 [MAX_ENTRY_COUNT] 直接拒绝
     * - 高压缩率：总解压大小超过 [SUSPICIOUS_TOTAL_BYTES] 且 解压/压缩包大小 > [COMPRESSION_RATIO_THRESHOLD] 时拒绝
     *
     * @param entries 已通过条目名/规模校验的元数据
     * @param archiveBytes 压缩包本体大小（字节）
     */
    fun validateBomb(
        entries: Iterable<ArchiveEntryMetadata>,
        archiveBytes: Long,
    ) {
        require(archiveBytes > 0L) { "压缩包大小为 0，无法判定压缩率" }
        var count = 0
        var totalSize = 0L
        entries.forEach { entry ->
            count++
            if (entry.size >= 0L) {
                totalSize = addBytesWithinLimit(totalSize, entry.size)
            }
        }
        require(count <= MAX_ENTRY_COUNT) { "压缩包条目数超过限制: $count" }

        // 仅在解压总量可能构成炸弹时检查压缩率（避免小文件误判）
        if (totalSize >= SUSPICIOUS_TOTAL_BYTES) {
            val ratio = totalSize.toDouble() / archiveBytes.toDouble()
            require(ratio <= COMPRESSION_RATIO_THRESHOLD) {
                "压缩包压缩率异常 (${"%.1f".format(ratio)}x)，疑似压缩炸弹，已拒绝"
            }
        }
    }

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
