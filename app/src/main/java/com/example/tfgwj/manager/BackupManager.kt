package com.example.tfgwj.manager

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupManager(private val context: Context) {
    
    companion object {
        private const val TAG = "BackupManager"
        private const val BACKUP_DIR_NAME = "backups"
        private const val MAX_BACKUP_COUNT = 10
        
        @Volatile
        private var instance: BackupManager? = null
        
        fun getInstance(context: Context): BackupManager {
            return instance ?: synchronized(this) {
                instance ?: BackupManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val backupDir: File
        get() = File(context.getExternalFilesDir(null), "听风改文件/$BACKUP_DIR_NAME").apply {
            if (!exists()) mkdirs()
        }
    
    /**
     * 创建备份
     * @param sourcePath 要备份的源路径（通常是应用的Android/data目录）
     * @param packageName 应用包名
     * @return 备份目录路径，失败返回null
     */
    suspend fun createBackup(sourcePath: String, packageName: String): String? = withContext(Dispatchers.IO) {
        try {
            val sourceDir = File(sourcePath)
            if (!sourceDir.exists()) {
                Log.w(TAG, "Source directory does not exist: $sourcePath")
                return@withContext null
            }
            
            // 创建带时间戳的备份目录
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val backupName = "${packageName}_$timestamp"
            val backupPath = File(backupDir, backupName)
            
            Log.d(TAG, "Creating backup: $sourcePath -> $backupPath")
            
            // 递归复制文件
            val success = copyDirectoryRecursively(sourceDir, backupPath)
            
            if (success) {
                Log.d(TAG, "Backup created successfully: $backupPath")
                
                // 清理旧备份
                cleanOldBackups(packageName)
                
                backupPath.absolutePath
            } else {
                Log.e(TAG, "Failed to create backup")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating backup", e)
            null
        }
    }
    
    /**
     * 恢复备份
     * @param backupPath 备份目录路径
     * @param targetPath 目标路径
     * @return 是否成功
     */
    suspend fun restoreBackup(backupPath: String, targetPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val backupDir = File(backupPath)
            if (!backupDir.exists()) {
                Log.w(TAG, "Backup directory does not exist: $backupPath")
                return@withContext false
            }
            
            val targetDir = File(targetPath)
            
            Log.d(TAG, "Restoring backup: $backupPath -> $targetPath")
            
            // 备份当前文件（如果存在）
            val currentBackup = if (targetDir.exists()) {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val tempBackup = File(backupDir.parent, "pre_restore_$timestamp")
                copyDirectoryRecursively(targetDir, tempBackup)
                tempBackup
            } else null
            
            // 删除目标目录
            if (targetDir.exists()) {
                deleteDirectoryRecursively(targetDir)
            }
            
            // 复制备份文件
            val success = copyDirectoryRecursively(backupDir, targetDir)
            
            if (!success && currentBackup != null) {
                // 恢复失败，恢复之前的备份
                Log.w(TAG, "Restore failed, restoring previous state")
                deleteDirectoryRecursively(targetDir)
                copyDirectoryRecursively(currentBackup, targetDir)
                currentBackup.deleteRecursively()
            }
            
            currentBackup?.deleteRecursively()
            
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring backup", e)
            false
        }
    }
    
    /**
     * 获取所有备份
     * @param packageName 应用包名（可选）
     * @return 备份列表
     */
    fun getBackups(packageName: String? = null): List<BackupInfo> {
        return try {
            backupDir.listFiles()?.mapNotNull { backupDir ->
                if (backupDir.isDirectory) {
                    val name = backupDir.name
                    val pkg = name.substringBeforeLast("_")
                    
                    if (packageName == null || pkg == packageName) {
                        val timestampStr = name.substringAfterLast("_")
                        val timestamp = try {
                            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).parse(timestampStr)?.time
                                ?: backupDir.lastModified()
                        } catch (e: Exception) {
                            backupDir.lastModified()
                        }
                        
                        BackupInfo(
                            path = backupDir.absolutePath,
                            packageName = pkg,
                            timestamp = timestamp,
                            size = calculateDirectorySize(backupDir)
                        )
                    } else null
                } else null
            }?.sortedByDescending { it.timestamp } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting backups", e)
            emptyList()
        }
    }
    
    /**
     * 删除备份
     * @param backupPath 备份路径
     * @return 是否成功
     */
    fun deleteBackup(backupPath: String): Boolean {
        return try {
            val backupDir = File(backupPath)
            if (backupDir.exists() && backupDir.isDirectory) {
                deleteDirectoryRecursively(backupDir)
                Log.d(TAG, "Backup deleted: $backupPath")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting backup", e)
            false
        }
    }
    
    /**
     * 清理旧备份（保留最新的MAX_BACKUP_COUNT个）
     * @param packageName 应用包名
     */
    private fun cleanOldBackups(packageName: String) {
        try {
            val backups = getBackups(packageName)
            if (backups.size > MAX_BACKUP_COUNT) {
                backups.drop(MAX_BACKUP_COUNT).forEach { oldBackup ->
                    deleteBackup(oldBackup.path)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning old backups", e)
        }
    }
    
    /**
     * 递归复制目录
     */
    private fun copyDirectoryRecursively(source: File, target: File): Boolean {
        return try {
            if (source.isDirectory) {
                target.mkdirs()
                source.listFiles()?.forEach { file ->
                    copyDirectoryRecursively(file, File(target, file.name))
                }
            } else {
                source.copyTo(target, overwrite = true)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error copying directory: ${source.absolutePath}", e)
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
            Log.e(TAG, "Error deleting directory: ${dir.absolutePath}", e)
            false
        }
    }
    
    /**
     * 计算目录大小
     */
    private fun calculateDirectorySize(dir: File): Long {
        return try {
            dir.walkTopDown()
                .filter { it.isFile }
                .map { it.length() }
                .sum()
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating directory size", e)
            0L
        }
    }
    
    /**
     * 获取备份目录总大小
     */
    fun getTotalBackupSize(): Long {
        return calculateDirectorySize(backupDir)
    }
    
    /**
     * 清空所有备份
     */
    fun clearAllBackups(): Boolean {
        return try {
            backupDir.listFiles()?.forEach { backup ->
                deleteDirectoryRecursively(backup)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing all backups", e)
            false
        }
    }
}

data class BackupInfo(
    val path: String,
    val packageName: String,
    val timestamp: Long,
    val size: Long
) {
    fun getFormattedSize(): String {
        val kb = size / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        
        return when {
            gb >= 1 -> String.format("%.2f GB", gb)
            mb >= 1 -> String.format("%.2f MB", mb)
            kb >= 1 -> String.format("%.2f KB", kb)
            else -> "$size B"
        }
    }
    
    fun getFormattedDate(): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(timestamp))
    }
}