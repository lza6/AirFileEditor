package com.example.tfgwj.model

import android.net.Uri
import java.io.File

data class ArchiveFile(
    val file: File,
    val uri: Uri? = null,
    val fileName: String = file.name,
    val fileSize: Long = file.length(),
    val filePath: String = file.absolutePath,
    val fileType: String = getFileExtension(fileName),
    val hasPassword: Boolean = false,
    val extractedPath: String? = null
) {
    fun getFileSizeFormatted(): String {
        val kb = fileSize / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        
        return when {
            gb >= 1 -> String.format("%.2f GB", gb)
            mb >= 1 -> String.format("%.2f MB", mb)
            kb >= 1 -> String.format("%.2f KB", kb)
            else -> "$fileSize B"
        }
    }
    
    fun getSuggestedPassword(): String {
        // 移除扩展名作为建议密码
        val lastDotIndex = fileName.lastIndexOf('.')
        return if (lastDotIndex > 0) {
            fileName.substring(0, lastDotIndex)
        } else {
            fileName
        }
    }
    
    companion object {
        fun getFileExtension(fileName: String): String {
            val lastDotIndex = fileName.lastIndexOf('.')
            return if (lastDotIndex > 0) {
                fileName.substring(lastDotIndex + 1).lowercase()
            } else {
                ""
            }
        }
        
        fun isSupportedArchive(fileName: String): Boolean {
            val extension = getFileExtension(fileName)
            return extension in listOf("zip", "jar", "gz", "gzip", "7z", "rar")
        }
    }
}