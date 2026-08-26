package com.example.tfgwj.manager

import android.util.Log
import com.example.tfgwj.performance.IoEngine
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * UniversalExtractor 解压安全红线测试
 *
 * 覆盖 P1-04 的核心契约：解压器在写入前必须经 ArchiveEntryValidator 校验，
 * 含 `..` 路径穿越的压缩包必须 fail-closed（返回失败且不留任何越界写入）。
 */
class UniversalExtractorSecurityTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        mockkObject(IoEngine)
        every { IoEngine.bufferManager.getCurrentBufferSize() } returns 8192
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `zip with parent traversal entry fails closed and writes nothing outside`() = runTest {
        val tmp = java.nio.file.Files.createTempDirectory("uev").toFile()
        try {
            // 构造含 ../ 路径穿越条目的恶意 zip（ZipOutputStream 不规范化条目名）
            val evilZip = File(tmp, "evil.zip")
            ZipOutputStream(evilZip.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry("../escaped.txt"))
                zos.write("boom".toByteArray())
                zos.closeEntry()
            }

            val outDir = File(tmp, "out")
            val result = UniversalExtractor.getInstance().extract(evilZip.absolutePath, outDir.absolutePath)

            // 必须 fail-closed
            assertFalse("恶意压缩包必须解压失败", result.success)
            // 不得在目标目录之外留下任何写入
            assertTrue("不得写入越界文件", !File(tmp, "escaped.txt").exists())
        } finally {
            ArchiveCleanupHelper.deleteRecursively(tmp)
        }
    }

    @Test
    fun `zip with safe legitimate entries extracts successfully`() = runTest {
        val tmp = java.nio.file.Files.createTempDirectory("uev").toFile()
        try {
            // 构造合法 zip
            val goodZip = File(tmp, "good.zip")
            ZipOutputStream(goodZip.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry("data/info.txt"))
                zos.write("hello".toByteArray())
                zos.closeEntry()
            }

            val outDir = File(tmp, "out")
            val result = UniversalExtractor.getInstance().extract(goodZip.absolutePath, outDir.absolutePath)

            assertTrue("合法压缩包应解压成功, 实际: $result", result.success)
            val written = File(outDir, "data/info.txt")
            assertTrue("合法文件应写入, 实际: $written", written.exists())
        } finally {
            ArchiveCleanupHelper.deleteRecursively(tmp)
        }
    }
}

/** 测试用目录清理工具（避免依赖生产代码的删除逻辑） */
private object ArchiveCleanupHelper {
    fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }
}