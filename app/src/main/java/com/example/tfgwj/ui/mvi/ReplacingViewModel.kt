package com.example.tfgwj.ui.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tfgwj.domain.model.AccessMode
import com.example.tfgwj.domain.model.EnvironmentStatus
import com.example.tfgwj.domain.model.TaskPhase
import com.example.tfgwj.domain.repository.ConfigRepository
import com.example.tfgwj.domain.usecase.*
import com.example.tfgwj.performance.MetricCollector
import com.example.tfgwj.performance.MetricNames
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * V12.0.0 架构升级版 ViewModel
 * 彻底实现 DDD + MVI，仅通过 UseCases 与业务逻辑交互
 */
class ReplacingViewModel(
    private val replaceFileUseCase: ReplaceFileUseCase,
    private val checkEnvironmentUseCase: CheckEnvironmentUseCase,
    private val managePatchUseCase: ManagePatchUseCase,
    private val manageFileTimeUseCase: ManageFileTimeUseCase,
    private val repository: ConfigRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReplacingState())
    val uiState: StateFlow<ReplacingState> = _uiState.asStateFlow()

    init {
        // 1. 订阅任务进度
        viewModelScope.launch {
            repository.getTaskProgress().collect { progress ->
                _uiState.update { it.copy(
                    processedFiles = progress.processed,
                    totalFiles = progress.total,
                    progress = progress.progress,
                    speedMBps = progress.speed,
                    currentFileName = progress.currentFile,
                    phase = TaskPhase.valueOf(progress.phase.name),
                    isReplacing = progress.isReplacing
                )}
            }
        }

        // 2. 订阅 APM 指标
        viewModelScope.launch {
            MetricCollector.getMetricFlow().collect { metric ->
                when (metric.name) {
                    MetricNames.IO_WRITE_LATENCY -> _uiState.update { it.copy(ioWaitMs = metric.value.toLong()) }
                    MetricNames.IPC_BINDER_LATENCY -> _uiState.update { it.copy(ipcLatencyMs = metric.value.toLong()) }
                    MetricNames.MEMORY_USAGE_PERCENT -> _uiState.update { it.copy(memoryUsagePercent = metric.value) }
                }
            }
        }
    }

    fun handleIntent(intent: ReplacingIntent) {
        when (intent) {
            is ReplacingIntent.StartReplace -> {
                viewModelScope.launch {
                    _uiState.update { it.copy(isReplacing = true, errorMessage = null) }
                    replaceFileUseCase(intent.sourcePath, intent.targetPackage)
                        .onFailure { setError(it.message ?: "替换任务启动失败") }
                }
            }
            is ReplacingIntent.CancelReplace -> {
                _uiState.update { it.copy(isReplacing = false, phase = TaskPhase.IDLE) }
            }
            is ReplacingIntent.RetryReplace -> {
                _uiState.update { it.copy(errorMessage = null, phase = TaskPhase.IDLE) }
            }
            is ReplacingIntent.PauseReplace -> {
                _uiState.update { it.copy(isPaused = true) }
            }
            is ReplacingIntent.ResumeReplace -> {
                _uiState.update { it.copy(isPaused = false) }
            }
            is ReplacingIntent.CheckEnvironment -> {
                viewModelScope.launch {
                    _uiState.update { it.copy(environmentStatus = EnvironmentStatus.CHECKING) }
                    val status = checkEnvironmentUseCase(_uiState.value.targetPackage, forceRefresh = true)
                    _uiState.update { it.copy(
                        environmentStatus = if (status.bestMode != com.example.tfgwj.domain.model.AccessMode.NONE) EnvironmentStatus.VALID else EnvironmentStatus.INVALID
                    )}
                }
            }
            is ReplacingIntent.RefreshPatches -> {
                viewModelScope.launch {
                    _uiState.update { it.copy(isScanning = true) }
                    val patches = managePatchUseCase.scanPatches().map {
                        com.example.tfgwj.ui.mvi.PatchVersion(it.version, it.path, it.size, it.fileCount)
                    }
                    _uiState.update { it.copy(patchVersions = patches, isScanning = false) }
                }
            }
            is ReplacingIntent.SelectPatch -> {
                _uiState.update { it.copy(selectedPatchVersion = intent.version) }
            }
            is ReplacingIntent.RandomizeFileTime -> {
                viewModelScope.launch {
                    val path = _uiState.value.selectedMainPackPath ?: return@launch
                    manageFileTimeUseCase.randomize(path).onSuccess { (_, time) ->
                        _uiState.update { it.copy(lockedTime = time, currentFileTime = time) }
                    }
                }
            }
            is ReplacingIntent.LockFileTime -> {
                _uiState.update { it.copy(lockedTime = intent.timestamp) }
            }
            is ReplacingIntent.UnlockFileTime -> {
                _uiState.update { it.copy(lockedTime = null) }
            }
            is ReplacingIntent.ClearLogs -> {
                _uiState.update { it.copy(logContent = "", logSize = "0 KB") }
            }
            else -> {}
        }
    }

    // 更新权限状态
    fun updatePermissions(hasStorage: Boolean, hasShizuku: Boolean) {
        _uiState.update { it.copy(hasStoragePermission = hasStorage, hasShizukuPermission = hasShizuku) }
    }

    // 更新主包信息
    fun updateMainPackInfo(path: String?, appName: String?, icon: Any?, targetPackage: String) {
        val time = path?.let { manageFileTimeUseCase.getCurrentTime(it) }
        _uiState.update { it.copy(
            selectedMainPackPath = path,
            mainPackAppName = appName,
            mainPackIcon = icon,
            targetPackage = targetPackage,
            currentFileTime = time
        )}
    }

    fun updateLogContent(content: String, size: String) {
        _uiState.update { it.copy(logContent = content, logSize = size) }
    }

    fun updateEnvironmentStatus(status: com.example.tfgwj.domain.model.EnvironmentStatus) {
        _uiState.update { it.copy(environmentStatus = status) }
    }

    fun updatePatchVersions(patches: List<PatchVersion>) {
        _uiState.update { it.copy(patchVersions = patches, isScanning = false) }
    }

    private fun setError(message: String) {
        _uiState.update { it.copy(errorMessage = message, isReplacing = false) }
    }
}
