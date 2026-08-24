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
}
