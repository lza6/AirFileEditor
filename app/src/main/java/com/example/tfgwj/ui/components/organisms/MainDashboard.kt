package com.example.tfgwj.ui.components.organisms

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tfgwj.performance.PerformanceMonitor
import com.example.tfgwj.ui.components.atoms.IoSpeedText
import com.example.tfgwj.ui.components.atoms.StatusBadge
import com.example.tfgwj.ui.components.atoms.TaskStatus

/**
 * 首页核心性能看板
 */
@Composable
fun MainDashboard(
    ioStats: PerformanceMonitor.IOStats,
    currentStatus: TaskStatus,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.05f)
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "系统性能看板",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                StatusBadge(status = currentStatus)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 核心指标行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem(
                    label = "平均速度",
                    value = { IoSpeedText(speedMBps = ioStats.avgSpeedMBps.toFloat()) }
                )

                StatItem(
                    label = "已处理文件",
                    value = {
                        Text(
                            text = ioStats.totalFilesCopied.toString(),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                )

                StatItem(
                    label = "命中率",
                    value = {
                        Text(
                            text = "${String.format("%.1f", ioStats.incrementalHitRate)}%",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                )
            }

            if (ioStats.mmapFallbackRate > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = (ioStats.mmapFallbackRate / 100f).toFloat(),
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                )
                Text(
                    text = "mmap 回退率: ${String.format("%.1f", ioStats.mmapFallbackRate)}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        value()
    }
}
