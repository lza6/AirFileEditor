package com.example.tfgwj.worker

import com.example.tfgwj.domain.repository.TaskController

/**
 * TaskController 应用级共享单例
 *
 * 解决状态源断裂问题：`FileReplaceWorkerV2`（:core 写入进度）与
 * `ConfigRepositoryImpl`（:data 读取进度/取消任务）此前各自 `new TaskControllerImpl()`，
 * 持不同实例、`StateFlow` 互不共享，导致 UI 订阅的进度与 Worker 实际状态不同步。
 *
 * 现统一由本 Provider 返回同一实例，落实"任务契约只走 ConfigRepository（唯一权威状态源）"红线。
 * `:core` 自包含，不依赖 `:data` 或 `:app`，符合依赖方向 `:app → :data → :domain ← :core`。
 */
object TaskControllerProvider {
    @Volatile
    private var instance: TaskController? = null

    /** 返回应用级共享的 [TaskController]（单例，懒初始化，线程安全） */
    fun get(): TaskController =
        instance ?: synchronized(this) {
            instance ?: TaskControllerImpl().also { instance = it }
        }
}
