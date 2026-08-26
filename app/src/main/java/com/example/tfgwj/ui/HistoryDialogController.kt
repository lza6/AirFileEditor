package com.example.tfgwj.ui

import android.view.LayoutInflater
import androidx.activity.viewModels
import com.example.tfgwj.MainActivity
import com.example.tfgwj.databinding.DialogHistoryContainerBinding
import com.example.tfgwj.ui.components.organisms.HistoryScreen
import com.example.tfgwj.ui.mvi.HistoryViewModel
import com.example.tfgwj.ui.mvi.HistoryViewModelFactory
import com.example.tfgwj.ui.theme.TfgwjTheme
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 历史记录对话框控制器 — 从 MainActivity 提取的历史弹窗逻辑
 */
class HistoryDialogController(private val activity: MainActivity) {
    fun show() {
        val historyViewModel: HistoryViewModel by activity.viewModels {
            HistoryViewModelFactory(activity.application)
        }
        val dialog = MaterialAlertDialogBuilder(activity).setCancelable(true).create()

        val binding = DialogHistoryContainerBinding.inflate(LayoutInflater.from(activity))
        dialog.setContentView(binding.root)

        binding.composeViewHistory.setContent {
            TfgwjTheme {
                HistoryScreen(viewModel = historyViewModel, onDismiss = { dialog.dismiss() })
            }
        }

        dialog.show()
    }
}
