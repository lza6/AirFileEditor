package com.example.tfgwj.ui

import androidx.lifecycle.lifecycleScope
import com.example.tfgwj.MainActivity
import com.example.tfgwj.ui.mvi.ReplacingViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 悬浮球控制器 — 从 MainActivity 提取的悬浮球生命周期管理
 */
class FloatingBallController(
    private val activity: MainActivity,
    private val replacingViewModel: ReplacingViewModel,
    private val floatingBallManager: FloatingBallManager,
) {
    fun observeTaskState() {
        activity.lifecycleScope.launch {
            replacingViewModel.uiState.collectLatest { state ->
                if (state.totalFiles > 0 && state.isReplacing && !floatingBallManager.isShowing()) {
                    floatingBallManager.show()
                } else if (!state.isReplacing && floatingBallManager.isShowing()) {
                    floatingBallManager.hide()
                }
            }
        }
    }
}
