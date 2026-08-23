package com.example.tfgwj.ui.compose

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.tfgwj.domain.model.TaskPhase
import com.example.tfgwj.ui.mvi.ReplacingViewModel

/**
 * V11.0.0 任务进行中的全屏 Overlay 组件
 * V13 收口：运行态只保留取消；成功/失败/取消终态均可关闭，失败可重试。
 */
@Composable
fun TaskProgressOverlay(
    viewModel: ReplacingViewModel,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    val isTerminal =
        uiState.phase == TaskPhase.COMPLETED ||
            uiState.phase == TaskPhase.FAILURE ||
            uiState.phase == TaskPhase.CANCELLED

    if (uiState.isReplacing || isTerminal) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background.copy(alpha = 0.95f),
        ) {
            LazyColumn(
                modifier =
                    Modifier
                        .padding(24.dp)
                        .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Text(
                        text =
                            when (uiState.phase) {
                                TaskPhase.COMPLETED -> "任务已完成"
                                TaskPhase.FAILURE -> "任务失败"
                                TaskPhase.CANCELLED -> "任务已取消"
                                else -> "任务处理中"
                            },
                        style = MaterialTheme.typography.headlineMedium,
                        color =
                            when (uiState.phase) {
                                TaskPhase.COMPLETED -> MaterialTheme.colorScheme.tertiary
                                TaskPhase.FAILURE -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            },
                    )
                }

                item {
                    FileProgressCard(
                        fileName = uiState.currentFileName,
                        processedCount = uiState.processedFiles,
                        totalCount = uiState.totalFiles,
                        progress = uiState.progress / 100f,
                        speedMBps = uiState.speedMBps,
                        phase = uiState.phase,
                        isReplacing = uiState.isReplacing,
                        onCancel = onCancel,
                    )
                }

                if (!isTerminal) {
                    item {
                        ApmDashboardCard(
                            ioWaitMs = uiState.ioWaitMs,
                            ipcLatencyMs = uiState.ipcLatencyMs,
                            memoryUsagePercent = uiState.memoryUsagePercent,
                        )
                    }
                }

                when (uiState.phase) {
                    TaskPhase.FAILURE -> {
                        if (uiState.errorMessage != null) {
                            item {
                                ErrorMessageCard(message = uiState.errorMessage!!)
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = onRetry) {
                                    Text("重试")
                                }
                                OutlinedButton(onClick = onDismiss) {
                                    Text("关闭")
                                }
                            }
                        }
                    }
                    TaskPhase.COMPLETED -> {
                        item {
                            TerminalSummaryCard(
                                title = "替换成功",
                                detail = "共处理 ${uiState.processedFiles}/${uiState.totalFiles} 个文件",
                                isError = false,
                            )
                        }
                        item {
                            OutlinedButton(onClick = onDismiss) {
                                Text("关闭")
                            }
                        }
                    }
                    TaskPhase.CANCELLED -> {
                        item {
                            TerminalSummaryCard(
                                title = "任务已取消",
                                detail = "已处理 ${uiState.processedFiles}/${uiState.totalFiles} 个文件",
                                isError = true,
                            )
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = onRetry) {
                                    Text("重试")
                                }
                                OutlinedButton(onClick = onDismiss) {
                                    Text("关闭")
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun TerminalSummaryCard(
    title: String,
    detail: String,
    isError: Boolean,
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isError) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    },
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color =
                    if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    },
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (isError) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    },
            )
        }
    }
}

@Composable
fun ErrorMessageCard(message: String) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "错误",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
