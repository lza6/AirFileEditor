package com.example.tfgwj.manager

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 实时进度管理器
 * 用于绕过 WorkManager 的 throttling 机制，实现 60fps 的 UI 更新
 */
object ReplaceProgressManager {
    
    data class ProgressState(
        val processed: Int = 0,
        val total: Int = 0,
        val progress: Int = 0,
        val currentFile: String = "",
        val speed: Float = 0f,
        val isReplacing: Boolean = false,
        val phase: String = "PREPARING" // "PREPARING", "REPLACING", "VERIFYING", "COMPLETED"
    )

    private val _progressState = MutableStateFlow(ProgressState())
    val progressState: StateFlow<ProgressState> = _progressState.asStateFlow()

    fun updateState(
        processed: Int, 
        total: Int, 
        currentFile: String, 
        progress: Int,
        speed: Float = 0f,
        phase: String = "REPLACING"
    ) {
        _progressState.value = ProgressState(
            processed = processed,
            total = total,
            progress = progress,
            currentFile = currentFile,
            speed = speed,
            isReplacing = true,
            phase = phase
        )
    }

    fun startMeasure() {
        _progressState.value = _progressState.value.copy(isReplacing = true, phase = "REPLACING")
    }

    fun finish() {
        _progressState.value = _progressState.value.copy(isReplacing = false, phase = "COMPLETED")
    }
    
    fun reset() {
        _progressState.value = ProgressState()
    }
}
