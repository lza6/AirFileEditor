package com.example.tfgwj.ui

import com.example.tfgwj.domain.repository.TaskController
import com.example.tfgwj.worker.TaskControllerProvider

/**
 * 全局依赖提供者 — 提供应用内单例组件（避免在多个 UI 层直接 `new` 基础设施对象）。
 *
 * 说明：项目未引入 Hilt/Koin，采用轻量手动 DI。此处集中托管跨层共享的
 * `TaskController`，供 [FloatingBallManager] 等订阅任务状态流，确保与
 * `ConfigRepositoryImpl`、`FileReplaceWorkerV2` 使用同一个控制器实例（唯一权威状态源）。
 *
 * 实现：委托给 [core][TaskControllerProvider] 的单例，避免 UI 层重复持有实例，
 * 统一由 `:core` 的 `TaskControllerProvider.get()` 返回唯一控制器。
 */
object ConfigRepositoryProvider {
    fun getTaskController(): TaskController = TaskControllerProvider.get()
}
