package com.example.tfgwj.performance.scheduler

import android.content.Context
import android.util.Log
import com.example.tfgwj.performance.PerformanceMonitor
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * 自适应并发许可调度器
 *
 * 核心逻辑：
 * 1. 周期性监控系统负载（CPU、内存）
 * 2. 结合 PerformanceMonitor 的实时 IO 指标
 * 3. 动态调整工作协程的并发许可证数量
 * 4. 确保在极致性能和系统稳定性之间取得平衡
 *
 * @version V10.0.0 - Performance Monitoring
 */
class AdaptivePermitScheduler(
    private val context: Context,
    private val basePermits: Int,
    private val minPermits: Int = 2,
    private val maxPermits: Int = 32
) {
    companion object {
        private const val TAG = "AdaptiveScheduler"
        private const val CHECK_INTERVAL_MS = 2000L
    }

    @Volatile
    private var scope = createScope()
    private val currentPermits = AtomicInteger(basePermits)
    private var schedulerJob: Job? = null

    // 用于通知外部并发度已更改
    private var onPermitsChanged: ((Int) -> Unit)? = null

    private fun createScope(): CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun start(callback: (Int) -> Unit) {
        this.onPermitsChanged = callback

        if (schedulerJob?.isActive == true) return
        if (scope.coroutineContext[Job]?.isActive != true) {
            scope = createScope()
        }

        schedulerJob = scope.launch {
            // V10: 立即执行一次更新，确保初始状态正确
            updatePermits()
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                updatePermits()
            }
        }
    }

    fun stop() {
        schedulerJob?.cancel()
        schedulerJob = null
        scope.cancel()
    }

    fun getCurrentPermits(): Int = currentPermits.get()

    // V10: 允许注入内存获取逻辑以便测试
    var memoryUsageProvider: () -> Double = {
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory())
        val maxMemory = runtime.maxMemory()
        usedMemory.toDouble() / maxMemory
    }

    private fun updatePermits() {
        // 1. 内存压力检测
        val memoryUsage = memoryUsageProvider()

        // 2. IO 性能反馈
        val ioStats = PerformanceMonitor.getIOStats()

        var targetPermits = basePermits

        // 策略 A: 内存告急 (使用率 > 85%) -> 强制削减并发
        if (memoryUsage > 0.85) {
            targetPermits = (targetPermits * 0.5).toInt().coerceAtLeast(minPermits)
            Log.w(TAG, "Memory pressure detected (${(memoryUsage * 100).toInt()}%). Reducing permits to $targetPermits")
        }
        // 策略 B: IO 速度极快 (Avg > 50MB/s) 且内存充足 -> 尝试激进提升
        else if (ioStats.avgSpeedMBps > 50.0 && memoryUsage < 0.5) {
            targetPermits = (targetPermits * 1.5).toInt().coerceAtMost(maxPermits)
        }
        // 策略 C: mmap 回退率过高 -> 降低并发，因为 NIO Fallback 内存开销更大
        if (ioStats.mmapFallbackRate > 50.0) {
            targetPermits = (targetPermits * 0.8).toInt().coerceAtLeast(minPermits)
        }

        val finalPermits = targetPermits.coerceIn(minPermits, maxPermits)
        val oldPermits = currentPermits.getAndSet(finalPermits)

        if (oldPermits != finalPermits) {
            Log.i(TAG, "Permits adjusted: $oldPermits -> $finalPermits (Reason: Mem=${(memoryUsage * 100).toInt()}%, IO=${String.format("%.1f", ioStats.avgSpeedMBps)}MB/s)")
            onPermitsChanged?.invoke(finalPermits)
        }
    }
}
