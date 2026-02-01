package com.example.tfgwj.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.tfgwj.R
import com.example.tfgwj.manager.PatchManager

/**
 * 小包版本列表适配器
 */
class PatchVersionAdapter(
    private val onItemClick: (PatchManager.PatchVersion) -> Unit,
    private val onDeleteClick: (PatchManager.PatchVersion) -> Unit
) : ListAdapter<PatchManager.PatchVersion, PatchVersionAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_patch_version, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tv_patch_name)
        private val tvSize: TextView = itemView.findViewById(R.id.tv_patch_size)
        private val tvFileCount: TextView = itemView.findViewById(R.id.tv_file_count)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btn_delete_patch)

        fun bind(item: PatchManager.PatchVersion) {
            tvName.text = item.name
            tvSize.text = item.sizeText
            tvFileCount.text = "${item.fileCount} 个文件"
            
            itemView.setOnClickListener { onItemClick(item) }
            btnDelete.setOnClickListener { onDeleteClick(item) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<PatchManager.PatchVersion>() {
        override fun areItemsTheSame(
            oldItem: PatchManager.PatchVersion,
            newItem: PatchManager.PatchVersion
        ): Boolean = oldItem.path == newItem.path

        override fun areContentsTheSame(
            oldItem: PatchManager.PatchVersion,
            newItem: PatchManager.PatchVersion
        ): Boolean = oldItem == newItem
    }
}
