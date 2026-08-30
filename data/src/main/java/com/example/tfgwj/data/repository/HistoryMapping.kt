package com.example.tfgwj.data.repository

import com.example.tfgwj.data.ReplaceHistoryItem
import com.example.tfgwj.worker.orchestrator.PathConstants

/**
 * V19 审计记录映射（纯函数 object，可 JVM 单测）：
 * 把 Worker 终态输出映射为替换历史条目。
 *
 * - 成功：successCount = verified(优先)/processed；failedCount = 0
 * - 失败：successCount = 0；failedCount = failedFiles（缺失回退 1）；附 errorMessage
 *
 * 提为 object 而非实例方法，使单测直接调用真实逻辑（而非 harness 副本），杜绝伪验证。
 */
object HistoryMapping {
    fun buildHistoryItem(
        sourcePath: String,
        targetPackage: String,
        now: Long,
        succeeded: Boolean,
        successCount: Int,
        totalFiles: Int,
        failedFiles: Int,
        backupPath: String?,
        errorMessage: String?,
    ): ReplaceHistoryItem {
        return ReplaceHistoryItem(
            timestamp = now,
            packageName = targetPackage,
            sourcePath = sourcePath,
            targetPath = PathConstants.buildTargetDataPath(targetPackage),
            totalFiles = totalFiles,
            successCount = if (succeeded) successCount else 0,
            failedCount = if (succeeded) 0 else if (failedFiles >= 0) failedFiles else 1,
            errors = if (succeeded) emptyList() else listOfNotNull(errorMessage ?: "任务未成功完成", "WorkState=非成功"),
            backupPath = backupPath,
        )
    }
}
