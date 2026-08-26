package com.example.tfgwj.ui

import androidx.lifecycle.lifecycleScope
import com.example.tfgwj.MainActivity
import com.example.tfgwj.manager.RuleEngine
import com.example.tfgwj.ui.mvi.ReplacingViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 主生命周期协调器 — 从 MainActivity 提取的 onCreate 启动任务序列
 */
class MainLifecycleCoordinator(
    private val activity: MainActivity,
    private val replacingViewModel: ReplacingViewModel,
) {
    fun launchStartupTasks() {
        activity.lifecycleScope.launch {
            activity.loadAppIcon()
            activity.loadWechatIcon()
            activity.loadLastMainPackPath()
            delay(ICON_LOAD_DELAY_MS)
            if (replacingViewModel.uiState.value.selectedMainPackPath == null) activity.loadMainPacks()
            activity.loadPatchVersions()
            delay(PATCH_LOAD_DELAY_MS)
            activity.checkEnvironment()
            delay(UPDATE_CHECK_DELAY_MS)
            activity.checkForUpdates()
            RuleEngine.fetchCloudRules()
        }
    }

    private companion object {
        const val ICON_LOAD_DELAY_MS = 300L
        const val PATCH_LOAD_DELAY_MS = 1000L
        const val UPDATE_CHECK_DELAY_MS = 2000L
    }
}
