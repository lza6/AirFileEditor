package com.example.tfgwj.worker.orchestrator

import android.os.Build

/**
 * 复制配置参数容器
 * 集中管理所有复制相关的配置参数，消除硬编码值
 *
 * 配置策略：
 * - 根据 Android 版本动态调整
 * - 根据设备能力自动优化
 * - 支持自定义覆盖（测试/特殊场景）
 *
 * @version V8.0.0 - Architecture Evolution
 */
data class CopyConfig(
    // 并发控制
    val maxConcurrentTasks: Int,
    val rootConcurrentPermits: Int,
    val shizukuConcurrentPermits: Int,
    val nativeConcurrentPermits: Int,
    // 进度上报节流
    val wmUpdateIntervalMs: Long, // WorkManager 更新间隔
    val uiUpdateIntervalMs: Long, // UI 更新间隔
    val initialProgressThreshold: Int, // 初始进度触发阈值
    // 文件处理批次大小
    val fileBatchSize: Int,
    val statBatchSize: Int,
    // 看门狗监控
    val watchdogIntervalMs: Long,
    // Shell 命令超时
    val shellCommandTimeoutMs: Long,
    // 进度阶段划分
    val progressPhaseReplacingMax: Int, // REPLACING 阶段最大进度
    val progressPhaseVerifyingStart: Int, // VERIFYING 阶段起始进度
    val progressPhaseVerifyingMax: Int, // VERIFYING 阶段最大进度
) {
    companion object {
        /**
         * 获取默认配置（根据设备能力自动优化）
         */
        fun getDefault(context: android.content.Context): CopyConfig {
            val cpuCores = Runtime.getRuntime().availableProcessors()
            val memoryMb = getMemoryMb(context)

            // 动态计算并发数：CPU 核心数 × 系数，范围 4-32
            val baseConcurrent = (cpuCores * 2).coerceIn(4, 32)

            // Root/Shizuku 使用保守并发（避免 shell 输出竞争）
            val rootConcurrent = 2
            val shizukuConcurrent = 2

            // Native 模式可以使用更高并发（纯 Java IO）
            val nativeConcurrent = baseConcurrent

            return CopyConfig(
                maxConcurrentTasks = baseConcurrent,
                rootConcurrentPermits = rootConcurrent,
                shizukuConcurrentPermits = shizukuConcurrent,
                nativeConcurrentPermits = nativeConcurrent,
                // 节流间隔（毫秒）
                wmUpdateIntervalMs = 1000, // WorkManager DB 写入频率
                uiUpdateIntervalMs = 32, // ~30 FPS UI 刷新
                initialProgressThreshold = 5, // 前5个文件高频更新
                // 批次大小
                fileBatchSize = 32, // 协程批次处理
                statBatchSize = 500, // stat 命令批量检查
                // 看门狗
                watchdogIntervalMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) 300 else 500,
                // 超时
                shellCommandTimeoutMs = 30000, // 30秒
                // 进度阶段（0-100）
                progressPhaseReplacingMax = 95,
                progressPhaseVerifyingStart = 90,
                progressPhaseVerifyingMax = 100,
            )
        }

        /**
         * 获取设备内存大小（MB）
         */
        private fun getMemoryMb(context: android.content.Context): Long {
            return try {
                val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val memoryInfo = android.app.ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)
                memoryInfo.totalMem / (1024 * 1024)
            } catch (e: Exception) {
                // 降级方案：使用 Runtime 估算
                Runtime.getRuntime().maxMemory() / (1024 * 1024)
            }
        }

        /**
         * 测试用配置：更快的节流（便于观察）
         */
        fun getTestConfig(): CopyConfig {
            return CopyConfig(
                maxConcurrentTasks = 2,
                rootConcurrentPermits = 1,
                shizukuConcurrentPermits = 1,
                nativeConcurrentPermits = 2,
                wmUpdateIntervalMs = 200,
                uiUpdateIntervalMs = 16,
                initialProgressThreshold = 1,
                fileBatchSize = 8,
                statBatchSize = 50,
                watchdogIntervalMs = 100,
                shellCommandTimeoutMs = 10000,
                progressPhaseReplacingMax = 95,
                progressPhaseVerifyingStart = 90,
                progressPhaseVerifyingMax = 100,
            )
        }
    }
}
