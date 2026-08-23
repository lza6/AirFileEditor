package com.example.tfgwj.manager

import com.example.tfgwj.domain.model.TaskPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 实时进度管理器
 * 用于绕过 WorkManager 的 throttling 机制，实现 60fps 的 UI 更新
 *
 * V13 收口：phase 字段从 String 迁移为 TaskPhase 枚举，消除歧义。
 * 状态机：IDLE → PREPARING → REPLACING → VERIFYING → COMPLETED
 *                ↓            ↓           ↓
 *                └──── FAILURE / CANCELLED ←┘
 */
object ReplaceProgressManager {
    data class ProgressState(
        val processed: Int = 0,
        val total: Int = 0,
        val progress: Int = 0,
        val currentFile: String = "",
        val speed: Float = 0f,
        val phase: TaskPhase = TaskPhase.IDLE,
        val errorMessage: String? = null,
    ) {
        /** 是否处于运行中状态（非终态） */
        val isReplacing: Boolean
            get() = phase == TaskPhase.PREPARING || phase == TaskPhase.REPLACING || phase == TaskPhase.VERIFYING

        /** 是否为终态（可关闭 Overlay） */
        val isTerminal: Boolean
            get() = phase == TaskPhase.COMPLETED || phase == TaskPhase.FAILURE || phase == TaskPhase.CANCELLED
    }

    private val _progressState = MutableStateFlow(ProgressState())
    val progressState: StateFlow<ProgressState> = _progressState.asStateFlow()

    fun updateState(
        processed: Int,
        total: Int,
        currentFile: String,
        progress: Int,
        speed: Float = 0f,
        phase: TaskPhase = TaskPhase.REPLACING,
        errorMessage: String? = null,
    ) {
        _progressState.value =
            ProgressState(
                processed = processed,
                total = total,
                progress = progress,
                currentFile = currentFile,
                speed = speed,
                phase = phase,
                errorMessage = errorMessage,
            )
    }

    /** 开始测量：重置所有计数并进入 PREPARING */
    fun startMeasure() {
        _progressState.value = ProgressState(phase = TaskPhase.PREPARING)
    }

    /** 完成：进入 COMPLETED 终态，清除错误 */
    fun finish() {
        _progressState.value = _progressState.value.copy(phase = TaskPhase.COMPLETED, errorMessage = null)
    }

    /** 失败：进入 FAILURE 终态，保留错误信息供 UI 展示 */
    fun fail(message: String) {
        _progressState.value = _progressState.value.copy(phase = TaskPhase.FAILURE, errorMessage = message)
    }

    /** 取消：进入 CANCELLED 终态，保留已处理的进度计数供 UI 解释 */
    fun cancel() {
        _progressState.value = _progressState.value.copy(phase = TaskPhase.CANCELLED, errorMessage = null)
    }

    /** 重置到 IDLE 初始状态 */
    fun reset() {
        _progressState.value = ProgressState()
    }
}
