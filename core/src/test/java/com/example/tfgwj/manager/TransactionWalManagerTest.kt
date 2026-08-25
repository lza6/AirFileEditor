package com.example.tfgwj.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class TransactionWalManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var walManager: TransactionWalManager
    private lateinit var targetFile: File
    private lateinit var backupFile: File

    @Before
    fun setup() {
        walManager = TransactionWalManager(tempFolder.root)
        targetFile = tempFolder.newFile("target_original.txt")
        targetFile.writeText("Original Content")
        backupFile = tempFolder.newFile("backup_original.txt")
        backupFile.writeText("Original Content")
    }

    @Test
    fun `recordAction and rollback restores overwritten file`() {
        // 模拟覆盖操作
        targetFile.writeText("Corrupted Content")
        walManager.recordAction(
            action = "OVERWRITE",
            targetPath = targetFile.absolutePath,
            backupPath = backupFile.absolutePath,
            originalMd5 = null
        )

        assertEquals(1, walManager.getPendingEntriesCount())

        val success = walManager.rollback()
        assertTrue(success)
        assertEquals("Original Content", targetFile.readText())
        assertEquals(0, walManager.getPendingEntriesCount())
    }

    @Test
    fun `rollback deletes created file`() {
        val newFile = File(tempFolder.root, "new_created.txt")
        newFile.writeText("Temporary Data")

        walManager.recordAction(
            action = "CREATE",
            targetPath = newFile.absolutePath,
            backupPath = null,
            originalMd5 = null
        )

        val success = walManager.rollback()
        assertTrue(success)
        assertFalse(newFile.exists())
    }

    // ==================== 追加：WAL 核心行为测试 ====================

    @Test
    fun `recordAction creates a wal file with the entry`() {
        walManager.recordAction(
            action = "OVERWRITE",
            targetPath = targetFile.absolutePath,
            backupPath = backupFile.absolutePath,
            originalMd5 = null
        )

        val walFiles = workDirWalFiles()
        assertEquals(1, walFiles.size)
        assertTrue(walFiles.single().isFile)
    }

    @Test
    fun `rollback removes wal file and clears entries on success`() {
        walManager.recordAction(
            action = "OVERWRITE",
            targetPath = targetFile.absolutePath,
            backupPath = backupFile.absolutePath,
            originalMd5 = null
        )
        val walFilesBefore = workDirWalFiles()
        assertEquals(1, walFilesBefore.size)

        val success = walManager.rollback()

        assertTrue(success)
        assertTrue(workDirWalFiles().isEmpty())
        assertEquals(0, walManager.getPendingEntriesCount())
    }

    @Test
    fun `rollback of overwrite without backup does not throw and succeeds`() {
        // 备份缺失（例如备份文件被外部删除）：OVERWRITE 不应抛异常，回滚应成功
        targetFile.writeText("Modified Content")
        walManager.recordAction(
            action = "OVERWRITE",
            targetPath = targetFile.absolutePath,
            backupPath = File(tempFolder.root, "missing_backup.bin").absolutePath,
            originalMd5 = null
        )

        val success = walManager.rollback()
        assertTrue(success)
        // 备份不存在时不恢复内容，但目标不被破坏
        assertEquals("Modified Content", targetFile.readText())
        assertEquals(0, walManager.getPendingEntriesCount())
    }

    @Test
    fun `rollback of create when target already deleted succeeds`() {
        // 目标已被删除：CREATE 回滚（delete）不应抛异常
        val newFile = File(tempFolder.root, "already_deleted.txt")
        newFile.writeText("Data")
        walManager.recordAction(
            action = "CREATE",
            targetPath = newFile.absolutePath,
            backupPath = null,
            originalMd5 = null
        )
        assertTrue(newFile.delete())

        val success = walManager.rollback()
        assertTrue(success)
        assertFalse(newFile.exists())
    }

    @Test
    fun `rollback of empty wal is a successful no-op`() {
        // 没有任何记录时回滚也应成功且无副作用
        val success = walManager.rollback()
        assertTrue(success)
        assertEquals(0, walManager.getPendingEntriesCount())
    }

    @Test
    fun `rollback restores multiple mixed entries in reverse order`() {
        // CREATE + OVERWRITE + DELETE 混合，逆序回滚且全部成功
        val createdFile = File(tempFolder.root, "multi_created.txt")
        createdFile.writeText("New Data")

        targetFile.writeText("Corrupted Content")

        val deleteTarget = File(tempFolder.root, "multi_deleted.txt")
        deleteTarget.writeText("Deleted Backup")
        val deleteBackup = File(tempFolder.root, "multi_deleted_backup.txt")
        deleteBackup.writeText("Delete Rescue")

        walManager.recordAction("CREATE", createdFile.absolutePath, null, null)
        walManager.recordAction("OVERWRITE", targetFile.absolutePath, backupFile.absolutePath, null)
        walManager.recordAction("DELETE", deleteTarget.absolutePath, deleteBackup.absolutePath, null)

        assertEquals(3, walManager.getPendingEntriesCount())
        assertTrue(deleteTarget.delete())

        val success = walManager.rollback()
        assertTrue(success)
        assertFalse(createdFile.exists()) // 逆序：CREATE 最后回滚 -> 删除
        assertEquals("Original Content", targetFile.readText()) // 逆序：OVERWRITE 恢复备份
        assertEquals("Delete Rescue", deleteTarget.readText()) // 逆序：DELETE 还原备份
        assertEquals(0, walManager.getPendingEntriesCount())
    }

    @Test
    fun `getPendingEntriesCount reflects recorded actions`() {
        assertEquals(0, walManager.getPendingEntriesCount())

        walManager.recordAction("CREATE", File(tempFolder.root, "a.txt").absolutePath, null, null)
        assertEquals(1, walManager.getPendingEntriesCount())

        walManager.recordAction("OVERWRITE", targetFile.absolutePath, backupFile.absolutePath, null)
        assertEquals(2, walManager.getPendingEntriesCount())

        walManager.recordAction("DELETE", File(tempFolder.root, "b.txt").absolutePath, null, null)
        assertEquals(3, walManager.getPendingEntriesCount())

        walManager.rollback()
        assertEquals(0, walManager.getPendingEntriesCount())
    }

    @Test
    fun `wal file does not exist before any action is recorded`() {
        assertTrue(workDirWalFiles().isEmpty())
    }

    @Test
    fun `multiple record actions update the same wal file`() {
        walManager.recordAction("CREATE", File(tempFolder.root, "a.txt").absolutePath, null, null)
        walManager.recordAction("OVERWRITE", targetFile.absolutePath, backupFile.absolutePath, null)
        walManager.recordAction("DELETE", File(tempFolder.root, "b.txt").absolutePath, null, null)

        // 每次 recordAction 都重建同一份 wal 文件，而不是先生成临时文件再改名
        assertEquals(1, workDirWalFiles().size)
    }

    /**
     * 返回 workDir 下所有 WAL 日志文件（以 .tfgwj_wal_ 前缀开头）
     */
    private fun workDirWalFiles(): List<File> {
        val files = tempFolder.root.listFiles()
        return files?.filter { it.isFile && it.name.startsWith(".tfgwj_wal_") } ?: emptyList()
    }
}