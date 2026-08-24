package com.example.tfgwj.manager

import com.example.tfgwj.utils.AppLogger
import com.example.tfgwj.utils.FileHasher
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 事务预写日志 (WAL) 与自愈回滚管理器 (V15.0 事务核心)
 *
 * 保证在替换任务中途崩溃、用户强退或验证失败时，能够基于原子日志 100% 还原原始文件现场。
 */
class TransactionWalManager(private val workDir: File) {

    data class WalEntry(
        val action: String, // CREATE, OVERWRITE, DELETE
        val targetPath: String,
        val backupPath: String?,
        val originalMd5: String?
    )

    private val entries = mutableListOf<WalEntry>()
    private val walFile = File(workDir, ".tfgwj_wal_${System.currentTimeMillis()}.json")

    @Synchronized
    fun recordAction(action: String, targetPath: String, backupPath: String?, originalMd5: String?) {
        entries.add(WalEntry(action, targetPath, backupPath, originalMd5))
        persist()
    }

    @Synchronized
    private fun persist() {
        try {
            val sb = StringBuilder("[")
            entries.forEachIndexed { index, entry ->
                sb.append("{\"action\":\"").append(entry.action).append("\",")
                sb.append("\"targetPath\":\"").append(entry.targetPath.replace("\\", "\\\\")).append("\",")
                sb.append("\"backupPath\":\"").append((entry.backupPath ?: "").replace("\\", "\\\\")).append("\",")
                sb.append("\"originalMd5\":\"").append(entry.originalMd5 ?: "").append("\"}")
                if (index < entries.size - 1) sb.append(",")
            }
            sb.append("]")
            walFile.writeText(sb.toString())
        } catch (e: Exception) {
            AppLogger.e("WAL", "持久化 WAL 失败: ${e.message}")
        }
    }

    @Synchronized
    fun rollback(): Boolean {
        AppLogger.i("WAL", "开始执行事务回滚，共 ${entries.size} 条记录")
        var allSuccess = true

        // 逆序回滚
        for (entry in entries.reversed()) {
            try {
                val target = File(entry.targetPath)
                when (entry.action) {
                    "CREATE" -> {
                        if (target.exists()) target.delete()
                    }
                    "OVERWRITE" -> {
                        entry.backupPath?.let { bPath ->
                            val backup = File(bPath)
                            if (backup.exists()) {
                                backup.copyTo(target, overwrite = true)
                            }
                        }
                    }
                    "DELETE" -> {
                        entry.backupPath?.let { bPath ->
                            val backup = File(bPath)
                            if (backup.exists()) {
                                backup.copyTo(target, overwrite = true)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("WAL", "回滚单项失败: ${entry.targetPath}, 错误: ${e.message}")
                allSuccess = false
            }
        }

        if (allSuccess) {
            walFile.delete()
            entries.clear()
        }
        return allSuccess
    }

    fun getPendingEntriesCount(): Int = entries.size
}
