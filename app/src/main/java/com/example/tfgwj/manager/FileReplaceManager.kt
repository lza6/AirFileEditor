package com.example.tfgwj.manager

import android.content.Context
import android.util.Log
import com.example.tfgwj.model.FileReplaceResult
import com.example.tfgwj.utils.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 文件替换管理器
 * 
 * 注意：此管理器已废弃，现在使用 FileReplaceWorker 进行批量并发复制
 * 保留此类是为了避免编译错误，实际功能已由 FileReplaceWorker 接管
 */
@Deprecated("请使用 FileReplaceWorker 进行批量并发复制")
class FileReplaceManager(private val context: Context) {

    companion object {
        private const val TAG = "FileReplaceManager"

        @Volatile
        private var instance: FileReplaceManager? = null

        fun getInstance(context: Context): FileReplaceManager {
            return instance ?: synchronized(this) {
                instance ?: FileReplaceManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val _replaceResult = MutableStateFlow(FileReplaceResult())
    val replaceResult: StateFlow<FileReplaceResult> = _replaceResult.asStateFlow()
    
    private val _isReplacing = MutableStateFlow(false)
    val isReplacing: StateFlow<Boolean> = _isReplacing.asStateFlow()
    
    /**
     * 取消替换
     */
    fun cancelReplace() {
        AppLogger.d(TAG, "替换已取消（已废弃，使用 FileReplaceWorker.cancelAllWork() 代替）")
    }
    
    /**
     * 重置结果
     */
    fun resetResult() {
        _replaceResult.value = FileReplaceResult()
    }
}