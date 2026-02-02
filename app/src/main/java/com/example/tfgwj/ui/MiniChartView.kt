package com.example.tfgwj.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.util.LinkedList
import com.example.tfgwj.R

/**
 * 迷你折线图 View
 * 用于悬浮球显示实时 IO 速率
 */
class MiniChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val dataPoints = LinkedList<Float>()
    private val maxPoints = 60
    private var maxValue = 1f
    
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f // dp
        color = Color.parseColor("#4CAF50") // 默认绿色
    }
    
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#334CAF50") // 半透明填充
    }
    
    private val path = Path()
    private val fillPath = Path()
    
    init {
        // 初始填充一些零数据
        repeat(maxPoints) { dataPoints.add(0f) }
    }
    
    fun addPoint(value: Float) {
        dataPoints.addLast(value)
        if (dataPoints.size > maxPoints) {
            dataPoints.removeFirst()
        }
        
        // 动态计算最大值（至少为 1，避免除零）
        val max = dataPoints.maxOrNull() ?: 1f
        // 平滑最大值变化 (简单的低通滤波)
        maxValue = if (max > maxValue) max else (maxValue * 0.95f + max * 0.05f)
        if (maxValue < 1f) maxValue = 1f
        
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (dataPoints.isEmpty()) return
        
        val width = width.toFloat()
        val height = height.toFloat()
        
        // 间距
        val stepX = width / (maxPoints - 1)
        
        path.reset()
        fillPath.reset()
        
        fillPath.moveTo(0f, height)
        
        dataPoints.forEachIndexed { index, value ->
            val x = index * stepX
            // Y 轴反转 (0 在底部)
            val y = height - (value / maxValue * height)
            
            if (index == 0) {
                path.moveTo(x, y)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        
        fillPath.lineTo(width, height)
        fillPath.close()
        
        // 绘制
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)
    }
    
    fun setLineColor(color: Int) {
        linePaint.color = color
        fillPaint.color = Color.argb(50, Color.red(color), Color.green(color), Color.blue(color))
        invalidate()
    }
}
