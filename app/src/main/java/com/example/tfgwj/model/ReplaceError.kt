package com.example.tfgwj.model

/**
 * 替换错误分类
 * 用于向用户提供明确的错误信息和解决方案
 */
sealed class ReplaceError {
    
    abstract val title: String
    abstract val message: String
    abstract val solution: String
    abstract val canRetry: Boolean
    
    /**
     * 权限被拒绝
     */
    class PermissionDenied : ReplaceError() {
        override val title = "权限不足"
        override val message = "没有权限访问游戏目录"
        override val solution = "请授予 Shizuku 权限后重试"
        override val canRetry = true
    }
    
    /**
     * 存储空间不足
     */
    class StorageInsufficient(val required: Long, val available: Long) : ReplaceError() {
        override val title = "存储空间不足"
        override val message = "需要 ${formatSize(required)} 但只有 ${formatSize(available)} 可用"
        override val solution = "请清理存储空间后重试"
        override val canRetry = true
        
        private fun formatSize(bytes: Long): String {
            return when {
                bytes >= 1024L * 1024L * 1024L -> String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
                bytes >= 1024L * 1024L -> String.format("%.2f MB", bytes / 1024.0 / 1024.0)
                else -> String.format("%.2f KB", bytes / 1024.0)
            }
        }
    }
    
    /**
     * 文件访问失败
     */
    class FileAccessFailed(val path: String, val cause: String) : ReplaceError() {
        override val title = "文件访问失败"
        override val message = "无法访问: $path"
        override val solution = "原因: $cause\n请检查文件是否存在或被占用"
        override val canRetry = true
    }
    
    /**
     * Shizuku 未连接
     */
    class ShizukuNotConnected : ReplaceError() {
        override val title = "Shizuku 未连接"
        override val message = "需要 Shizuku 服务来访问游戏目录"
        override val solution = "请启动 Shizuku 应用并授权后重试"
        override val canRetry = true
    }
    
    /**
     * 源目录不存在
     */
    class SourceNotFound(val path: String) : ReplaceError() {
        override val title = "主包未找到"
        override val message = "源目录不存在: $path"
        override val solution = "请重新选择主包目录"
        override val canRetry = false
    }
    
    /**
     * Android 目录缺失
     */
    class AndroidDirMissing : ReplaceError() {
        override val title = "主包结构错误"
        override val message = "未找到 Android 文件夹"
        override val solution = "主包必须包含 Android/data/com.tencent.tmgp.pubgmhd/ 目录"
        override val canRetry = false
    }
    
    /**
     * 任务已取消
     */
    class Cancelled(val processedFiles: Int, val totalFiles: Int) : ReplaceError() {
        override val title = "任务已取消"
        override val message = "已完成 $processedFiles / $totalFiles 个文件"
        override val solution = "可以点击重试继续替换未完成的文件"
        override val canRetry = true
    }
    
    /**
     * 未知错误
     */
    class Unknown(val exception: Throwable) : ReplaceError() {
        override val title = "未知错误"
        override val message = exception.message ?: "发生未知错误"
        override val solution = "请查看日志文件获取详细信息，或联系开发者"
        override val canRetry = true
    }
}

/**
 * 断点续传状态
 */
data class ResumeState(
    val sourcePath: String,
    val targetPath: String,
    val totalFiles: Int,
    val completedFiles: List<String>,
    val failedFiles: Map<String, String>   // path -> error message
) {
    val remainingCount: Int get() = totalFiles - completedFiles.size
    val failedCount: Int get() = failedFiles.size
    
    fun isCompleted() = completedFiles.size >= totalFiles
}
