package com.example.tfgwj.manager

import com.example.tfgwj.security.ArchiveEntryMetadata
import com.example.tfgwj.security.ArchiveSafetyGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ArchiveSafetyGuard 规模与条目安全契约测试（补充场景）
 *
 * 覆盖解压安全红线的其余边界：
 * - 单文件/总大小上限
 * - 大数溢出防护
 * - staging 目录创建与清理
 */
class ArchiveSafetyGuardScaleTest {

    @Test
    fun `addBytesWithinLimit accumulates correctly under limit`() {
        val total = ArchiveSafetyGuard.addBytesWithinLimit(100L, 50L)
        assertEquals(150L, total)
    }

    @Test
    fun `addBytesWithinLimit rejects negative input`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveSafetyGuard.addBytesWithinLimit(-1L, 1L)
        }
    }

    @Test
    fun `addBytesWithinLimit rejects overflow beyond max total`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveSafetyGuard.addBytesWithinLimit(ArchiveSafetyGuard.MAX_TOTAL_SIZE_BYTES, 1L)
        }
    }

    @Test
    fun `validateEntry rejects traversal name`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveSafetyGuard.validateEntry("../evil.txt")
        }
    }

    @Test
    fun `validateEntry rejects oversized entry`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveSafetyGuard.validateEntry("big.bin", ArchiveSafetyGuard.MAX_ENTRY_SIZE_BYTES + 1)
        }
    }

    @Test
    fun `validateEntries accepts safe unique entries`() {
        ArchiveSafetyGuard.validateEntries(
            listOf(
                ArchiveEntryMetadata("a.txt", 1L),
                ArchiveEntryMetadata("b/c.txt", 2L),
            ),
        )
        assertTrue(true)
    }

    @Test
    fun `newStagingDirectory creates sibling staging folder`() {
        val parent = java.nio.file.Files.createTempDirectory("safety_parent").toFile()
        val finalDir = File(parent, "out")
        val staging = ArchiveSafetyGuard.newStagingDirectory(finalDir)
        try {
            assertTrue(staging.exists())
            assertTrue(staging.absolutePath.contains("__extracting_"))
            assertEquals(parent.absolutePath, staging.parentFile.absolutePath)
        } finally {
            ArchiveSafetyGuard.discardDirectory(parent)
        }
    }
}
