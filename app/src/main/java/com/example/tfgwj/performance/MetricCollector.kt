package com.example.tfgwj.performance

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 性能指标采集器
 *
 * 核心职责：
 * 1. 采集性能指标（线程安全）
 * 2. 内存缓冲队列（避免阻塞主线程）
 * 3. 批量持久化（减少IO开销）
 * 4. 实时通知（用于 Dashboard）
 *
 * 设计原则：
 * - 单例模式，全局唯一实例
 * - 异步采集，不阻塞业务线程
 * - 可配置采样率（默认 100%）
 * - Debug 模式全量采集，Release 模式可配置
 *
 * @version V10.0.0 - Performance Monitoring
 */
object MetricCollector {
    private const val TAG = "MetricCollector"

    // 配置参数
    private var sampleRate: Double = 1.0 // 采样率 (0.0 - 1.0)
    private var bufferSize: Int = 1000 // 缓冲区大小
    private var flushIntervalMs: Long = 5000 // 刷新间隔 (ms)

    // 内存缓冲队列（线程安全）
    private val buffer = ConcurrentLinkedQueue<PerformanceMetric>()

    // 实时通知通道
    private val metricChannel = Channel<PerformanceMetric>(Channel.BUFFERED)

    // 协程作用域
    private val collectorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 状态标志
    private val isInitialized = AtomicBoolean(false)
    private val isCollecting = AtomicBoolean(false)

    // 统计信息
    private val totalCollected = AtomicLong(0)
    private val totalFlushed = AtomicLong(0)

    /**
     * 初始化采集器
     * @param sampleRate 采样率 (0.0 - 1.0)
     * @param bufferSize 缓冲区大小
     * @param flushIntervalMs 刷新间隔 (ms)
     */
    fun init(
        sampleRate: Double = 1.0,
        bufferSize: Int = 1000,
        flushIntervalMs: Long = 5000,
    ) {
        if (isInitialized.get()) {
            Log.w(TAG, "MetricCollector already initialized")
            return
        }

        this.sampleRate = sampleRate.coerceIn(0.0, 1.0)
        this.bufferSize = bufferSize
        this.flushIntervalMs = flushIntervalMs

        isInitialized.set(true)
        isCollecting.set(true)

        // 启动定时刷新任务
        startFlushTask()

        Log.i(TAG, "MetricCollector initialized: sampleRate=$sampleRate, bufferSize=$bufferSize, flushInterval=${flushIntervalMs}ms")
    }

    /**
     * 记录性能指标
     * @param metric 性能指标
     */
    fun record(metric: PerformanceMetric) {
        if (!isCollecting.get()) return

        // 采样判断
        if (Math.random() > sampleRate) return

        // 添加到缓冲队列
        if (buffer.size < bufferSize) {
            buffer.offer(metric)
            totalCollected.incrementAndGet()

            // 实时通知
            collectorScope.launch {
                try {
                    metricChannel.send(metric)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send metric notification: ${e.message}")
                }
            }
        } else {
            Log.w(TAG, "Buffer full, dropping metric: ${metric.name}")
        }
    }

    /**
     * 便捷方法：记录 IO 性能指标
     */
    fun recordIO(
        name: String,
        value: Double,
        unit: String,
        tags: Map<String, String> = emptyMap(),
    ) {
        record(
            PerformanceMetric(
                category = MetricCategory.IO,
                name = name,
                value = value,
                unit = unit,
                tags = tags,
            ),
        )
    }

    /**
     * 便捷方法：记录内存指标
     */
    fun recordMemory(
        name: String,
        value: Double,
        unit: String,
    ) {
        record(
            PerformanceMetric(
                category = MetricCategory.MEMORY,
                name = name,
                value = value,
                unit = unit,
            ),
        )
    }

    /**
     * 便捷方法：记录任务指标
     */
    fun recordTask(
        name: String,
        value: Double,
        unit: String,
        tags: Map<String, String> = emptyMap(),
    ) {
        record(
            PerformanceMetric(
                category = MetricCategory.TASK,
                name = name,
                value = value,
                unit = unit,
                tags = tags,
            ),
        )
    }

    /**
     * 便捷方法：记录网络指标
     */
    fun recordNetwork(
        name: String,
        value: Double,
        unit: String,
        tags: Map<String, String> = emptyMap(),
    ) {
        record(
            PerformanceMetric(
                category = MetricCategory.NETWORK,
                name = name,
                value = value,
                unit = unit,
                tags = tags,
            ),
        )
    }

    /**
     * 获取实时指标流（用于 Dashboard）
     */
    fun getMetricFlow(): Flow<PerformanceMetric> = metricChannel.receiveAsFlow()

    /**
     * 获取缓冲区中的所有指标
     */
    fun getBufferedMetrics(): List<PerformanceMetric> {
        val metrics = mutableListOf<PerformanceMetric>()
        while (true) {
            val metric = buffer.poll() ?: break
            metrics.add(metric)
        }
        return metrics
    }

    /**
     * 获取统计信息
     */
    fun getStats(): CollectorStats {
        return CollectorStats(
            totalCollected = totalCollected.get(),
            totalFlushed = totalFlushed.get(),
            bufferSize = buffer.size,
            isCollecting = isCollecting.get(),
        )
    }

    /**
     * 暂停采集
     */
    fun pause() {
        isCollecting.set(false)
        Log.i(TAG, "MetricCollector paused")
    }

    /**
     * 恢复采集
     */
    fun resume() {
        isCollecting.set(true)
        Log.i(TAG, "MetricCollector resumed")
    }

    /**
     * 清空缓冲区
     */
    fun clear() {
        buffer.clear()
        Log.i(TAG, "MetricCollector buffer cleared")
    }

    /**
     * 停止采集器
     */
    fun stop() {
        isCollecting.set(false)
        collectorScope.cancel()
        buffer.clear()
        metricChannel.close()
        Log.i(TAG, "MetricCollector stopped")
    }

    /**
     * 启动定时刷新任务
     */
    private fun startFlushTask() {
        collectorScope.launch {
            while (isActive && isCollecting.get()) {
                delay(flushIntervalMs)
                flush()
            }
        }
    }

    /**
     * 刷新缓冲区（持久化到存储）
     */
    private fun flush() {
        val metrics = getBufferedMetrics()
        if (metrics.isEmpty()) return

        // TODO: 持久化到 Room DB 或文件
        // 这里先记录日志
        Log.d(TAG, "Flushing ${metrics.size} metrics to storage")
        totalFlushed.addAndGet(metrics.size.toLong())
    }

    /**
     * 采集器统计信息
     */
    data class CollectorStats(
        val totalCollected: Long,
        val totalFlushed: Long,
        val bufferSize: Int,
        val isCollecting: Boolean,
    )
}
