package com.example.tfgwj.utils

import java.util.LinkedList

/**
 * ProgressPredictor - V7.0.0 User Experience Enhancement
 *
 * Predicts remaining time based on historical processing speed:
 * - Rolling average speed calculation
 * - Adaptive window size based on data stability
 * - Exponential smoothing for noise reduction
 *
 * Usage:
 * - Initialize with total items count
 * - Update with processed count at regular intervals
 * - Get estimated remaining time
 */
class ProgressPredictor(
    private val totalItems: Int,
    private val windowSize: Int = 10, // Number of samples for rolling average
) {
    // Timestamp of first update (ms)
    private var startTime: Long = 0

    // Track progress samples for speed calculation
    private data class SpeedSample(
        val timestamp: Long,
        val processedCount: Int,
        val bytesProcessed: Long = 0,
    )

    private val samples = LinkedList<SpeedSample>()

    // Current speed (items per second)
    @Volatile
    var currentSpeed: Float = 0f
        private set

    // Average speed (items per second) - smoothed
    @Volatile
    var averageSpeed: Float = 0f
        private set

    // Total bytes processed
    @Volatile
    var totalBytesProcessed: Long = 0
        private set

    // Is predictor initialized
    val isInitialized: Boolean
        get() = startTime > 0

    /**
     * Initialize predictor with start time
     */
    fun start() {
        startTime = System.currentTimeMillis()
        samples.clear()
        currentSpeed = 0f
        averageSpeed = 0f
        totalBytesProcessed = 0
    }

    /**
     * Update progress with processed count
     * @param processedCount Number of items processed so far
     * @param bytesProcessed Total bytes processed (optional, for byte-based prediction)
     */
    fun update(
        processedCount: Int,
        bytesProcessed: Long = 0,
    ): SpeedInfo {
        val currentTime = System.currentTimeMillis()

        // Initialize on first update
        if (startTime == 0L) {
            startTime = currentTime
        }

        // Add sample
        val sample = SpeedSample(currentTime, processedCount, bytesProcessed)
        samples.addLast(sample)

        // Maintain window size
        while (samples.size > windowSize) {
            samples.removeFirst()
        }

        // Calculate current speed (items per second)
        if (samples.size >= 2) {
            val oldest = samples.first()
            val latest = samples.last()
            val timeDiff = (latest.timestamp - oldest.timestamp) / 1000f
            val countDiff = latest.processedCount - oldest.processedCount

            if (timeDiff > 0) {
                currentSpeed = countDiff / timeDiff
            }
        }

        // Calculate average speed with exponential smoothing
        if (samples.size >= 2) {
            val elapsed = (currentTime - startTime) / 1000f
            if (elapsed > 0) {
                val rawAverage = processedCount / elapsed
                // Exponential smoothing: 70% average, 30% current
                averageSpeed =
                    if (averageSpeed > 0) {
                        0.7f * averageSpeed + 0.3f * rawAverage
                    } else {
                        rawAverage
                    }
            }
        }

        // Update total bytes
        totalBytesProcessed = bytesProcessed

        // Return speed info
        return calculateSpeedInfo(processedCount)
    }

    /**
     * Calculate speed info including estimated remaining time
     */
    private fun calculateSpeedInfo(processedCount: Int): SpeedInfo {
        val remaining = (totalItems - processedCount).coerceAtLeast(0)

        // Use average speed for more stable prediction
        val speedToUse = if (averageSpeed > 0) averageSpeed else currentSpeed

        // Calculate estimated remaining time
        val estimatedSeconds =
            if (speedToUse > 0) {
                remaining / speedToUse
            } else {
                -1f // Unable to estimate
            }

        // Calculate progress percentage
        val progress =
            if (totalItems > 0) {
                (processedCount.toFloat() / totalItems * 100).toInt()
            } else {
                0
            }

        return SpeedInfo(
            processed = processedCount,
            total = totalItems,
            progress = progress,
            currentSpeed = currentSpeed,
            averageSpeed = averageSpeed,
            estimatedRemainingSeconds = estimatedSeconds,
            bytesProcessed = totalBytesProcessed,
        )
    }

    /**
     * Get current speed info without updating
     */
    fun getSpeedInfo(processedCount: Int): SpeedInfo {
        return calculateSpeedInfo(processedCount)
    }

    /**
     * Reset predictor for reuse
     */
    fun reset() {
        startTime = 0
        samples.clear()
        currentSpeed = 0f
        averageSpeed = 0f
        totalBytesProcessed = 0
    }

    /**
     * Speed information data class
     */
    data class SpeedInfo(
        val processed: Int,
        val total: Int,
        val progress: Int,
        val currentSpeed: Float,
        val averageSpeed: Float,
        val estimatedRemainingSeconds: Float,
        val bytesProcessed: Long,
    ) {
        /**
         * Get formatted remaining time string
         */
        fun getFormattedRemainingTime(): String {
            return if (estimatedRemainingSeconds < 0) {
                "计算中..."
            } else if (estimatedRemainingSeconds < 60) {
                "${estimatedRemainingSeconds.toInt()}秒"
            } else if (estimatedRemainingSeconds < 3600) {
                val minutes = (estimatedRemainingSeconds / 60).toInt()
                val seconds = (estimatedRemainingSeconds % 60).toInt()
                "${minutes}分${seconds}秒"
            } else {
                val hours = (estimatedRemainingSeconds / 3600).toInt()
                val minutes = ((estimatedRemainingSeconds % 3600) / 60).toInt()
                "${hours}小时${minutes}分"
            }
        }

        /**
         * Get formatted speed string
         */
        fun getFormattedSpeed(): String {
            return when {
                averageSpeed >= 1000 -> "${(averageSpeed / 1000).toInt()}K/秒"
                averageSpeed > 0 -> "${"%.1f".format(averageSpeed)}/秒"
                else -> "计算中..."
            }
        }

        /**
         * Get formatted bytes speed
         */
        fun getFormattedBytesSpeed(bytesPerSecond: Float): String {
            return when {
                bytesPerSecond >= 1024 * 1024 -> "${"%.1f".format(bytesPerSecond / (1024 * 1024))} MB/秒"
                bytesPerSecond >= 1024 -> "${(bytesPerSecond / 1024).toInt()} KB/秒"
                bytesPerSecond > 0 -> "${bytesPerSecond.toInt()} B/秒"
                else -> "计算中..."
            }
        }
    }

    companion object {
        private const val TAG = "ProgressPredictor"

        /**
         * Simple time formatter utility
         */
        fun formatRemainingTime(seconds: Float): String {
            return when {
                seconds < 0 -> "计算中..."
                seconds < 60 -> "${seconds.toInt()}秒"
                seconds < 3600 -> {
                    val minutes = (seconds / 60).toInt()
                    val secs = (seconds % 60).toInt()
                    "${minutes}分${secs}秒"
                }
                else -> {
                    val hours = (seconds / 3600).toInt()
                    val minutes = ((seconds % 3600) / 60).toInt()
                    "${hours}小时${minutes}分"
                }
            }
        }
    }
}
