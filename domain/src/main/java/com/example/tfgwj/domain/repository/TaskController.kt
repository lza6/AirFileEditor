package com.example.tfgwj.domain.repository

import com.example.tfgwj.domain.model.TaskPhase
import kotlinx.coroutines.flow.Flow

/**
 * 实时任务进度控制器（TaskController 替代 ReplaceProgressManager）
 *
 * 绕过 WorkManager 的 throttling 机制，实现 60fps 的 UI 更新。
 * 状态机：IDLE → PREPARING → REPLACING → VERIFYING → COMPLETED
 *                ↓            ↓           ↓
 *                └──── FAILURE / CANCELLED ←┘
 */
data class TaskState(
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

/**
 * 任务进度控制接口（唯一权威状态源，可替换 ReplaceProgressManager）
 */
interface TaskController {
    val state: Flow<TaskState>

    /** 更新运行中的进度状态 */
    fun updateState(
        processed: Int,
        total: Int,
        currentFile: String,
        progress: Int,
        speed: Float = 0f,
        phase: TaskPhase = TaskPhase.REPLACING,
        errorMessage: String? = null,
    )

    /** 开始测量：重置所有计数并进入 PREPARING */
    fun startMeasure()

    /** 完成：进入 COMPLETED 终态，清除错误 */
    fun finish()

    /** 失败：进入 FAILURE 终态，保留错误信息供 UI 展示 */
    fun fail(message: String)

    /** 取消：进入 CANCELLED 终态，保留已处理的进度计数供 UI 解释 */
    fun cancel()

    /** 重置到 IDLE 初始状态 */
    fun reset()
}