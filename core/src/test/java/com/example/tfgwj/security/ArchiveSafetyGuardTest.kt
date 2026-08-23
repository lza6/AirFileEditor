package com.example.tfgwj.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ArchiveSafetyGuardTest {

    @Test
    fun `rejects duplicate normalized archive entries`() {
        val entries = listOf(
            ArchiveEntryMetadata("dir/file.txt", 1L),
            ArchiveEntryMetadata("dir\\file.txt", 1L),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            ArchiveSafetyGuard.validateEntries(entries)
        }

        assertEquals("重复压缩包条目: dir\\file.txt", error.message)
    }

    @Test
    fun `rejects entry larger than single entry limit`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ArchiveSafetyGuard.validateEntries(
                listOf(ArchiveEntryMetadata("large.bin", ArchiveSafetyGuard.MAX_ENTRY_SIZE_BYTES + 1)),
            )
        }

        assertEquals("压缩包单文件超过大小限制: large.bin", error.message)
    }

    @Test
    fun `rejects archive larger than total extraction limit`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ArchiveSafetyGuard.validateEntries(
                listOf(
                    ArchiveEntryMetadata("first.bin", ArchiveSafetyGuard.MAX_ENTRY_SIZE_BYTES),
                    ArchiveEntryMetadata("second.bin", ArchiveSafetyGuard.MAX_ENTRY_SIZE_BYTES),
                    ArchiveEntryMetadata("third.bin", ArchiveSafetyGuard.MAX_ENTRY_SIZE_BYTES),
                    ArchiveEntryMetadata("fourth.bin", ArchiveSafetyGuard.MAX_ENTRY_SIZE_BYTES),
                    ArchiveEntryMetadata("fifth.bin", 1L),
                ),
            )
        }

        assertEquals("压缩包总解压大小超过限制", error.message)
    }

    @Test
    fun `streaming byte accounting rejects overflow and accepts safe chunks`() {
        assertEquals(10L, ArchiveSafetyGuard.addBytesWithinLimit(0L, 10L))

        val error = assertThrows(IllegalArgumentException::class.java) {
            ArchiveSafetyGuard.addBytesWithinLimit(
                ArchiveSafetyGuard.MAX_TOTAL_SIZE_BYTES,
                1L,
            )
        }

        assertEquals("压缩包总解压大小超过限制", error.message)
    }

    @Test
    fun `failed staging directory is discarded without keeping leftover files`() {
        val root = java.nio.file.Files.createTempDirectory("archive-staging").toFile()
        try {
            val finalDir = java.io.File(root, "out")
            val staging = ArchiveSafetyGuard.newStagingDirectory(finalDir)
            java.io.File(staging, "partial.bin").writeText("partial")

            ArchiveSafetyGuard.discardDirectory(staging)

            assertEquals(false, staging.exists())
            assertEquals(false, finalDir.exists())
            val leftovers = root.listFiles()?.filter { it.name.contains("__extracting_") }.orEmpty()
            org.junit.Assert.assertTrue(leftovers.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
