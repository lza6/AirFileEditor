package com.example.tfgwj.utils

import android.util.Log
import java.io.File
import java.util.Calendar
import java.util.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文件时间修改器
 * 用于修改文件和目录的最后修改时间
 */
object FileTimeModifier {
    
    private const val TAG = "FileTimeModifier"
    
    /**
     * 修改文件/目录时间（递归应用到所有子项）
     * 
     * @param path 目标路径
     * @param timeMillis 目标时间戳（毫秒）
     * @param progressCallback 进度回调 (current, total)
     * @return 成功修改的文件数
     */
    suspend fun modifyTime(
        path: String, 
        timeMillis: Long,
        progressCallback: ((Int, Int) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        val root = File(path)
        if (!root.exists()) {
            AppLogger.func("modifyTime", "检查路径", false, "路径不存在: $path")
            return@withContext 0
        }
        
        // 1. 先统计总数（使用 walk().count() 比较快且省内存）
        val total = root.walk().count()
        var current = 0
        var success = 0
        
        AppLogger.func("modifyTime", "开始修改时间", true, "总计: $total | 路径: $path")

        // 2. 迭代修改
        root.walk().forEach { f ->
            try {
                if (f.setLastModified(timeMillis)) {
                    success++
                }
            } catch (e: Exception) {
                AppLogger.file("SET_TIME", f.absolutePath, false, e.message)
            }
            current++
            // 降低回调频率，每 10 个文件回调一次或最后一次
            if (current % 10 == 0 || current == total) {
                progressCallback?.invoke(current, total)
            }
        }
        
        AppLogger.func("modifyTime", "修改完成", success == total, "成功: $success/$total")
        success
    }
    
    /**
     * 生成随机时间（按照用户指定规则）
     * 规则：2027-2029年, 9-12月, 15-29日, 20-23时, 50-56分, 50-58秒
     */
    fun generateRandomTime(): Long {
        val random = Random()
        val calendar = Calendar.getInstance()
        
        // 年份: 2027-2029
        calendar.set(Calendar.YEAR, 2027 + random.nextInt(3))
        
        // 月份: 9-12 (Calendar 月份从0开始)
        calendar.set(Calendar.MONTH, 8 + random.nextInt(4))
        
        // 日期: 15-29
        calendar.set(Calendar.DAY_OF_MONTH, 15 + random.nextInt(15))
        
        // 小时: 20-23
        calendar.set(Calendar.HOUR_OF_DAY, 20 + random.nextInt(4))
        
        // 分钟: 50-56
        calendar.set(Calendar.MINUTE, 50 + random.nextInt(7))
        
        // 秒数: 50-58
        calendar.set(Calendar.SECOND, 50 + random.nextInt(9))
        
        calendar.set(Calendar.MILLISECOND, 0)
        
        return calendar.timeInMillis
    }

    
    /**
     * 一键随机修改时间
     */
    suspend fun randomizeTime(
        path: String,
        progressCallback: ((Int, Int) -> Unit)? = null
    ): Pair<Int, Long> {
        val randomTime = generateRandomTime()
        val count = modifyTime(path, randomTime, progressCallback)
        return Pair(count, randomTime)
    }
    
    /**
     * 设置自定义时间
     */
    suspend fun setCustomTime(
        path: String,
        timeMillis: Long,
        progressCallback: ((Int, Int) -> Unit)? = null
    ): Pair<Int, Long> {
        val count = modifyTime(path, timeMillis, progressCallback)
        return Pair(count, timeMillis)
    }
    
    /**
     * 获取文件/目录的当前时间
     */
    fun getFileTime(path: String): Long? {
        val file = File(path)
        return if (file.exists()) {
            file.lastModified()
        } else {
            null
        }
    }
    
    /**
     * 格式化时间戳为可读字符串
     */
    fun formatTime(timeMillis: Long): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timeMillis
        
        return String.format(
            "%04d-%02d-%02d %02d:%02d:%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND)
        )
    }
    
    /**
     * 解析时间字符串为时间戳
     * 格式: "yyyy-MM-dd HH:mm:ss"
     */
    fun parseTime(timeString: String): Long? {
        return try {
            val parts = timeString.split(" ")
            val dateParts = parts[0].split("-")
            val timeParts = parts[1].split(":")
            
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.YEAR, dateParts[0].toInt())
            calendar.set(Calendar.MONTH, dateParts[1].toInt() - 1)
            calendar.set(Calendar.DAY_OF_MONTH, dateParts[2].toInt())
            calendar.set(Calendar.HOUR_OF_DAY, timeParts[0].toInt())
            calendar.set(Calendar.MINUTE, timeParts[1].toInt())
            calendar.set(Calendar.SECOND, timeParts[2].toInt())
            calendar.set(Calendar.MILLISECOND, 0)
            
            calendar.timeInMillis
        } catch (e: Exception) {
            Log.e(TAG, "解析时间失败: $timeString", e)
            null
        }
    }
    
    /**
     * 统计目录下文件和文件夹数量
     */
    fun countItems(path: String): Pair<Int, Int> {
        val root = File(path)
        if (!root.exists()) return Pair(0, 0)
        
        var fileCount = 0
        var dirCount = 0
        
        root.walk().forEach {
            if (it.isDirectory) dirCount++ else fileCount++
        }
        
        return Pair(fileCount, dirCount)
    }
}
