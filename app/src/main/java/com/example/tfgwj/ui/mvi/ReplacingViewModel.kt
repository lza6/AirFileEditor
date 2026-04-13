package com.example.tfgwj.ui.mvi

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tfgwj.manager.ReplaceProgressManager
import com.example.tfgwj.performance.MetricCollector
import com.example.tfgwj.performance.MetricCategory
import com.example.tfgwj.performance.MetricNames
import com.example.tfgwj.performance.PerformanceMonitor
import com.example.tfgwj.utils.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * V11.0.0 完整应用 ViewModel (MVI 核心)
 * 整合 MainActivity 所有业务逻辑
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
                    phase = when (progress.phase) {
                        "IDLE" -> TaskPhase.IDLE
                        "PREPARING" -> TaskPhase.PREPARING
                        "REPLACING" -> TaskPhase.REPLACING
                        "VERIFYING" -> TaskPhase.VERIFYING
                        "COMPLETED" -> TaskPhase.COMPLETED
                        "FAILURE" -> TaskPhase.FAILURE
                        else -> TaskPhase.IDLE
                    },
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
            // 替换任务控制
            is ReplacingIntent.StartReplace -> {
                // 由外部通过 WorkManager 触发，ViewModel 仅同步状态
                _uiState.update { it.copy(
                    isReplacing = true,
                    phase = TaskPhase.PREPARING,
                    errorMessage = null
                )}
            }
            is ReplacingIntent.CancelReplace -> {
                _uiState.update { it.copy(
                    isReplacing = false,
                    phase = TaskPhase.IDLE
                )}
            }
            is ReplacingIntent.RetryReplace -> {
                _uiState.update { it.copy(
                    errorMessage = null,
                    phase = TaskPhase.IDLE
                )}
            }
            is ReplacingIntent.PauseReplace -> {
                _uiState.update { it.copy(isPaused = true) }
            }
            is ReplacingIntent.ResumeReplace -> {
                _uiState.update { it.copy(isPaused = false) }
            }

            // 权限与模式
            is ReplacingIntent.UpdateMode -> {
                _uiState.update { it.copy(currentMode = intent.mode) }
            }
            is ReplacingIntent.RefreshEnvironment -> {
                _uiState.update { it.copy(environmentStatus = EnvironmentStatus.Checking) }
                // 实际检测逻辑由外部调用
            }
            is ReplacingIntent.RequestStoragePermission -> {
                // 由 Activity 处理权限请求
            }
            is ReplacingIntent.RequestShizukuPermission -> {
                // 由 Activity 处理 Shizuku 权限
            }

            // 主包管理
            is ReplacingIntent.SelectMainPack -> {
                _uiState.update { it.copy(selectedMainPackPath = intent.path) }
            }
            is ReplacingIntent.ScanMainPacks -> {
                _uiState.update { it.copy(isScanning = true) }
            }
            is ReplacingIntent.CheckEnvironment -> {
                _uiState.update { it.copy(environmentStatus = EnvironmentStatus.Checking) }
            }
            is ReplacingIntent.LaunchGame -> {
                // 由 Activity 处理应用启动
            }
            is ReplacingIntent.CleanEnvironment -> {
                // 由 Activity 处理环境清理
            }

            // 文件时间管理
            is ReplacingIntent.RandomizeFileTime -> {
                val randomTime = System.currentTimeMillis() - (Math.random() * 365 * 24 * 60 * 60 * 1000).toLong()
                _uiState.update { it.copy(currentFileTime = randomTime) }
            }
            is ReplacingIntent.LockFileTime -> {
                _uiState.update { it.copy(lockedTime = intent.timestamp) }
            }
            is ReplacingIntent.UnlockFileTime -> {
                _uiState.update { it.copy(lockedTime = null) }
            }
            is ReplacingIntent.ApplyLockedTime -> {
                // 由外部处理文件时间应用
            }

            // 小包管理
            is ReplacingIntent.ScanArchives -> {
                _uiState.update { it.copy(isScanning = true) }
            }
            is ReplacingIntent.RefreshPatches -> {
                _uiState.update { it.copy(isScanning = true) }
            }
            is ReplacingIntent.SelectPatch -> {
                _uiState.update { it.copy(selectedPatchVersion = intent.version) }
            }
            is ReplacingIntent.ExtractAndUpdate -> {
                // 由外部处理解压更新
            }

            // 日志操作
            is ReplacingIntent.CopyLogs -> {
                // 由 Activity 处理剪贴板操作
            }
            is ReplacingIntent.ClearLogs -> {
                _uiState.update { it.copy(logContent = "", logSize = "0 KB") }
            }

            // OTA 更新
            is ReplacingIntent.CheckForUpdates -> {
                // 由外部处理更新检测
            }
            is ReplacingIntent.InstallUpdate -> {
                // 由 Activity 处理 APK 安装
            }
        }
    }

    // 更新权限状态
    fun updatePermissions(hasStorage: Boolean, hasShizuku: Boolean) {
        _uiState.update { it.copy(
            hasStoragePermission = hasStorage,
            hasShizukuPermission = hasShizuku
        )}
    }

    // 更新环境状态
    fun updateEnvironmentStatus(status: EnvironmentStatus) {
        _uiState.update { it.copy(environmentStatus = status) }
    }

    // 更新主包信息
    fun updateMainPackInfo(path: String?, appName: String?, icon: Any?, targetPackage: String) {
        _uiState.update { it.copy(
            selectedMainPackPath = path,
            mainPackAppName = appName,
            mainPackIcon = icon,
            targetPackage = targetPackage
        )}
    }

    // 更新小包列表
    fun updatePatchVersions(patches: List<PatchVersion>) {
        _uiState.update { it.copy(
            patchVersions = patches,
            isScanning = false
        )}
    }

    // 更新日志内容
    fun updateLogContent(content: String, size: String) {
        _uiState.update { it.copy(
            logContent = content,
            logSize = size
        )}
    }

    // 更新 OTA 状态
    fun updateOtaStatus(hasUpdate: Boolean, version: String?, progress: Int = 0) {
        _uiState.update { it.copy(
            hasUpdate = hasUpdate,
            updateVersion = version,
            updateDownloadProgress = progress
        )}
    }

    // 设置错误消息
    fun setError(message: String) {
        _uiState.update { it.copy(
            errorMessage = message,
            phase = TaskPhase.FAILURE,
            isReplacing = false
        )}
    }

    // 清除错误
    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
