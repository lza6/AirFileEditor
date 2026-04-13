package com.example.tfgwj.utils

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object FileHasher {
    private const val TAG = "FileHasher"

    // Optimized buffer size: 64KB for better IO throughput
    private const val BUFFER_SIZE = 64 * 1024

    /**
     * 计算文件的SHA-256哈希值
     * @param file 文件
     * @return SHA-256哈希字符串，失败返回null
     */
    fun calculateSHA256(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            bytesToHex(digest.digest())
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating SHA-256 for ${file.name}", e)
            null
        }
    }

    /**
     * 计算文件的MD5哈希值
     * @param file 文件
     * @return MD5哈希字符串，失败返回null
     */
    fun calculateMD5(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            bytesToHex(digest.digest())
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating MD5 for ${file.name}", e)
            null
        }
    }

    /**
     * 计算目录的哈希值（递归计算所有文件的哈希）
     * @param dir 目录
     * @return 目录哈希字符串，失败返回null
     */
    fun calculateDirectoryHash(dir: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")

            dir.walkTopDown()
                .filter { it.isFile }
                .sortedBy { it.absolutePath }
                .forEach { file ->
                    val relativePath = file.relativeTo(dir).path
                    digest.update(relativePath.toByteArray())

                    val fileHash = calculateSHA256(file)
                    if (fileHash != null) {
                        digest.update(fileHash.toByteArray())
                    }
                }

            bytesToHex(digest.digest())
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating directory hash for ${dir.name}", e)
            null
        }
    }

    /**
     * 字节数组转十六进制字符串 - Optimized with lookup table
     */
    private fun bytesToHex(bytes: ByteArray): String {
        // Using lookup table for faster conversion
        val hexChars = "0123456789abcdef".toCharArray()
        val result = CharArray(bytes.size * 2)
        var idx = 0
        for (byte in bytes) {
            result[idx++] = hexChars[(byte.toInt() shr 4) and 0x0f]
            result[idx++] = hexChars[byte.toInt() and 0x0f]
        }
        return String(result)
    }

    /**
     * 验证文件哈希
     * @param file 文件
     * @param expectedHash 期望的哈希值
     * @param algorithm 算法（SHA-256或MD5）
     * @return 是否匹配
     */
    fun verifyFileHash(
        file: File,
        expectedHash: String,
        algorithm: String = "SHA-256",
    ): Boolean {
        val actualHash =
            when (algorithm.uppercase()) {
                "MD5" -> calculateMD5(file)
                "SHA-256" -> calculateSHA256(file)
                else -> calculateSHA256(file)
            }

        return actualHash?.equals(expectedHash, ignoreCase = true) ?: false
    }

    /**
     * 比较两个文件是否相同（通过哈希值）
     */
    fun areFilesEqual(
        file1: File,
        file2: File,
    ): Boolean {
        if (!file1.exists() || !file2.exists()) {
            return false
        }

        if (file1.length() != file2.length()) {
            return false
        }

        val hash1 = calculateMD5(file1)
        val hash2 = calculateMD5(file2)

        return hash1 != null && hash1 == hash2
    }

    /**
     * 通过前、中、后 抽样进行快速文件相等性判断（用于超大文件提速，替代全量哈希）
     */
    fun areFilesEqualWithSampling(
        file1: File,
        file2: File,
        sampleSize: Long = 512 * 1024L,
    ): Boolean {
        if (!file1.exists() || !file2.exists()) return false
        val fileSize = file1.length()
        if (fileSize != file2.length()) return false

        // 样本太小直接退化成全量比对
        if (fileSize <= sampleSize * 3) {
            return areFilesEqual(file1, file2)
        }

        return try {
            java.io.RandomAccessFile(file1, "r").use { raf1 ->
                java.io.RandomAccessFile(file2, "r").use { raf2 ->
                    // 1. 验证首部区块
                    if (!compareChunks(raf1, raf2, 0L, sampleSize)) return false
                    // 2. 验证中间区块
                    val midPos = (fileSize / 2) - (sampleSize / 2)
                    if (!compareChunks(raf1, raf2, midPos, sampleSize)) return false
                    // 3. 验证尾部区块
                    val endPos = fileSize - sampleSize
                    if (!compareChunks(raf1, raf2, endPos, sampleSize)) return false

                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "抽样比对失败: ${file1.name}", e)
            false
        }
    }

    private fun compareChunks(
        raf1: java.io.RandomAccessFile,
        raf2: java.io.RandomAccessFile,
        startOffset: Long,
        sizeToRead: Long,
    ): Boolean {
        raf1.seek(startOffset)
        raf2.seek(startOffset)

        // Optimized buffer size for chunk comparison
        val bufferSize = 64 * 1024
        val buf1 = ByteArray(bufferSize)
        val buf2 = ByteArray(bufferSize)
        var remaining = sizeToRead

        while (remaining > 0) {
            val toRead = minOf(remaining, bufferSize.toLong()).toInt()
            val read1 = raf1.read(buf1, 0, toRead)
            val read2 = raf2.read(buf2, 0, toRead)

            if (read1 != read2) return false
            if (read1 == -1) break

            // 快速内存比对
            for (i in 0 until read1) {
                if (buf1[i] != buf2[i]) return false
            }
            remaining -= read1
        }
        return true
    }

    /**
     * 验证目录完整性
     */
    fun verifyDirectoryIntegrity(
        dir: File,
        expectedHash: String,
    ): Boolean {
        val actualHash = calculateDirectoryHash(dir)
        return actualHash?.equals(expectedHash, ignoreCase = true) ?: false
    }
}
