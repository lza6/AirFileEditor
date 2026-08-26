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
import com.example.tfgwj.utils.AppLogger
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
    private val repository: ConfigRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ReplacingState())
    val uiState: StateFlow<ReplacingState> = _uiState.asStateFlow()
    private var lastReplaceIntent: ReplacingIntent.StartReplace? = null

    companion object {
        /** 日志控制台最多显示的行数（上限，防止 logContent 无限增长） */
        const val MAX_LOG_LINES = 200

        /** 首屏预填充的历史日志条数 */
        private const val INITIAL_LOG_COUNT = 50
    }

    init {
        // 1. 订阅任务进度 — 直接使用 TaskPhase 枚举，消除字符串转换
        viewModelScope.launch {
            repository.getTaskProgress().collect { progress ->
                _uiState.update {
                    it.copy(
                        processedFiles = progress.processed,
                        totalFiles = progress.total,
                        progress = progress.progress,
                        speedMBps = progress.speed,
                        currentFileName = progress.currentFile,
                        phase = progress.phase,
                        errorMessage = progress.errorMessage,
                    )
                }
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

        // 3. 订阅日志事件流（替代 startLogUpdates 每秒轮询）— V16 修复：接入真实日志推送
        // 先灌入历史日志补足首屏，再增量订阅新日志
        val initialLogs = AppLogger.getRecentLogs(INITIAL_LOG_COUNT)
        if (initialLogs.isNotEmpty()) {
            _uiState.update { it.copy(logContent = initialLogs.joinToString("\n"), logSize = AppLogger.getLogSize()) }
        }
        viewModelScope.launch {
            AppLogger.logFlow.collect { logLine ->
                _uiState.update {
                    it.copy(
                        logContent = it.logContent.appendLogLine(logLine),
                        logSize = AppLogger.getLogSize(),
                    )
                }
            }
        }
    }

    /**
     * 追加单行日志，仅保留最近 [MAX_LOG_LINES] 行，避免 logContent 无限增长
     */
    private fun String.appendLogLine(newLine: String): String {
        val updated = if (this.isEmpty()) newLine else "$this\n$newLine"
        val lines = updated.split("\n")
        return if (lines.size > MAX_LOG_LINES) lines.takeLast(MAX_LOG_LINES).joinToString("\n") else updated
    }

    fun handleIntent(intent: ReplacingIntent) {
        when (intent) {
            is ReplacingIntent.StartReplace -> {
                if (_uiState.value.isReplacing) return
                lastReplaceIntent = intent
                launchReplace(intent)
            }
            is ReplacingIntent.CancelReplace -> {
                viewModelScope.launch {
                    repository.cancelReplace()
                        .onSuccess {
                            _uiState.update {
                                it.copy(isPaused = false, phase = TaskPhase.CANCELLED, errorMessage = null)
                            }
                        }
                        .onFailure { setError(it.message ?: "取消任务失败") }
                }
            }
            is ReplacingIntent.RetryReplace -> {
                val previousIntent = lastReplaceIntent
                if (previousIntent == null) {
                    setError("没有可重试的替换任务")
                } else {
                    launchReplace(previousIntent)
                }
            }
            is ReplacingIntent.DismissTaskResult -> {
                viewModelScope.launch {
                    repository.dismissReplaceResult()
                        .onSuccess {
                            _uiState.update {
                                it.copy(isPaused = false, phase = TaskPhase.IDLE, errorMessage = null)
                            }
                        }
                        .onFailure { setError(it.message ?: "清理任务状态失败") }
                }
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
                    val isEnvValid = status.bestMode != com.example.tfgwj.domain.model.AccessMode.NONE
                    _uiState.update {
                        it.copy(
                            environmentStatus = if (isEnvValid) EnvironmentStatus.VALID else EnvironmentStatus.INVALID,
                        )
                    }
                }
            }
            is ReplacingIntent.RefreshPatches -> {
                viewModelScope.launch {
                    _uiState.update { it.copy(isScanning = true) }
                    val patches =
                        managePatchUseCase.scanPatches().map {
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
    fun updatePermissions(
        hasStorage: Boolean,
        hasShizuku: Boolean,
    ) {
        _uiState.update { it.copy(hasStoragePermission = hasStorage, hasShizukuPermission = hasShizuku) }
    }

    // 更新主包信息（targetPackage 为空表示"未选择应用"，禁止静默兜底）
    fun updateMainPackInfo(
        path: String?,
        appName: String?,
        icon: Any?,
        targetPackage: String,
    ) {
        val time = path?.let { manageFileTimeUseCase.getCurrentTime(it) }
        _uiState.update {
            it.copy(
                selectedMainPackPath = path,
                mainPackAppName = appName,
                mainPackIcon = icon,
                targetPackage = targetPackage,
                currentFileTime = time,
            )
        }
    }

    fun updateLogContent(
        content: String,
        size: String,
    ) {
        _uiState.update { it.copy(logContent = content, logSize = size) }
    }

    fun updateEnvironmentStatus(status: com.example.tfgwj.domain.model.EnvironmentStatus) {
        _uiState.update { it.copy(environmentStatus = status) }
    }

    fun updatePatchVersions(patches: List<PatchVersion>) {
        _uiState.update { it.copy(patchVersions = patches, isScanning = false) }
    }

    private fun setError(message: String) {
        _uiState.update { it.copy(errorMessage = message, phase = TaskPhase.FAILURE) }
    }

    private fun launchReplace(intent: ReplacingIntent.StartReplace) {
        viewModelScope.launch {
            _uiState.update { it.copy(isPaused = false, phase = TaskPhase.PREPARING, errorMessage = null) }
            replaceFileUseCase(intent.sourcePath, intent.targetPackage)
                .onFailure { setError(it.message ?: "替换任务启动失败") }
        }
    }
}
