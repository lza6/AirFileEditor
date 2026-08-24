@file:Suppress("WildcardImport")

package com.example.tfgwj.ui.components.organisms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.tfgwj.performance.PerformanceMonitor
import com.example.tfgwj.ui.components.atoms.CardSkeleton
import com.example.tfgwj.ui.components.atoms.IoSpeedText
import com.example.tfgwj.ui.components.atoms.StatusBadge
import com.example.tfgwj.ui.components.atoms.TaskStatus

/**
 * 首页核心性能看板
 */
@Composable
@Suppress("LongMethod")
fun MainDashboard(
    ioStats: PerformanceMonitor.IOStats,
    currentStatus: TaskStatus,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier,
    onHistoryClick: () -> Unit = {},
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.05f),
            ),
    ) {
        if (isLoading) {
            CardSkeleton()
        } else {
            Column(
                modifier = Modifier.padding(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "系统性能看板",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    StatusBadge(status = currentStatus)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 核心指标行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatItem(
                        label = "平均速度",
                        value = { IoSpeedText(speedMBps = ioStats.avgSpeedMBps.toFloat()) },
                    )

                    StatItem(
                        label = "已处理文件",
                        value = {
                            Text(
                                text = ioStats.totalFilesCopied.toString(),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        },
                    )

                    StatItem(
                        label = "命中率",
                        value = {
                            Text(
                                text = "${String.format("%.1f", ioStats.incrementalHitRate)}%",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        },
                    )
                }

                if (ioStats.mmapFallbackRate > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = (ioStats.mmapFallbackRate / 100f).toFloat(),
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                    )
                    Text(
                        text = "mmap 回退率: ${String.format("%.1f", ioStats.mmapFallbackRate)}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onHistoryClick,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("查看替换历史")
                }
            }
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        value()
    }
}
