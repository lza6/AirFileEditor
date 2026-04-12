package com.example.tfgwj.performance

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

/**
 * 性能监控管理器
 *
 * 核心职责：
 * 1. 整合所有性能监控功能
 * 2. 提供统一的监控接口
 * 3. 管理监控生命周期
 * 4. 提供性能诊断功能
 *
 * @version V10.0.0 - Performance Monitoring
 */
object PerformanceMonitor {
    private const val TAG = "PerformanceMonitor"

    private var context: Context? = null
    private val monitorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 任务计时器
    private val taskStartTimes = mutableMapOf<String, Long>()

    // IO 统计
    private val totalBytesCopied = AtomicLong(0)
    private val totalCopyTime = AtomicLong(0)
    private val totalFilesCopied = AtomicLong(0)
    private val mmapFallbackCount = AtomicLong(0)
    private val incrementalSkipCount = AtomicLong(0)

    /**
     * 初始化性能监控
     */
    fun init(
        context: Context,
        sampleRate: Double = 1.0,
    ) {
        this.context = context.applicationContext
        MetricCollector.init(sampleRate = sampleRate)
        Log.i(TAG, "PerformanceMonitor initialized with sampleRate=$sampleRate")
    }

    /**
     * 开始任务计时
     * @param taskId 任务ID
     */
    fun startTask(taskId: String) {
        taskStartTimes[taskId] = System.currentTimeMillis()
        Log.d(TAG, "Task started: $taskId")
    }

    /**
     * 结束任务计时
     * @param taskId 任务ID
     * @param success 是否成功
     * @param filesProcessed 处理的文件数
     */
    fun endTask(
        taskId: String,
        success: Boolean,
        filesProcessed: Int = 0,
    ) {
        val startTime = taskStartTimes.remove(taskId) ?: return
        val duration = System.currentTimeMillis() - startTime

        MetricCollector.recordTask(
            name = MetricNames.TASK_DURATION,
            value = duration.toDouble(),
            unit = MetricUnits.MILLISECONDS,
            tags = mapOf("taskId" to taskId, "success" to success.toString()),
        )

        if (filesProcessed > 0) {
            MetricCollector.recordTask(
                name = MetricNames.TASK_FILES_PROCESSED,
                value = filesProcessed.toDouble(),
                unit = MetricUnits.COUNT,
                tags = mapOf("taskId" to taskId),
            )
        }

        Log.i(TAG, "Task ended: $taskId, duration=${duration}ms, success=$success, files=$filesProcessed")
    }

    /**
     * 记录 IO 复制操作
     * @param bytesCopied 复制的字节数
     * @param durationMs 耗时（毫秒）
     * @param usedMmap 是否使用了 mmap
     * @param wasIncrementalSkip 是否因为增量检测而跳过
     */
    fun recordIOCopy(
        bytesCopied: Long,
        durationMs: Long,
        usedMmap: Boolean = false,
        wasIncrementalSkip: Boolean = false,
    ) {
        totalBytesCopied.addAndGet(bytesCopied)
        totalCopyTime.addAndGet(durationMs)
        totalFilesCopied.incrementAndGet()

        if (!usedMmap) {
            mmapFallbackCount.incrementAndGet()
        }

        if (wasIncrementalSkip) {
            incrementalSkipCount.incrementAndGet()
        }

        // 计算复制速度 (MB/s)
        val speedMBps =
            if (durationMs > 0) {
                (bytesCopied.toDouble() / (1024 * 1024)) / (durationMs.toDouble() / 1000)
            } else {
                0.0
            }

        MetricCollector.recordIO(
            name = MetricNames.IO_COPY_SPEED,
            value = speedMBps,
            unit = MetricUnits.MB_PER_SECOND,
            tags = mapOf("mmap" to usedMmap.toString()),
        )
    }

    /**
     * 记录内存使用情况
     */
    fun recordMemoryUsage() {
        val runtime = Runtime.getRuntime()
        val usedMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMemoryMB = runtime.maxMemory() / (1024 * 1024)
        val usagePercent = (usedMemoryMB.toDouble() / maxMemoryMB * 100)

        MetricCollector.recordMemory(MetricNames.MEMORY_USED, usedMemoryMB.toDouble(), MetricUnits.MEGABYTES)
        MetricCollector.recordMemory(MetricNames.MEMORY_MAX, maxMemoryMB.toDouble(), MetricUnits.MEGABYTES)
        MetricCollector.recordMemory(MetricNames.MEMORY_USAGE_PERCENT, usagePercent, MetricUnits.PERCENT)
    }

    /**
     * 获取 IO 统计信息
     */
    fun getIOStats(): IOStats {
        val totalBytes = totalBytesCopied.get()
        val totalTime = totalCopyTime.get()
        val totalFiles = totalFilesCopied.get()
        val mmapFallbacks = mmapFallbackCount.get()
        val incrementalSkips = incrementalSkipCount.get()

        val avgSpeedMBps =
            if (totalTime > 0) {
                (totalBytes.toDouble() / (1024 * 1024)) / (totalTime.toDouble() / 1000)
            } else {
                0.0
            }

        val mmapFallbackRate =
            if (totalFiles > 0) {
                (mmapFallbacks.toDouble() / totalFiles * 100)
            } else {
                0.0
            }

        val incrementalHitRate =
            if (totalFiles > 0) {
                (incrementalSkips.toDouble() / totalFiles * 100)
            } else {
                0.0
            }

        return IOStats(
            totalBytesCopied = totalBytes,
            totalCopyTimeMs = totalTime,
            totalFilesCopied = totalFiles,
            avgSpeedMBps = avgSpeedMBps,
            mmapFallbackRate = mmapFallbackRate,
            incrementalHitRate = incrementalHitRate,
        )
    }

    /**
     * 获取性能诊断报告
     */
    fun getDiagnosticReport(): DiagnosticReport {
        val ioStats = getIOStats()
        val collectorStats = MetricCollector.getStats()

        val issues = mutableListOf<DiagnosticIssue>()

        // 检查 IO 速度
        if (ioStats.avgSpeedMBps < 10.0 && ioStats.totalFilesCopied > 10) {
            issues.add(
                DiagnosticIssue(
                    severity = IssueSeverity.WARNING,
                    category = "IO",
                    message = "IO 速度较低 (${String.format("%.1f", ioStats.avgSpeedMBps)} MB/s)",
                    suggestion = "建议检查存储设备性能，或降低并发度",
                ),
            )
        }

        // 检查 mmap 回退率
        if (ioStats.mmapFallbackRate > 50.0 && ioStats.totalFilesCopied > 10) {
            issues.add(
                DiagnosticIssue(
                    severity = IssueSeverity.INFO,
                    category = "IO",
                    message = "mmap 回退率较高 (${String.format("%.1f", ioStats.mmapFallbackRate)}%)",
                    suggestion = "设备可能不支持 mmap，已自动使用 NIO 替代",
                ),
            )
        }

        // 检查内存使用
        val runtime = Runtime.getRuntime()
        val memoryUsagePercent = (runtime.totalMemory() - runtime.freeMemory()).toDouble() / runtime.maxMemory() * 100
        if (memoryUsagePercent > 80.0) {
            issues.add(
                DiagnosticIssue(
                    severity = IssueSeverity.WARNING,
                    category = "Memory",
                    message = "内存使用率较高 (${String.format("%.1f", memoryUsagePercent)}%)",
                    suggestion = "建议降低并发度或缓冲区大小",
                ),
            )
        }

        // 检查增量更新命中率
        if (ioStats.incrementalHitRate < 20.0 && ioStats.totalFilesCopied > 20) {
            issues.add(
                DiagnosticIssue(
                    severity = IssueSeverity.INFO,
                    category = "IO",
                    message = "增量更新命中率较低 (${String.format("%.1f", ioStats.incrementalHitRate)}%)",
                    suggestion = "大部分文件需要更新，这是正常的首次替换行为",
                ),
            )
        }

        // 检查设备 CPU 核心数
        val cpuCores = Runtime.getRuntime().availableProcessors()
        if (cpuCores <= 2) {
            issues.add(
                DiagnosticIssue(
                    severity = IssueSeverity.INFO,
                    category = "CPU",
                    message = "设备 CPU 核心数较少 ($cpuCores 核)",
                    suggestion = "已自动降低并发度以避免卡顿",
                ),
            )
        }

        // 检查可用内存
        val maxMemoryMB = runtime.maxMemory() / (1024 * 1024)
        if (maxMemoryMB < 256) {
            issues.add(
                DiagnosticIssue(
                    severity = IssueSeverity.WARNING,
                    category = "Memory",
                    message = "设备可用内存较少 (${maxMemoryMB}MB)",
                    suggestion = "已自动使用较小的缓冲区大小",
                ),
            )
        }

        return DiagnosticReport(
            timestamp = System.currentTimeMillis(),
            ioStats = ioStats,
            collectorStats = collectorStats,
            issues = issues,
        )
    }

    /**
     * 获取诊断摘要（用于通知）
     */
    fun getDiagnosticSummary(): String {
        val report = getDiagnosticReport()
        if (report.issues.isEmpty()) {
            return "✅ 性能正常"
        }

        val warningCount = report.issues.count { it.severity == IssueSeverity.WARNING }
        val errorCount = report.issues.count { it.severity == IssueSeverity.ERROR }

        return buildString {
            if (errorCount > 0) append("❌ $errorCount 个错误 ")
            if (warningCount > 0) append("⚠️ $warningCount 个警告")
        }.toString().trim()
    }

    // 实时性能快照
    private val lastIOWriteTime = AtomicLong(0)
    private val lastIPCTime = AtomicLong(0)

    /**
     * 记录 IO 写入详细延迟 (V10)
     */
    fun recordIOWriteLatency(
        durationMs: Long,
        writeSize: Long = 0,
    ) {
        lastIOWriteTime.set(System.currentTimeMillis())
        MetricCollector.recordIO(
            name = MetricNames.IO_WRITE_LATENCY,
            value = durationMs.toDouble(),
            unit = MetricUnits.MILLISECONDS,
            tags = if (writeSize > 0) mapOf("size" to writeSize.toString()) else emptyMap(),
        )

        // 如果写入延迟过高 (> 500ms)，触发预警记录
        if (durationMs > 500) {
            Log.w(TAG, "High IO Wait detected: ${durationMs}ms for ${writeSize} bytes")
        }
    }

    /**
     * 记录 IPC (Shizuku) 详细延迟 (V10)
     */
    fun recordIPCLatency(
        durationMs: Long,
        transferSize: Long = 0,
        methodName: String = "unknown",
    ) {
        lastIPCTime.set(System.currentTimeMillis())
        MetricCollector.recordIPC(
            name = MetricNames.IPC_BINDER_LATENCY,
            value = durationMs.toDouble(),
            unit = MetricUnits.MILLISECONDS,
            tags = mapOf("method" to methodName),
        )

        if (transferSize > 0) {
            MetricCollector.recordIPC(
                name = MetricNames.IPC_TRANSFER_SIZE,
                value = transferSize.toDouble(),
                unit = "B",
                tags = mapOf("method" to methodName),
            )
        }

        if (durationMs > 100) {
            Log.w(TAG, "High IPC Latency: ${durationMs}ms in $methodName")
        }
    }

    /**
     * 重置统计信息
     */
    fun resetStats() {
        totalBytesCopied.set(0)
        totalCopyTime.set(0)
        totalFilesCopied.set(0)
        mmapFallbackCount.set(0)
        incrementalSkipCount.set(0)
        taskStartTimes.clear()
        MetricCollector.clear()
        Log.i(TAG, "Performance stats reset")
    }

    /**
     * 性能优化建议系统
     * 基于设备配置自动推荐最佳参数
     */
    fun getOptimizationRecommendations(): List<OptimizationRecommendation> {
        val recommendations = mutableListOf<OptimizationRecommendation>()
        val ioStats = getIOStats()
        val runtime = Runtime.getRuntime()

        // CPU 核心数 → 推荐并发度
        val cpuCores = runtime.availableProcessors()
        val recommendedConcurrency =
            when {
                cpuCores >= 8 -> 16
                cpuCores >= 4 -> 8
                cpuCores >= 2 -> 4
                else -> 2
            }
        recommendations.add(
            OptimizationRecommendation(
                category = "Concurrency",
                parameter = "maxConcurrentTasks",
                currentValue = "N/A",
                recommendedValue = recommendedConcurrency.toString(),
                reason = "基于 $cpuCores 个 CPU 核心自动计算",
                impact = "提高并发处理能力，充分利用多核性能",
            ),
        )

        // 内存大小 → 推荐缓冲区大小
        val maxMemoryMB = runtime.maxMemory() / (1024 * 1024)
        val recommendedBufferSize =
            when {
                maxMemoryMB >= 512 -> 4 * 1024 * 1024 // 4MB
                maxMemoryMB >= 256 -> 2 * 1024 * 1024 // 2MB
                maxMemoryMB >= 128 -> 1 * 1024 * 1024 // 1MB
                else -> 512 * 1024 // 512KB
            }
        recommendations.add(
            OptimizationRecommendation(
                category = "Buffer",
                parameter = "bufferSize",
                currentValue = "N/A",
                recommendedValue = "${recommendedBufferSize / 1024}KB",
                reason = "基于 ${maxMemoryMB}MB 可用内存自动计算",
                impact = "平衡内存使用和IO性能",
            ),
        )

        // IO 速度 → 推荐存储策略
        if (ioStats.totalFilesCopied > 10) {
            val storageStrategy =
                when {
                    ioStats.avgSpeedMBps >= 50 -> "高速存储：使用大缓冲区 + 高并发"
                    ioStats.avgSpeedMBps >= 20 -> "中速存储：使用中等缓冲区 + 中等并发"
                    else -> "低速存储：使用小缓冲区 + 低并发"
                }
            recommendations.add(
                OptimizationRecommendation(
                    category = "Storage",
                    parameter = "storageStrategy",
                    currentValue = "${String.format("%.1f", ioStats.avgSpeedMBps)}MB/s",
                    recommendedValue = storageStrategy,
                    reason = "基于平均IO速度自动判断",
                    impact = "根据存储性能优化复制策略",
                ),
            )
        }

        // mmap 回退率 → 推荐复制模式
        if (ioStats.totalFilesCopied > 10) {
            val copyMode =
                if (ioStats.mmapFallbackRate > 50.0) {
                    "NIO Zero-Copy（设备不支持 mmap）"
                } else {
                    "mmap + NIO Fallback（智能调度）"
                }
            recommendations.add(
                OptimizationRecommendation(
                    category = "CopyMode",
                    parameter = "copyMode",
                    currentValue = "${String.format("%.1f", ioStats.mmapFallbackRate)}% 回退率",
                    recommendedValue = copyMode,
                    reason = "基于 mmap 回退率自动选择",
                    impact = "选择最适合设备的复制模式",
                ),
            )
        }

        return recommendations
    }

    /**
     * 优化建议数据类
     */
    data class OptimizationRecommendation(
        val category: String,
        val parameter: String,
        val currentValue: String,
        val recommendedValue: String,
        val reason: String,
        val impact: String,
    )

    /**
     * 停止性能监控
     */
    fun stop() {
        MetricCollector.stop()
        monitorScope.cancel()
        Log.i(TAG, "PerformanceMonitor stopped")
    }

    /**
     * IO 统计信息
     */
    data class IOStats(
        val totalBytesCopied: Long,
        val totalCopyTimeMs: Long,
        val totalFilesCopied: Long,
        val avgSpeedMBps: Double,
        val mmapFallbackRate: Double,
        val incrementalHitRate: Double,
    )

    /**
     * 诊断报告
     */
    data class DiagnosticReport(
        val timestamp: Long,
        val ioStats: IOStats,
        val collectorStats: MetricCollector.CollectorStats,
        val issues: List<DiagnosticIssue>,
    )

    /**
     * 诊断问题
     */
    data class DiagnosticIssue(
        val severity: IssueSeverity,
        val category: String,
        val message: String,
        val suggestion: String,
    )

    /**
     * 问题严重程度
     */
    enum class IssueSeverity {
        INFO, // 信息
        WARNING, // 警告
        ERROR, // 错误
    }
}
