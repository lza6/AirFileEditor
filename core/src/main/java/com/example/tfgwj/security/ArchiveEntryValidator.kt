package com.example.tfgwj.security

import java.io.File

/**
 * 归一化并校验压缩包条目，确保所有写入都留在指定解压根目录内。
 *
 * 不能只做字符串前缀比较：`/target-escape` 与 `/target` 共享前缀，但并不在
 * `/target` 目录中。因此统一使用 canonical path 加路径分隔符边界进行判断。
 */
object ArchiveEntryValidator {
    private val windowsDrivePrefix = Regex("^[A-Za-z]:.*")

    fun isSafeEntryName(entryName: String): Boolean {
        if (entryName.isBlank() || entryName.indexOf('\u0000') >= 0) return false

        val normalized = entryName.replace('\\', '/')
        if (normalized.startsWith('/') || normalized.startsWith("//") || windowsDrivePrefix.matches(normalized)) {
            return false
        }

        return normalized
            .split('/')
            .none { segment -> segment.isEmpty() || segment == "." || segment == ".." }
    }

    fun resolveWithin(destination: File, entryName: String): File {
        require(isSafeEntryName(entryName)) { "非法压缩包条目: $entryName" }

        val destinationRoot = destination.canonicalFile
        val output = File(destinationRoot, entryName.replace('\\', '/')).canonicalFile
        require(isWithinDestination(destinationRoot, output)) { "压缩包条目越界: $entryName" }
        return output
    }

    fun isWithinDestination(destination: File, candidate: File): Boolean {
        val root = destination.canonicalFile.path
        val resolved = candidate.canonicalFile.path
        return resolved == root || resolved.startsWith(root + File.separator)
    }

    fun requireSafeDirectoryName(directoryName: String): String {
        val normalized = directoryName.trim()
        require(
            normalized.isNotEmpty() &&
                !normalized.contains('/') &&
                !normalized.contains('\\') &&
                normalized != "." &&
                normalized != ".." &&
                normalized.indexOf('\u0000') < 0,
        ) { "非法输出目录名" }
        return normalized
    }
}
