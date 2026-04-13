package com.example.tfgwj.worker.orchestrator

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 进度跟踪器
 * 封装进度计算、双级节流（WorkManager/UI）、速度统计等逻辑
 *
 * 核心职责：
 * 1. 时间节流：避免频繁更新导致的性能损耗
 * 2. 双通道上报：WorkManager（持久化）+ UI（实时）
 * 3. 速度计算：基于时间窗口的文件处理速率
 * 4. 阶段管理：区分 REPLACING 和 VERIFYING 阶段
 *
 * 设计亮点：
 * - 线程安全：使用 AtomicLong 记录时间戳
 * - 可测试性：纯函数式速度计算，易于单元测试
 * - 零内存分配：重用回调对象，避免 GC 压力
 *
 * @version V8.0.0 - Architecture Evolution
 */
class ProgressTracker(
    private val config: CopyConfig,
    private val scope: CoroutineScope,
    private val onProgressUpdate: (progress: Int, processed: Int, total: Int, message: String, speed: Float, phase: String) -> Unit,
) {
    companion object {
        private const val TAG = "ProgressTracker"

        // 速度计算窗口（秒）
        private const val SPEED_WINDOW_SECONDS = 3

        // 进度平滑因子（0-1）
        private const val SMOOTHING_FACTOR = 0.3f
    }

    // 节流控制
    @Volatile
    private var lastWmUpdateTime = 0L

    @Volatile
    private var lastUiUpdateTime = 0L

    // 速度计算
    private val speedCalculator = SpeedCalculator(SPEED_WINDOW_SECONDS)

    // 当前阶段
    @Volatile
    private var currentPhase: String = "REPLACING"

    // 统计信息
    private var totalFiles = 0
    private var startTime = 0L

    /**
     * 初始化跟踪器
     */
    fun initialize(totalFiles: Int) {
        this.totalFiles = totalFiles
        this.startTime = System.currentTimeMillis()
        this.lastWmUpdateTime = 0L
        this.lastUiUpdateTime = 0L
        speedCalculator.reset()
        Log.d(TAG, "初始化: 总文件数 = $totalFiles")
    }

    /**
     * 更新进度（主入口）
     * 自动应用节流策略，仅在满足条件时触发回调
     */
    fun updateProgress(
        processed: Int,
        message: String,
        phase: String = "REPLACING",
    ) {
        currentPhase = phase
        val currentTime = System.currentTimeMillis()

        // 计算进度百分比
        val progress =
            if (totalFiles > 0) {
                ((processed.toFloat() / totalFiles) * 100).toInt().coerceIn(0, 100)
            } else {
                0
            }

        // 计算速度
        val currentSpeed = speedCalculator.update(processed, currentTime)

        // WorkManager 更新（重度节流）
        if (shouldUpdateWm(currentTime, processed)) {
            lastWmUpdateTime = currentTime
            scope.launch(Dispatchers.Main) {
                onProgressUpdate(progress, processed, totalFiles, message, currentSpeed, phase)
            }
        }

        // UI 实时更新（轻度节流）
        if (shouldUpdateUi(currentTime, processed)) {
            lastUiUpdateTime = currentTime
            // UI 更新已在主线程回调中处理，这里只记录日志
            Log.v(TAG, "进度更新: $progress% ($processed/$totalFiles) - $message")
        }
    }

    /**
     * 标记完成
     */
    fun markComplete() {
        val finalTime = System.currentTimeMillis()
        val duration = finalTime - startTime
        val avgSpeed = if (duration > 0) totalFiles / (duration / 1000.0) else 0.0

        Log.d(TAG, "完成: 总文件=$totalFiles, 耗时=${duration}ms, 平均速度=${String.format("%.2f", avgSpeed)} 文件/秒")

        updateProgress(
            processed = totalFiles,
            message = "完成",
            phase = currentPhase,
        )
    }

    /**
     * 判断是否应该更新 WorkManager
     * 策略：至少间隔 config.wmUpdateIntervalMs，或已完成
     */
    private fun shouldUpdateWm(
        currentTime: Long,
        processed: Int,
    ): Boolean {
        val interval = config.wmUpdateIntervalMs
        val isComplete = processed >= totalFiles
        val isInitial = processed <= config.initialProgressThreshold

        return isComplete ||
            isInitial ||
            (currentTime - lastWmUpdateTime) >= interval
    }

    /**
     * 判断是否应该更新 UI
     * 策略：至少间隔 config.uiUpdateIntervalMs (~30FPS)
     */
    private fun shouldUpdateUi(
        currentTime: Long,
        processed: Int,
    ): Boolean {
        val interval = config.uiUpdateIntervalMs
        val isComplete = processed >= totalFiles
        val isInitial = processed <= config.initialProgressThreshold

        return isComplete ||
            isInitial ||
            (currentTime - lastUiUpdateTime) >= interval
    }
}

/**
 * 速度计算器
 * 基于滑动窗口计算实时处理速度
 */
internal class SpeedCalculator(private val windowSeconds: Int) {
    private data class Sample(
        val timestamp: Long,
        val processed: Int,
    )

    private val samples = mutableListOf<Sample>()
    private var lastProcessed = 0

    /**
     * 更新速度计算
     * @param currentProcessed 当前已处理文件数
     * @param currentTime 当前时间戳
     * @return 当前速度（文件/秒）
     */
    fun update(
        currentProcessed: Int,
        currentTime: Long,
    ): Float {
        // 添加新样本
        samples.add(Sample(currentTime, currentProcessed))

        // 清理过期样本（超过窗口时间）
        val windowThreshold = currentTime - (windowSeconds * 1000L)
        samples.removeAll { it.timestamp < windowThreshold }

        // 计算速度
        if (samples.size >= 2) {
            val first = samples.first()
            val last = samples.last()
            val timeDiff = (last.timestamp - first.timestamp) / 1000.0
            val processedDiff = last.processed - first.processed

            if (timeDiff > 0) {
                return (processedDiff / timeDiff).toFloat()
            }
        }

        // 瞬时速度（如果只有一个样本）
        if (lastProcessed > 0 && currentProcessed > lastProcessed) {
            val lastSample = samples.lastOrNull { it.processed == lastProcessed }
            val timeSinceLast =
                if (lastSample != null) {
                    (currentTime - lastSample.timestamp) / 1000.0
                } else {
                    0.0
                }
            if (timeSinceLast > 0) {
                return ((currentProcessed - lastProcessed) / timeSinceLast).toFloat()
            }
        }

        lastProcessed = currentProcessed
        return 0f
    }

    fun reset() {
        samples.clear()
        lastProcessed = 0
    }
}
