package com.example.tfgwj.utils

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object FileHasher {
    private const val TAG = "FileHasher"
    private const val BUFFER_SIZE = 8192
    
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
     * 字节数组转十六进制字符串
     */
    private fun bytesToHex(bytes: ByteArray): String {
        val hexString = StringBuilder()
        for (byte in bytes) {
            val hex = Integer.toHexString(0xff and byte.toInt())
            if (hex.length == 1) {
                hexString.append('0')
            }
            hexString.append(hex)
        }
        return hexString.toString()
    }
    
    /**
     * 验证文件哈希
     * @param file 文件
     * @param expectedHash 期望的哈希值
     * @param algorithm 算法（SHA-256或MD5）
     * @return 是否匹配
     */
    fun verifyFileHash(file: File, expectedHash: String, algorithm: String = "SHA-256"): Boolean {
        val actualHash = when (algorithm.uppercase()) {
            "MD5" -> calculateMD5(file)
            "SHA-256" -> calculateSHA256(file)
            else -> calculateSHA256(file)
        }
        
        return actualHash?.equals(expectedHash, ignoreCase = true) ?: false
    }
    
    /**
     * 比较两个文件是否相同（通过哈希值）
     * @param file1 文件1
     * @param file2 文件2
     * @return 是否相同
     */
    fun areFilesEqual(file1: File, file2: File): Boolean {
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
     * 验证目录完整性
     * @param dir 目录
     * @param expectedHash 期望的哈希值
     * @return 是否完整
     */
    fun verifyDirectoryIntegrity(dir: File, expectedHash: String): Boolean {
        val actualHash = calculateDirectoryHash(dir)
        return actualHash?.equals(expectedHash, ignoreCase = true) ?: false
    }
}