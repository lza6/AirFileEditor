package com.example.tfgwj.ui.components.molecules

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 统一 Snackbar 管理器
 * 替代散布在各处的 Toast.makeText() 调用
 * V15: 替换所有硬编码 Toast
 */
object SnackbarManager {
    sealed class SnackbarEvent(
        val message: String,
        val actionLabel: String? = null,
        val isError: Boolean = false,
    ) {
        class Show(message: String, actionLabel: String? = null) : SnackbarEvent(message, actionLabel)

        class Error(message: String, actionLabel: String? = null) : SnackbarEvent(message, actionLabel, isError = true)

        class Success(message: String) : SnackbarEvent(message)
    }

    private val _events = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 10)
    val events: SharedFlow<SnackbarEvent> = _events.asSharedFlow()

    fun show(
        message: String,
        actionLabel: String? = null,
    ) {
        _events.tryEmit(SnackbarEvent.Show(message, actionLabel))
    }

    fun showError(
        message: String,
        actionLabel: String? = null,
    ) {
        _events.tryEmit(SnackbarEvent.Error(message, actionLabel))
    }

    fun showSuccess(message: String) {
        _events.tryEmit(SnackbarEvent.Success(message))
    }
}

@Composable
fun SnackbarHostWrapper(snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }) {
    LaunchedEffect(Unit) {
        SnackbarManager.events.collect { event ->
            when (event) {
                is SnackbarManager.SnackbarEvent.Show -> {
                    snackbarHostState.showSnackbar(
                        message = event.message,
                        duration = SnackbarDuration.Short,
                    )
                }
                is SnackbarManager.SnackbarEvent.Error -> {
                    snackbarHostState.showSnackbar(
                        message = "⚠ ${event.message}",
                        duration = SnackbarDuration.Long,
                    )
                }
                is SnackbarManager.SnackbarEvent.Success -> {
                    snackbarHostState.showSnackbar(
                        message = "✓ ${event.message}",
                        duration = SnackbarDuration.Short,
                    )
                }
            }
        }
    }
}
