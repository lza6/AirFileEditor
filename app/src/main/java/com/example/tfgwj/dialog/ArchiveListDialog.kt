package com.example.tfgwj.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tfgwj.R
import com.example.tfgwj.model.ArchiveFile
import com.example.tfgwj.databinding.DialogArchiveListBinding
import com.example.tfgwj.databinding.DialogPasswordInputBinding
import com.example.tfgwj.databinding.ItemArchiveBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ArchiveListDialog(
    context: Context,
    private val archiveFiles: List<ArchiveFile>,
    private val onArchiveSelected: (ArchiveFile, String?) -> Unit
) : MaterialAlertDialogBuilder(context) {

    private var dialog: Dialog? = null
    private var binding: DialogArchiveListBinding? = null
    private var adapter: ArchiveAdapter? = null
    private var useAutoPassword = false

    override fun create(): androidx.appcompat.app.AlertDialog {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_archive_list, null)
        binding = DialogArchiveListBinding.bind(view)

        // 设置标题
        setTitle(context.getString(R.string.archives_found, archiveFiles.size))

        // 初始化适配器
        adapter = ArchiveAdapter(archiveFiles) { archive ->
            if (useAutoPassword) {
                val password = archive.getSuggestedPassword()
                onArchiveSelected(archive, password)
            } else {
                // 显示密码输入对话框
                PasswordInputDialog(context, archive) { password ->
                    onArchiveSelected(archive, password)
                }.show()
            }
            dialog?.dismiss()
        }

        binding?.recyclerView?.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@ArchiveListDialog.adapter
        }

        // 自动密码按钮
        binding?.btnAutoPassword?.setOnClickListener {
            useAutoPassword = true
            Toast.makeText(context, R.string.auto_fill_password, Toast.LENGTH_SHORT).show()
        }

        // 取消按钮
        setNegativeButton(R.string.cancel) { _, _ ->
            dialog?.dismiss()
        }

        dialog = super.setView(view).create()
        return dialog as androidx.appcompat.app.AlertDialog
    }

    private class ArchiveAdapter(
        private val archives: List<ArchiveFile>,
        private val onItemClick: (ArchiveFile) -> Unit
    ) : RecyclerView.Adapter<ArchiveAdapter.ViewHolder>() {

        inner class ViewHolder(private val binding: ItemArchiveBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(archive: ArchiveFile) {
                binding.apply {
                    tvFileName.text = archive.fileName
                    tvFileSize.text = archive.getFileSizeFormatted()
                    tvFileType.text = archive.fileType.uppercase()
                    root.setOnClickListener {
                        onItemClick(archive)
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemArchiveBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(archives[position])
        }

        override fun getItemCount(): Int = archives.size
    }
}

class PasswordInputDialog(
    context: Context,
    private val archive: ArchiveFile,
    private val onPasswordEntered: (String?) -> Unit
) : MaterialAlertDialogBuilder(context) {

    private var binding: DialogPasswordInputBinding? = null
    private var dialog: androidx.appcompat.app.AlertDialog? = null

    override fun create(): androidx.appcompat.app.AlertDialog {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_password_input, null)
        binding = DialogPasswordInputBinding.bind(view)

        binding?.apply {
            etPassword.hint = context.getString(R.string.password_hint)
            tvFileName.text = archive.fileName
            tvSuggestedPassword.text = context.getString(
                R.string.use_filename_as_password,
                archive.getSuggestedPassword()
            )

            // 使用建议密码按钮 - 自动填充并自动确定
            btnUseSuggested.setOnClickListener {
                etPassword.setText(archive.getSuggestedPassword())
                dialog?.dismiss()
                onPasswordEntered(archive.getSuggestedPassword())
            }

            // 确认按钮
            setPositiveButton(R.string.ok) { _, _ ->
                val password = etPassword.text?.toString()
                onPasswordEntered(password)
            }

            // 取消按钮
            setNegativeButton(R.string.cancel) { _, _ ->
                onPasswordEntered(null)
            }
        }

        dialog = super.setView(view).create()
        return dialog!!
    }
}