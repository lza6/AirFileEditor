package com.example.tfgwj.data

import com.example.tfgwj.data.repository.HistoryMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V19 审计记录映射真实单测（直接调 HistoryMapping.buildHistoryItem，杜绝 harness 副本伪验证）
 */
class HistoryMappingTest {

    @Test
    fun `buildHistoryItem success maps verified count and no errors`() {
        val item = HistoryMapping.buildHistoryItem(
            sourcePath = "/storage/emulated/0/主包",
            targetPackage = "com.example.app",
            now = 1_700_000_000_000L,
            succeeded = true,
            successCount = 42,
            totalFiles = 42,
            failedFiles = -1,
            backupPath = "/backup/app.zip",
            errorMessage = null,
        )
        assertEquals("com.example.app", item.packageName)
        assertEquals(42, item.successCount)
        assertEquals(0, item.failedCount)
        assertEquals(42, item.totalFiles)
        assertTrue(item.errors.isEmpty())
        assertEquals("/backup/app.zip", item.backupPath)
    }

    @Test
    fun `buildHistoryItem failure uses failedFiles count when available`() {
        val item = HistoryMapping.buildHistoryItem(
            sourcePath = "/storage/emulated/0/主包",
            targetPackage = "com.example.app",
            now = 1_700_000_000_000L,
            succeeded = false,
            successCount = 10,
            totalFiles = 50,
            failedFiles = 30,
            backupPath = null,
            errorMessage = "替换失败: 磁盘空间不足",
        )
        // 失败时 successCount 恒 0（Worker 失败路径的 processed 不可信）
        assertEquals(0, item.successCount)
        assertEquals(30, item.failedCount)
        assertEquals(50, item.totalFiles)
        assertTrue(item.errors.any { it.contains("磁盘空间不足") })
    }

    @Test
    fun `buildHistoryItem failure without failedFiles falls back to 1`() {
        val item = HistoryMapping.buildHistoryItem(
            sourcePath = "/s",
            targetPackage = "com.a.b",
            now = 0L,
            succeeded = false,
            successCount = 0,
            totalFiles = 0,
            failedFiles = -1,
            backupPath = null,
            errorMessage = null,
        )
        assertEquals(0, item.successCount)
        assertEquals(1, item.failedCount)
        assertTrue(item.errors.any { it.contains("任务未成功完成") })
    }
}
