package com.example.tfgwj.ui

import android.app.Dialog
import android.content.Context
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.example.tfgwj.R
import com.example.tfgwj.databinding.DialogModeSelectionBinding
import com.example.tfgwj.utils.PermissionChecker
import com.example.tfgwj.utils.PermissionManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 模式选择对话框
 */
object ModeSelectionDialog {

    interface Callback {
        fun onModeSelected(mode: PermissionChecker.AccessMode)
        fun onRequestShizukuPermission()
    }

    fun show(context: Context, permissionManager: PermissionManager, callback: Callback) {
        val binding = DialogModeSelectionBinding.inflate(LayoutInflater.from(context))
        val status = permissionManager.permissionStatus.value
        val androidVersion = Build.VERSION.SDK_INT

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("选择文件访问模式")
            .setView(binding.root)
            .setNegativeButton("取消", null)
            .create()

        // 设置各个模式的说明
        setupModeItem(
            binding.layoutModeRoot,
            binding.tvModeRootTitle,
            binding.tvModeRootDesc,
            PermissionChecker.AccessMode.ROOT,
            androidVersion,
            status.bestMode == PermissionChecker.AccessMode.ROOT
        )

        setupModeItem(
            binding.layoutModeShizuku,
            binding.tvModeShizukuTitle,
            binding.tvModeShizukuDesc,
            PermissionChecker.AccessMode.SHIZUKU,
            androidVersion,
            status.bestMode == PermissionChecker.AccessMode.SHIZUKU
        )

        setupModeItem(
            binding.layoutModeNative,
            binding.tvModeNativeTitle,
            binding.tvModeNativeDesc,
            PermissionChecker.AccessMode.NATIVE,
            androidVersion,
            status.bestMode == PermissionChecker.AccessMode.NATIVE
        )

        // 监听点击
        binding.layoutModeRoot.setOnClickListener {
            callback.onModeSelected(PermissionChecker.AccessMode.ROOT)
            dialog.dismiss()
        }

        binding.layoutModeShizuku.setOnClickListener {
            // 如果 Shizuku 未授权，先尝试请求权限
            if (status.isShizukuAvailable && !status.hasShizukuPermission) {
                callback.onRequestShizukuPermission()
            }
            callback.onModeSelected(PermissionChecker.AccessMode.SHIZUKU)
            dialog.dismiss()
        }

        binding.layoutModeNative.setOnClickListener {
            callback.onModeSelected(PermissionChecker.AccessMode.NATIVE)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun setupModeItem(
        layout: View,
        titleView: TextView,
        descView: TextView,
        mode: PermissionChecker.AccessMode,
        androidVersion: Int,
        isRecommended: Boolean
    ) {
        val (title, desc) = PermissionChecker.getModeDescription(mode, androidVersion)
        titleView.text = if (isRecommended) "★ $title (推荐)" else title
        descView.text = desc
        
        if (isRecommended) {
            titleView.setTextColor(titleView.context.getColor(R.color.primary_color))
        }
    }
}
