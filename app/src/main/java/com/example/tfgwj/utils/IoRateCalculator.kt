package com.example.tfgwj.utils

import java.util.LinkedList

/**
 * IO 速率计算器
 * 用于计算实时传输速度 (MB/s) 并维护历史数据供图表显示
 */
class IoRateCalculator(private val windowSizeMs: Long = 1000) {

    private var lastBytes: Long = 0
    private var lastTime: Long = 0
    private var currentSpeed: Float = 0f
    
    // 历史速度数据 (最近 60 个点，每秒一个)
    val speedHistory = LinkedList<Float>()
    private val maxHistorySize = 60
    
    init {
        reset()
    }
    
    fun reset() {
        lastBytes = 0
        lastTime = System.currentTimeMillis()
        currentSpeed = 0f
        speedHistory.clear()
        // 填充初始零值
        repeat(maxHistorySize) { speedHistory.add(0f) }
    }
    
    /**
     * 更新当前已处理的总字节数
     * @return 当前瞬时速度 (MB/s)
     */
    fun update(totalProcessedBytes: Long): Float {
        val now = System.currentTimeMillis()
        val timeDiff = now - lastTime
        
        if (timeDiff >= windowSizeMs) {
            val bytesDiff = totalProcessedBytes - lastBytes
            
            // 计算速度 (MB/s)
            // 避免除零，且转换为 Float
            currentSpeed = if (timeDiff > 0) {
                (bytesDiff.toDouble() / 1024 / 1024 / (timeDiff.toDouble() / 1000.0)).toFloat()
            } else {
                0f
            }
            
            // 更新历史记录
            speedHistory.addLast(currentSpeed)
            if (speedHistory.size > maxHistorySize) {
                speedHistory.removeFirst()
            }
            
            lastBytes = totalProcessedBytes
            lastTime = now
        }
        
        return currentSpeed
    }
    
    /**
     * 获取当前速度
     */
    fun getSpeed(): Float = currentSpeed
    
    /**
     * 获取最近一分钟的平均速度
     */
    fun getAverageSpeed(): Float {
        if (speedHistory.isEmpty()) return 0f
        return speedHistory.average().toFloat()
    }
}
