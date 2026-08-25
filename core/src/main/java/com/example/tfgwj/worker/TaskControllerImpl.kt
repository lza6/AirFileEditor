package com.example.tfgwj.worker

import com.example.tfgwj.domain.model.TaskPhase
import com.example.tfgwj.domain.repository.TaskController
import com.example.tfgwj.domain.repository.TaskState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * TaskController 的默认实现（替代 ReplaceProgressManager）
 *
 * 采用 MutableStateFlow 保存状态，支持 60fps 的实时 UI 更新。
 * 状态机行为与 ReplaceProgressManager 完全一致。
 */
class TaskControllerImpl : TaskController {
    private val _state = MutableStateFlow(TaskState())
    override val state: Flow<TaskState> = _state.asStateFlow()

    override fun updateState(
        processed: Int,
        total: Int,
        currentFile: String,
        progress: Int,
        speed: Float,
        phase: TaskPhase,
        errorMessage: String?,
    ) {
        _state.value = TaskState(processed, total, progress, currentFile, speed, phase, errorMessage)
    }

    /** 开始测量：重置所有计数并进入 PREPARING */
    override fun startMeasure() {
        _state.value = TaskState(phase = TaskPhase.PREPARING)
    }

    /** 完成：进入 COMPLETED 终态，清除错误 */
    override fun finish() {
        _state.value = _state.value.copy(phase = TaskPhase.COMPLETED, errorMessage = null)
    }

    /** 失败：进入 FAILURE 终态，保留错误信息供 UI 展示 */
    override fun fail(message: String) {
        _state.value = _state.value.copy(phase = TaskPhase.FAILURE, errorMessage = message)
    }

    /** 取消：进入 CANCELLED 终态，保留已处理的进度计数供 UI 解释 */
    override fun cancel() {
        _state.value = _state.value.copy(phase = TaskPhase.CANCELLED, errorMessage = null)
    }

    /** 重置到 IDLE 初始状态 */
    override fun reset() {
        _state.value = TaskState()
    }
}