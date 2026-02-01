package com.example.tfgwj.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.widget.Toast
import com.example.tfgwj.utils.FileTimeModifier
import com.example.tfgwj.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

/**
 * 时间选择器帮助类
 * 提供日期时间选择对话框
 */
class TimePickerHelper(
    private val context: Context,
    private val scope: CoroutineScope
) {
    
    /**
     * 回调接口
     */
    interface OnTimeSelectedListener {
        fun onTimeSelected(timeMillis: Long, formattedTime: String)
        fun onApplyStarted()
        fun onApplyCompleted(fileCount: Int, formattedTime: String)
        fun onApplyFailed(error: String)
    }
    
    private var listener: OnTimeSelectedListener? = null
    
    fun setOnTimeSelectedListener(listener: OnTimeSelectedListener) {
        this.listener = listener
    }
    
    /**
     * 显示日期时间选择器
     * @param initialTimeMillis 初始时间（毫秒）
     */
    fun showDateTimePicker(initialTimeMillis: Long = System.currentTimeMillis()) {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = initialTimeMillis
        }
        
        // 先显示日期选择
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                
                // 再显示时间选择
                showTimePicker(calendar)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).apply {
            setTitle("选择日期")
            show()
        }
    }
    
    private fun showTimePicker(calendar: Calendar) {
        TimePickerDialog(
            context,
            { _, hourOfDay, minute ->
                calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                calendar.set(Calendar.MINUTE, minute)
                calendar.set(Calendar.SECOND, 0)
                
                val selectedTime = calendar.timeInMillis
                val formattedTime = FileTimeModifier.formatTime(selectedTime)
                
                listener?.onTimeSelected(selectedTime, formattedTime)
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true // 24小时制
        ).apply {
            setTitle("选择时间")
            show()
        }
    }
    
    /**
     * 应用自定义时间到文件夹
     */
    fun applyTimeToFolder(folderPath: String, timeMillis: Long) {
        listener?.onApplyStarted()
        
        scope.launch {
            try {
                AppLogger.func("applyTimeToFolder", "开始应用自定义时间", true, "Path: $folderPath")
                val (count, _) = withContext(Dispatchers.IO) {
                    FileTimeModifier.setCustomTime(folderPath, timeMillis)
                }
                
                val formattedTime = FileTimeModifier.formatTime(timeMillis)
                AppLogger.func("applyTimeToFolder", "应用自定义时间完成", true, "文件数: $count")
                listener?.onApplyCompleted(count, formattedTime)
                
            } catch (e: Exception) {
                AppLogger.func("applyTimeToFolder", "应用自定义时间失败", false, e.message ?: "未知错误")
                listener?.onApplyFailed(e.message ?: "未知错误")
            }
        }
    }
    
    /**
     * 一键随机时间
     */
    fun applyRandomTime(folderPath: String) {
        listener?.onApplyStarted()
        
        scope.launch {
            try {
                val (count, randomTime) = withContext(Dispatchers.IO) {
                    FileTimeModifier.randomizeTime(folderPath)
                }
                
                val formattedTime = FileTimeModifier.formatTime(randomTime)
                listener?.onApplyCompleted(count, formattedTime)
                
            } catch (e: Exception) {
                listener?.onApplyFailed(e.message ?: "未知错误")
            }
        }
    }
}
