package com.example.tfgwj.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tfgwj.data.PreferencesManager
import com.example.tfgwj.manager.*
import com.example.tfgwj.performance.PerformanceMonitor
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * MainViewModel - V11.0.0 Compose 状态管理
 *
 * 集中管理所有 UI 状态，替代 Activity 中的分散状态管理
 *
 * @version V11.0.0 - UI Modernization
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferencesManager = PreferencesManager(application)

    // ==================== UI 状态 ====================

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _replaceProgress = MutableStateFlow(ReplaceProgressState())
    val replaceProgress: StateFlow<ReplaceProgressState> = _replaceProgress.asStateFlow()

    private val _logMessages = MutableStateFlow<List<String>>(emptyList())
    val logMessages: StateFlow<List<String>> = _logMessages.asStateFlow()

    // ==================== 初始化 ====================

    init {
        loadPreferences()
        observeProgress()
    }

    private fun loadPreferences() {
        viewModelScope.launch {
            preferencesManager.appPackageName.collect { packageName ->
                _uiState.update { it.copy(selectedPackageName = packageName) }
            }
        }
        viewModelScope.launch {
            preferencesManager.lastMainPackPath.collect { path ->
                _uiState.update { it.copy(selectedMainPackPath = path ?: "") }
            }
        }
        viewModelScope.launch {
            preferencesManager.lockedTimeEnabled.collect { enabled ->
                _uiState.update { it.copy(lockedTimeEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            preferencesManager.lockedTime.collect { time ->
                _uiState.update { it.copy(lockedTime = time ?: 0) }
            }
        }
    }

    private fun observeProgress() {
        viewModelScope.launch {
            ReplaceProgressManager.progressState.collect { state ->
                _replaceProgress.value =
                    ReplaceProgressState(
                        progress = state.progress,
                        processed = state.processed,
                        total = state.total,
                        currentFile = state.currentFile,
                        speed = state.speed,
                        phase = state.phase,
                        isReplacing = state.isReplacing,
                    )
            }
        }
    }

    // ==================== 用户操作 ====================

    fun selectMainPack(path: String) {
        _uiState.update { it.copy(selectedMainPackPath = path) }
        viewModelScope.launch {
            preferencesManager.saveLastMainPackPath(path)
        }
    }

    fun selectPackage(packageName: String) {
        _uiState.update { it.copy(selectedPackageName = packageName) }
        viewModelScope.launch {
            preferencesManager.setAppPackageName(packageName)
        }
    }

    fun selectMode(mode: String) {
        _uiState.update { it.copy(selectedMode = mode) }
    }

    fun toggleLockedTime() {
        val newValue = !_uiState.value.lockedTimeEnabled
        _uiState.update { it.copy(lockedTimeEnabled = newValue) }
        viewModelScope.launch {
            if (newValue) {
                preferencesManager.lockTime(_uiState.value.lockedTime)
            } else {
                preferencesManager.unlockTime()
            }
        }
    }

    fun setLockedTime(time: Long) {
        _uiState.update { it.copy(lockedTime = time, lockedTimeEnabled = true) }
        viewModelScope.launch {
            preferencesManager.lockTime(time)
        }
    }

    fun toggleAutoCleanCache() {
        val newValue = !_uiState.value.autoCleanCache
        _uiState.update { it.copy(autoCleanCache = newValue) }
    }

    fun addLogMessage(message: String) {
        _logMessages.update { currentMessages ->
            val newMessages = currentMessages + message
            // 保持最近 100 条日志
            if (newMessages.size > 100) newMessages.takeLast(100) else newMessages
        }
    }

    fun clearLogs() {
        _logMessages.value = emptyList()
    }

    fun setShowHelpDialog(show: Boolean) {
        _uiState.update { it.copy(showHelpDialog = show) }
    }

    fun setShowModeSelectionDialog(show: Boolean) {
        _uiState.update { it.copy(showModeSelectionDialog = show) }
    }

    fun setShowArchiveListDialog(show: Boolean) {
        _uiState.update { it.copy(showArchiveListDialog = show) }
    }

    // ==================== 性能监控 ====================

    fun getPerformanceReport(): PerformanceMonitor.DiagnosticReport {
        return PerformanceMonitor.getDiagnosticReport()
    }

    fun getOptimizationRecommendations(): List<PerformanceMonitor.OptimizationRecommendation> {
        return PerformanceMonitor.getOptimizationRecommendations()
    }
}

/**
 * 主界面 UI 状态
 */
data class MainUiState(
    val selectedPackageName: String = "",
    val selectedMainPackPath: String = "",
    val selectedMode: String = "AUTO",
    val lockedTimeEnabled: Boolean = false,
    val lockedTime: Long = 0,
    val autoCleanCache: Boolean = true,
    val showHelpDialog: Boolean = false,
    val showModeSelectionDialog: Boolean = false,
    val showArchiveListDialog: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * 替换进度状态
 */
data class ReplaceProgressState(
    val progress: Int = 0,
    val processed: Int = 0,
    val total: Int = 0,
    val currentFile: String = "",
    val speed: Float = 0f,
    val phase: String = "IDLE",
    val isReplacing: Boolean = false,
)
