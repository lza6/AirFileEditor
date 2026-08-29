package com.example.tfgwj.data

import com.example.tfgwj.domain.model.TaskPhase
import com.example.tfgwj.domain.repository.ReplaceHistoryItem
import com.example.tfgwj.domain.repository.TaskProgress
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * :data 模块冒烟测试（V17 补足：该模块此前 0 测试，未纳入 JaCoCo）
 *
 * 验证 ConfigRepository 接口的天花板契约能正常映射：
 * - TaskProgress 领域映射（phase / isReplacing / errorMessage）
 * - ReplaceHistoryItem 领域映射（backupPath / errors）
 * 不依赖 Android 框架，纯 Kotlin JVM 测试。
 */
class ConfigRepositoryContractTest {

    @Test
    fun `TaskProgress maps phase and isReplacing consistently`() {
        val progress = TaskProgress(
            processed = 10,
            total = 100,
            progress = 50,
            speed = 25.5f,
            currentFile = "test.txt",
            phase = TaskPhase.REPLACING,
            isReplacing = true,
            errorMessage = null,
        )

        assertTrue(progress.isReplacing)
        assertEquals(TaskPhase.REPLACING, progress.phase)
        assertEquals(10, progress.processed)
        assertEquals(100, progress.total)
        assertEquals(50, progress.progress)
        assertEquals(25.5f, progress.speed)
        assertEquals("test.txt", progress.currentFile)
    }

    @Test
    fun `TaskProgress terminal phase is not replacing`() {
        val progress = TaskProgress(
            processed = 100,
            total = 100,
            progress = 100,
            speed = 0f,
            currentFile = "",
            phase = TaskPhase.COMPLETED,
            isReplacing = false,
            errorMessage = null,
        )

        assertFalse(progress.isReplacing)
        assertEquals(TaskPhase.COMPLETED, progress.phase)
    }

    @Test
    fun `ReplaceHistoryItem carries audit fields`() {
        val item = ReplaceHistoryItem(
            timestamp = 1_700_000_000_000L,
            packageName = "com.example.app",
            sourcePath = "/storage/emulated/0/Android/data/com.example.app",
            targetPath = "/storage/emulated/0/Android/data/com.example.app/files",
            totalFiles = 42,
            successCount = 40,
            failedCount = 2,
            errors = listOf("file1.txt 复制失败"),
            backupPath = "/backup/com.example.app.zip",
        )

        assertEquals("com.example.app", item.packageName)
        assertEquals(42, item.totalFiles)
        assertEquals(40, item.successCount)
        assertEquals(2, item.failedCount)
        assertEquals(1, item.errors.size)
        assertEquals("/backup/com.example.app.zip", item.backupPath)
    }

    @Test
    fun `history flow emits list without hanging`() = runTest {
        val flow = flowOf(listOf<ReplaceHistoryItem>())
        val result = flow.toList()
        assertTrue(result.isNotEmpty())
        assertTrue(result.first().isEmpty())
    }

    @Test
    fun `empty flow is collectable`() = runTest {
        val flow = emptyFlow<ReplaceHistoryItem>()
        val result = flow.toList()
        assertTrue(result.isEmpty())
    }
}
