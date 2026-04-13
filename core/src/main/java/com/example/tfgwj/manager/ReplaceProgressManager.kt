package com.example.tfgwj.manager

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 实时进度管理器
 * 用于绕过 WorkManager 的 throttling 机制，实现 60fps 的 UI 更新
 */
object ReplaceProgressManager {
    // V11 MVI Architecture Update
    // Phase defines the progress state exactly: IDLE -> PREPARING -> REPLACING -> VERIFYING -> COMPLETED
    data class ProgressState(
        val processed: Int = 0,
        val total: Int = 0,
        val progress: Int = 0,
        val currentFile: String = "",
        val speed: Float = 0f,
        val phase: String = "IDLE",
    ) {
        // Computed property to determine if work is ongoing, making it easier for UI
        val isReplacing: Boolean
            get() = phase == "PREPARING" || phase == "REPLACING" || phase == "VERIFYING"
    }

    private val _progressState = MutableStateFlow(ProgressState())
    val progressState: StateFlow<ProgressState> = _progressState.asStateFlow()

    fun updateState(
        processed: Int,
        total: Int,
        currentFile: String,
        progress: Int,
        speed: Float = 0f,
        phase: String = "REPLACING",
    ) {
        _progressState.value =
            ProgressState(
                processed = processed,
                total = total,
                progress = progress,
                currentFile = currentFile,
                speed = speed,
                phase = phase,
            )
    }

    fun startMeasure() {
        _progressState.value = _progressState.value.copy(phase = "PREPARING")
    }

    fun finish() {
        _progressState.value = _progressState.value.copy(phase = "COMPLETED")
    }

    fun reset() {
        _progressState.value = ProgressState()
    }
}
