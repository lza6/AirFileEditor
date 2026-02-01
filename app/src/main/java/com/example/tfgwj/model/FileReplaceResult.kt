package com.example.tfgwj.model

data class FileReplaceResult(
    val totalFiles: Int = 0,
    val successCount: Int = 0,
    val failedCount: Int = 0,
    val errors: List<FileError> = emptyList(),
    val isCompleted: Boolean = false,
    val progress: Float = 0f
) {
    fun getSuccessPercentage(): Float {
        if (totalFiles == 0) return 0f
        return (successCount.toFloat() / totalFiles.toFloat()) * 100f
    }
    
    fun getProgressPercentage(): Float {
        return progress * 100f
    }
}

data class FileError(
    val filePath: String,
    val errorMessage: String,
    val exception: Throwable? = null
)