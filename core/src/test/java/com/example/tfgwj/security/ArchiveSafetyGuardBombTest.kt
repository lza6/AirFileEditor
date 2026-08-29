package com.example.tfgwj.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * ArchiveSafetyGuard 压缩炸弹检测测试 (V20)
 */
class ArchiveSafetyGuardBombTest {

    @Test
    fun `rejects archive with too many entries`() {
        // 构造超过 MAX_ENTRY_COUNT 的条目列表（模拟 10 万+ 条目的 Zip Bomb）
        val entries = (1L..ArchiveSafetyGuard.MAX_ENTRY_COUNT + 1).map { ArchiveEntryMetadata("f$it.bin", 1024L) }

        val error = assertThrows(IllegalArgumentException::class.java) {
            ArchiveSafetyGuard.validateBomb(entries, archiveBytes = 1_000_000L)
        }

        assertEquals("压缩包条目数超过限制: ${ArchiveSafetyGuard.MAX_ENTRY_COUNT + 1}", error.message)
    }

    @Test
    fun `rejects high compression ratio bomb`() {
        // 512MB 解压总量 / 4MB 压缩包 = 128x > 100x 阈值
        val entries = listOf(
            ArchiveEntryMetadata("big.bin", ArchiveSafetyGuard.SUSPICIOUS_TOTAL_BYTES),
        )
        val smallArchive = 4L * 1024 * 1024 // 4MB

        val error = assertThrows(IllegalArgumentException::class.java) {
            ArchiveSafetyGuard.validateBomb(entries, archiveBytes = smallArchive)
        }

        assertEquals("压缩包压缩率异常 (128.0x)，疑似压缩炸弹，已拒绝", error.message)
    }

    @Test
    fun `accepts normal archive with reasonable ratio`() {
        val entries = listOf(
            ArchiveEntryMetadata("a.txt", 1024L),
            ArchiveEntryMetadata("b.txt", 2048L),
        )

        // 无异常
        ArchiveSafetyGuard.validateBomb(entries, archiveBytes = 1024 * 1024L)
    }

    @Test
    fun `accepts small total size regardless of ratio`() {
        // 解压总量 < 512MB 门槛，即使压缩率很高也不误判（小文件场景）
        val entries = listOf(
            ArchiveEntryMetadata("tiny.dat", 100L * 1024L * 1024L), // 100MB
        )
        val tinyArchive = 1024L // 1KB

        ArchiveSafetyGuard.validateBomb(entries, archiveBytes = tinyArchive)
    }

    @Test
    fun `rejects zero archive size`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ArchiveSafetyGuard.validateBomb(emptyList(), archiveBytes = 0L)
        }

        assertEquals("压缩包大小为 0，无法判定压缩率", error.message)
    }

    @Test
    fun `rejects negative total size overflow`() {
        val entries = listOf(
            ArchiveEntryMetadata("first.bin", ArchiveSafetyGuard.MAX_ENTRY_SIZE_BYTES),
            ArchiveEntryMetadata("second.bin", ArchiveSafetyGuard.MAX_ENTRY_SIZE_BYTES),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ArchiveSafetyGuard.validateBomb(entries, archiveBytes = 1024L)
        }
    }
}
