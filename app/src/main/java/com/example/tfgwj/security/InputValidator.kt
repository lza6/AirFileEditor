package com.example.tfgwj.security

import android.util.Log
import java.io.File
import java.util.regex.Pattern

/**
 * 输入验证器
 *
 * 核心职责：
 * 1. Path Traversal 防护
 * 2. Zip Slip 防护
 * 3. 命令注入防护
 * 4. 正则表达式 DoS 防护
 *
 * @version V12.0.0 - Security Hardening
 */
object InputValidator {
    private const val TAG = "InputValidator"

    // 危险路径模式
    private val PATH_TRAVERSAL_PATTERNS =
        listOf(
            "../",
            "..\\",
            "..%2f",
            "..%5c",
            "%2e%2e%2f",
            "%2e%2e/",
            "%2e%2e%5c",
        )

    // 命令注入危险字符
    private val COMMAND_INJECTION_CHARS =
        listOf(
            ";",
            "|",
            "&",
            "$",
            "`",
            "\n",
            "\r",
            "(",
            ")",
            "{",
            "}",
            "[",
            "]",
        )

    /**
     * 验证文件路径安全性
     * @param path 待验证的路径
     * @param basePath 允许的基础路径（可选）
     * @return 安全的路径，如果路径不安全则返回 null
     */
    fun validatePath(
        path: String,
        basePath: String? = null,
    ): String? {
        if (path.isBlank()) {
            Log.w(TAG, "Path is blank")
            return null
        }

        // 规范化路径
        val normalizedPath =
            try {
                File(path).canonicalPath
            } catch (e: Exception) {
                Log.w(TAG, "Failed to normalize path: $path", e)
                return null
            }

        // 检查 Path Traversal
        for (pattern in PATH_TRAVERSAL_PATTERNS) {
            if (path.lowercase().contains(pattern)) {
                Log.w(TAG, "Path traversal detected: $path")
                return null
            }
        }

        // 检查是否在允许的基础路径内
        if (basePath != null) {
            val normalizedBase =
                try {
                    File(basePath).canonicalPath
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to normalize base path: $basePath", e)
                    return null
                }

            if (!normalizedPath.startsWith(normalizedBase)) {
                Log.w(TAG, "Path outside base directory: $normalizedPath (base: $normalizedBase)")
                return null
            }
        }

        return normalizedPath
    }

    /**
     * 验证 Zip 解压目标路径（Zip Slip 防护）
     * @param targetDir 目标目录
     * @param entryName 压缩包内条目名称
     * @return 安全的解压路径，如果不安全则返回 null
     */
    fun validateZipEntry(
        targetDir: File,
        entryName: String,
    ): File? {
        val targetFile = File(targetDir, entryName)

        try {
            val targetCanonical = targetDir.canonicalPath
            val entryCanonical = targetFile.canonicalPath

            if (!entryCanonical.startsWith(targetCanonical)) {
                Log.w(TAG, "Zip Slip detected: $entryName -> $entryCanonical")
                return null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to validate zip entry: $entryName", e)
            return null
        }

        return targetFile
    }

    /**
     * 净化 Shell 命令参数（防止命令注入）
     * @param arg 待净化的参数
     * @return 净化后的参数
     */
    fun sanitizeShellArg(arg: String): String {
        var sanitized = arg

        // 移除危险字符
        for (char in COMMAND_INJECTION_CHARS) {
            sanitized = sanitized.replace(char, "")
        }

        // 转义引号
        sanitized = sanitized.replace("'", "\\'")
        sanitized = sanitized.replace("\"", "\\\"")

        return sanitized
    }

    /**
     * 验证包名格式
     * @param packageName 待验证的包名
     * @return 是否有效
     */
    fun isValidPackageName(packageName: String): Boolean {
        // 包名格式：xxx.xxx.xxx（至少两段，只包含字母、数字、下划线、点）
        val pattern = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
        return pattern.matcher(packageName).matches()
    }

    /**
     * 验证文件名安全性
     * @param fileName 待验证的文件名
     * @return 是否安全
     */
    fun isSafeFileName(fileName: String): Boolean {
        if (fileName.isBlank()) return false

        // 检查危险字符
        val dangerousChars = listOf("/", "\\", ":", "*", "?", "\"", "<", ">", "|", "\u0000")
        for (char in dangerousChars) {
            if (fileName.contains(char)) return false
        }

        // 检查保留名称（Windows）
        val reservedNames = listOf("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "LPT1", "LPT2")
        if (reservedNames.contains(fileName.uppercase())) return false

        // 检查 Path Traversal
        if (fileName.contains("..")) return false

        return true
    }

    /**
     * 验证 URL 安全性
     * @param url 待验证的 URL
     * @return 是否安全
     */
    fun isSafeUrl(url: String): Boolean {
        if (url.isBlank()) return false

        // 只允许 HTTPS
        if (!url.startsWith("https://")) {
            Log.w(TAG, "Non-HTTPS URL rejected: $url")
            return false
        }

        // 检查危险字符
        val dangerousPatterns = listOf("javascript:", "data:", "vbscript:", "file:")
        for (pattern in dangerousPatterns) {
            if (url.lowercase().contains(pattern)) {
                Log.w(TAG, "Dangerous URL pattern detected: $url")
                return false
            }
        }

        return true
    }

    /**
     * 限制正则表达式复杂度（ReDoS 防护）
     * @param regex 正则表达式
     * @param timeoutMs 超时时间（毫秒）
     * @return 是否安全
     */
    fun isSafeRegex(
        regex: String,
        timeoutMs: Long = 100,
    ): Boolean {
        // 检查危险模式
        val dangerousPatterns =
            listOf(
                "(.*)+", // 嵌套量词
                "(.*)*",
                "(.+)+",
                "(.+)*",
            )

        for (pattern in dangerousPatterns) {
            if (regex.contains(pattern)) {
                Log.w(TAG, "Potentially dangerous regex pattern: $regex")
                return false
            }
        }

        // 测试编译
        try {
            val compiled = Pattern.compile(regex)
            // 测试执行（使用超时保护）
            val testInput = "a".repeat(1000)
            val startTime = System.currentTimeMillis()
            compiled.matcher(testInput).matches()
            val elapsed = System.currentTimeMillis() - startTime

            if (elapsed > timeoutMs) {
                Log.w(TAG, "Regex execution timeout: $regex (${elapsed}ms)")
                return false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Invalid regex: $regex", e)
            return false
        }

        return true
    }
}
