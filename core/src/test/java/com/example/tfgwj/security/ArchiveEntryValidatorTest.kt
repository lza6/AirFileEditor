package com.example.tfgwj.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ArchiveEntryValidator 单元测试
 *
 * 覆盖解压安全红线的核心契约：
 * - isSafeEntryName：拒绝 `..`、绝对路径、盘符、NUL、空段、`.`/空
 * - resolveWithin：规范化解析并保证不越界
 * - isWithinDestination：canonical path + 分隔符边界（防前缀欺骗）
 */
class ArchiveEntryValidatorTest {

    private val tmpDir: File = Files.createTempDirectory("validator").toFile()

    // ---- isSafeEntryName ----

    @Test
    fun `safe relative entry passes`() {
        assertTrue(ArchiveEntryValidator.isSafeEntryName("data/file.txt"))
    }

    @Test
    fun `safe nested entry passes`() {
        assertTrue(ArchiveEntryValidator.isSafeEntryName("a/b/c/d.png"))
    }

    @Test
    fun `parent traversal entry rejected`() {
        assertFalse(ArchiveEntryValidator.isSafeEntryName("../evil.txt"))
    }

    @Test
    fun `absolute path entry rejected`() {
        assertFalse(ArchiveEntryValidator.isSafeEntryName("/etc/passwd"))
    }

    @Test
    fun `windows drive prefix entry rejected`() {
        assertFalse(ArchiveEntryValidator.isSafeEntryName("C:/windows/evil.dll"))
    }

    @Test
    fun `nul char entry rejected`() {
        assertFalse(ArchiveEntryValidator.isSafeEntryName("evil\u0000.txt"))
    }

    @Test
    fun `empty segment entry rejected`() {
        assertFalse(ArchiveEntryValidator.isSafeEntryName("a//b.txt"))
    }

    @Test
    fun `blank entry rejected`() {
        assertFalse(ArchiveEntryValidator.isSafeEntryName("   "))
    }

    // ---- isWithinDestination ----

    @Test
    fun `target inside destination is within`() {
        val root = tmpDir
        val child = File(root, "data/file.txt")
        assertTrue(ArchiveEntryValidator.isWithinDestination(root, child))
    }

    @Test
    fun `sibling with shared prefix is not within`() {
        val root = File(tmpDir, "target")
        val sibling = File(tmpDir, "target-escape")
        // 前缀相同但不在同一目录内 — 必须判定为越界
        assertTrue(!ArchiveEntryValidator.isWithinDestination(root, sibling))
    }

    // ---- resolveWithin ----

    @Test
    fun `resolveWithin maps safe entry inside destination`() {
        val resolved = ArchiveEntryValidator.resolveWithin(tmpDir, "data/file.txt")
        assertEquals(File(tmpDir, "data/file.txt").canonicalFile, resolved)
    }

    @Test
    fun `resolveWithin throws on traversal entry`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArchiveEntryValidator.resolveWithin(tmpDir, "../outside.txt")
        }
    }
}
