package com.example.tfgwj.ui.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tfgwj.manager.ReplaceProgressManager
import com.example.tfgwj.performance.MetricCollector
import com.example.tfgwj.performance.MetricCategory
import com.example.tfgwj.performance.MetricNames
import com.example.tfgwj.performance.PerformanceMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * V11.0.0 Replacing 模块 ViewModel (MVI 核心)
 * 整合 V8 Orchestrator 进度与 V10 APM 指标
 */
class ReplacingViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ReplacingState())
    val uiState: StateFlow<ReplacingState> = _uiState.asStateFlow()

    init {
        // 1. 订阅任务进度流 (V8 架构)
        viewModelScope.launch {
            ReplaceProgressManager.progressState.collect { progress ->
                _uiState.update { it.copy(
                    processedFiles = progress.processed,
                    totalFiles = progress.total,
                    progress = progress.progress,
                    speedMBps = progress.speed,
                    currentFileName = progress.currentFile,
                    phase = progress.phase,
                    isReplacing = progress.isReplacing
                )}
            }
        }

        // 2. 订阅 APM 实时指标流 (V10 架构)
        viewModelScope.launch {
            MetricCollector.getMetricFlow().collect { metric ->
                when (metric.name) {
                    MetricNames.IO_WRITE_LATENCY -> {
                        _uiState.update { it.copy(ioWaitMs = metric.value.toLong()) }
                    }
                    MetricNames.IPC_BINDER_LATENCY -> {
                        _uiState.update { it.copy(ipcLatencyMs = metric.value.toLong()) }
                    }
                    MetricNames.MEMORY_USAGE_PERCENT -> {
                        _uiState.update { it.copy(memoryUsagePercent = metric.value) }
                    }
                }
            }
        }
    }

    fun handleIntent(intent: ReplacingIntent) {
        when (intent) {
            is ReplacingIntent.StartReplace -> {
                // 由外部通过 WorkManager 触发，ViewModel 仅同步状态
            }
            is ReplacingIntent.CancelReplace -> {
                // 触发取消逻辑 (待实装封装)
            }
            is ReplacingIntent.RetryReplace -> {
                // 重试逻辑
            }
            is ReplacingIntent.UpdateMode -> {
                _uiState.update { it.copy(currentMode = intent.mode) }
            }
            is ReplacingIntent.RefreshEnvironment -> {
                // 刷新环境状态
            }
        }
    }
}
