package com.example.tfgwj.ui

import com.example.tfgwj.domain.repository.TaskController
import com.example.tfgwj.worker.TaskControllerImpl

/**
 * 全局依赖提供者 — 提供应用内单例组件（避免在多个 UI 层直接 `new` 基础设施对象）。
 *
 * 说明：项目未引入 Hilt/Koin，采用轻量手动 DI。此处集中托管跨层共享的
 * `TaskController`，供 [FloatingBallManager] 等订阅任务状态流，确保与
 * `ConfigRepositoryImpl` 使用同一个控制器实例（唯一权威状态源）。
 */
object ConfigRepositoryProvider {
    @Volatile
    private var taskController: TaskController? = null

    /** 返回应用级共享的 [TaskController]（单例，懒初始化） */
    fun getTaskController(): TaskController =
        taskController ?: synchronized(this) {
            taskController ?: TaskControllerImpl().also { taskController = it }
        }
}
