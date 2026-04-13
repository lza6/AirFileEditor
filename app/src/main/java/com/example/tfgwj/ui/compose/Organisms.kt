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
 */
@Composable
fun TaskProgressOverlay(
    viewModel: ReplacingViewModel,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.isReplacing || uiState.phase != TaskPhase.IDLE) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background.copy(alpha = 0.95f)
        ) {
            LazyColumn(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        text = "任务处理中",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                item {
                    FileProgressCard(
                        fileName = uiState.currentFileName,
                        processedCount = uiState.processedFiles,
                        totalCount = uiState.totalFiles,
                        progress = uiState.progress / 100f,
                        speedMBps = uiState.speedMBps,
                        phase = uiState.phase.name,
                        isReplacing = uiState.isReplacing,
                        onCancel = onCancel
                    )
                }

                item {
                    ApmDashboardCard(
                        ioWaitMs = uiState.ioWaitMs,
                        ipcLatencyMs = uiState.ipcLatencyMs,
                        memoryUsagePercent = uiState.memoryUsagePercent
                    )
                }

                if (uiState.errorMessage != null) {
                    item {
                        ErrorMessageCard(message = uiState.errorMessage!!)
                    }
                }
            }
        }
    }
}

@Composable
fun ErrorMessageCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "错误",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}
