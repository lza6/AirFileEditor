package com.example.tfgwj.ui

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.tfgwj.performance.MetricCollector
import com.example.tfgwj.performance.PerformanceMonitor

/**
 * 性能监控 Dashboard（Debug Only）
 *
 * 功能：
 * 1. 实时显示 IO 统计信息
 * 2. 显示内存使用情况
 * 3. 显示诊断报告
 * 4. 显示采集器统计
 *
 * @version V10.0.0 - Performance Monitoring
 */
class PerformanceDashboardActivity : AppCompatActivity() {
    private lateinit var tvStats: TextView
    private lateinit var tvMemory: TextView
    private lateinit var tvDiagnostics: TextView
    private lateinit var tvCollector: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 创建简单的布局
        val scrollView =
            ScrollView(this).apply {
                setPadding(32, 32, 32, 32)
            }

        val linearLayout =
            android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }

        // 标题
        val tvTitle =
            TextView(this).apply {
                text = "📊 性能监控 Dashboard"
                textSize = 24f
                setPadding(0, 0, 0, 32)
            }
        linearLayout.addView(tvTitle)

        // IO 统计
        val tvIOLabel =
            TextView(this).apply {
                text = "📁 IO 统计"
                textSize = 18f
                setPadding(0, 16, 0, 8)
            }
        linearLayout.addView(tvIOLabel)

        tvStats =
            TextView(this).apply {
                textSize = 14f
                setPadding(16, 0, 0, 16)
            }
        linearLayout.addView(tvStats)

        // 内存统计
        val tvMemoryLabel =
            TextView(this).apply {
                text = "💾 内存统计"
                textSize = 18f
                setPadding(0, 16, 0, 8)
            }
        linearLayout.addView(tvMemoryLabel)

        tvMemory =
            TextView(this).apply {
                textSize = 14f
                setPadding(16, 0, 0, 16)
            }
        linearLayout.addView(tvMemory)

        // 诊断报告
        val tvDiagLabel =
            TextView(this).apply {
                text = "🔍 诊断报告"
                textSize = 18f
                setPadding(0, 16, 0, 8)
            }
        linearLayout.addView(tvDiagLabel)

        tvDiagnostics =
            TextView(this).apply {
                textSize = 14f
                setPadding(16, 0, 0, 16)
            }
        linearLayout.addView(tvDiagnostics)

        // 采集器统计
        val tvCollectorLabel =
            TextView(this).apply {
                text = "📈 采集器统计"
                textSize = 18f
                setPadding(0, 16, 0, 8)
            }
        linearLayout.addView(tvCollectorLabel)

        tvCollector =
            TextView(this).apply {
                textSize = 14f
                setPadding(16, 0, 0, 16)
            }
        linearLayout.addView(tvCollector)

        scrollView.addView(linearLayout)
        setContentView(scrollView)

        // 刷新数据
        refreshStats()
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
    }

    private fun refreshStats() {
        // IO 统计
        val ioStats = PerformanceMonitor.getIOStats()
        tvStats.text =
            buildString {
                appendLine("总复制字节: ${formatBytes(ioStats.totalBytesCopied)}")
                appendLine("总复制时间: ${ioStats.totalCopyTimeMs}ms")
                appendLine("总复制文件: ${ioStats.totalFilesCopied}")
                appendLine("平均速度: ${String.format("%.2f", ioStats.avgSpeedMBps)} MB/s")
                appendLine("mmap 回退率: ${String.format("%.1f", ioStats.mmapFallbackRate)}%")
                appendLine("增量命中率: ${String.format("%.1f", ioStats.incrementalHitRate)}%")
            }

        // 内存统计
        val runtime = Runtime.getRuntime()
        val usedMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMemoryMB = runtime.maxMemory() / (1024 * 1024)
        val usagePercent = (usedMemoryMB.toDouble() / maxMemoryMB * 100)

        tvMemory.text =
            buildString {
                appendLine("已用内存: ${usedMemoryMB}MB")
                appendLine("最大内存: ${maxMemoryMB}MB")
                appendLine("使用率: ${String.format("%.1f", usagePercent)}%")
                appendLine("CPU 核心数: ${Runtime.getRuntime().availableProcessors()}")
            }

        // 诊断报告
        val report = PerformanceMonitor.getDiagnosticReport()
        tvDiagnostics.text =
            buildString {
                if (report.issues.isEmpty()) {
                    appendLine("✅ 未发现性能问题")
                } else {
                    report.issues.forEach { issue ->
                        val icon =
                            when (issue.severity) {
                                PerformanceMonitor.IssueSeverity.INFO -> "ℹ️"
                                PerformanceMonitor.IssueSeverity.WARNING -> "⚠️"
                                PerformanceMonitor.IssueSeverity.ERROR -> "❌"
                            }
                        appendLine("$icon [${issue.category}] ${issue.message}")
                        appendLine("   建议: ${issue.suggestion}")
                        appendLine()
                    }
                }
            }

        // 采集器统计
        val collectorStats = MetricCollector.getStats()
        tvCollector.text =
            buildString {
                appendLine("总采集数: ${collectorStats.totalCollected}")
                appendLine("总刷新数: ${collectorStats.totalFlushed}")
                appendLine("缓冲区大小: ${collectorStats.bufferSize}")
                appendLine("采集状态: ${if (collectorStats.isCollecting) "运行中" else "已暂停"}")
            }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> "${String.format("%.2f", bytes / (1024.0 * 1024 * 1024))}GB"
            bytes >= 1024 * 1024 -> "${String.format("%.2f", bytes / (1024.0 * 1024))}MB"
            bytes >= 1024 -> "${String.format("%.2f", bytes / 1024.0)}KB"
            else -> "${bytes}B"
        }
    }
}
