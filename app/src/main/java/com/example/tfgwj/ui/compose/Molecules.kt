package com.example.tfgwj.ui.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tfgwj.domain.model.TaskPhase
import com.example.tfgwj.ui.components.atoms.IoSpeedText
import com.example.tfgwj.ui.components.atoms.StatusBadge

/**
 * V11.0.0 文件替换进度卡片
 */
@Composable
fun FileProgressCard(
    fileName: String,
    processedCount: Int,
    totalCount: Int,
    progress: Float,
    speedMBps: Float,
    phase: TaskPhase,
    isReplacing: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isReplacing) "正在替换..." else "准备中",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    StatusBadge(text = phase.name)
                }

                if (isReplacing) {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "取消")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = fileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            val animatedProgress by animateFloatAsState(
                targetValue = progress,
                label = "ProgressAnimation",
            )
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(8.dp),
                strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$processedCount / $totalCount",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IoSpeedText(speedMBps = speedMBps)
            }
        }
    }
}

/**
 * V11.0.0 APM 实时指标仪表盘卡片
 */
@Composable
fun ApmDashboardCard(
    ioWaitMs: Long,
    ipcLatencyMs: Long,
    memoryUsagePercent: Double,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "APM 实时监控",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                MetricItem(
                    label = "IO 等待",
                    value = "${ioWaitMs}ms",
                    modifier = Modifier.weight(1f),
                    isWarning = ioWaitMs > 100,
                )
                MetricItem(
                    label = "IPC 延迟",
                    value = "${ipcLatencyMs}ms",
                    modifier = Modifier.weight(1f),
                    isWarning = ipcLatencyMs > 50,
                )
                MetricItem(
                    label = "内存占用",
                    value = "${String.format("%.1f", memoryUsagePercent)}%",
                    modifier = Modifier.weight(1f),
                    isWarning = memoryUsagePercent > 80,
                )
            }
        }
    }
}

@Composable
private fun MetricItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    isWarning: Boolean = false,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style =
                MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                ),
        )
    }
}
