@file:Suppress("WildcardImport")

package com.example.tfgwj.ui.mvi

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.tfgwj.data.ReplaceHistoryManager
import com.example.tfgwj.domain.repository.ReplaceHistoryItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

data class HistoryUiState(
    val items: List<ReplaceHistoryItem> = emptyList(),
    val isLoading: Boolean = true,
    val selectedItem: ReplaceHistoryItem? = null,
    val error: String? = null,
)

class HistoryViewModel(
    private val historyManager: ReplaceHistoryManager,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                historyManager.history.collect { items ->
                    val domainItems =
                        items.map { item ->
                            ReplaceHistoryItem(
                                timestamp = item.timestamp,
                                packageName = item.packageName,
                                sourcePath = item.sourcePath,
                                targetPath = item.targetPath,
                                totalFiles = item.totalFiles,
                                successCount = item.successCount,
                                failedCount = item.failedCount,
                                errors = item.errors,
                                backupPath = item.backupPath,
                            )
                        }
                    _uiState.update { it.copy(items = domainItems.reversed(), isLoading = false, error = null) }
                }
            } catch (e: IOException) {
                _uiState.update { it.copy(isLoading = false, error = "加载历史记录失败: ${e.message}") }
            }
        }
    }

    fun selectItem(item: ReplaceHistoryItem) {
        _uiState.update { it.copy(selectedItem = item) }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedItem = null) }
    }

    fun deleteItem(timestamp: Long) {
        viewModelScope.launch {
            try {
                historyManager.deleteHistory(timestamp)
                _uiState.update { it.copy(selectedItem = null) }
            } catch (e: IOException) {
                _uiState.update { it.copy(error = "删除失败: ${e.message}") }
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            try {
                historyManager.clearHistory()
                _uiState.update { it.copy(items = emptyList(), selectedItem = null) }
            } catch (e: IOException) {
                _uiState.update { it.copy(error = "清空失败: ${e.message}") }
            }
        }
    }
}

class HistoryViewModelFactory(private val app: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return HistoryViewModel(ReplaceHistoryManager.getInstance(app)) as T
    }
}
