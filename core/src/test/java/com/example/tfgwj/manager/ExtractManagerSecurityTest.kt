package com.example.tfgwj.manager

import android.util.Log
import com.example.tfgwj.performance.IoEngine
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ExtractManager 解压安全红线测试
 *
 * 覆盖"先完整校验再写入、fail-closed 不留半成品"的核心契约：
 * - 合法条目全量写入目标且内容一致
 * - 含 `..` 路径穿越条目被拒绝且不写入
 * - 绝对路径条目被拒绝
 * - 重复条目（含反斜杠归一化后重复）被拒绝
 * - RAR / 损坏压缩包 fail-closed，staging 目录被清理
 *
 * 限制说明：ExtractManager.extractToCache 使用硬编码的 PermissionChecker.CACHE_DIR
 * （/storage/emulated/0/听风改文件），该路径在纯 JVM 测试中不可写。因此本测试统一走
 * 可参数化输出目录的 extractToMainPack；extractToCache 的目录判定逻辑不属于本文件范围。
 */
class ExtractManagerSecurityTest {

    private lateinit var manager: ExtractManager

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

        manager = ExtractManager.getInstance()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ---------- 测试构造辅助 ----------

    private fun writeZip(file: File, entries: List<Pair<String, ByteArray>>) {
        assertTrue("临时 zip 创建失败: ${file.path}", file.parentFile!!.exists() || file.parentFile!!.mkdirs())
        ZipOutputStream(file.outputStream()).use { zos ->
            entries.forEach { (name, data) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
    }

    private fun assertNoStagingLeftover(root: File) {
        val leftovers = root.listFiles()?.filter { it.name.contains("__extracting_") }.orEmpty()
        assertTrue("不应残留 staging 目录, 实际: ${leftovers.map { it.name }}", leftovers.isEmpty())
    }

    private fun assertDirEmpty(dir: File) {
        val files = dir.listFiles().orEmpty()
        assertTrue("目标目录应为空, 实际: ${files.map { it.name }}", files.isEmpty())
    }

    // ---------- 测试用例 ----------

    @Test
    fun `合法 zip 条目全量写入目标并保持内容一致`() = runTest {
        val tmp = java.nio.file.Files.createTempDirectory("em-legal").toFile()
        try {
            val zip = File(tmp, "good.zip")
            writeZip(
                zip,
                listOf(
                    "data/info.txt" to "hello".toByteArray(),
                    "data/sub/other.bin" to byteArrayOf(1, 2, 3, 4, 5),
                ),
            )

            val outDir = File(tmp, "out")
            val result = manager.extractToMainPack(zip.absolutePath, outDir.absolutePath)

            assertTrue("合法压缩包应解压成功, 实际: $result", result.success)
            assertEquals(2, result.extractedCount)

            val info = File(outDir, "data/info.txt")
            val other = File(outDir, "data/sub/other.bin")
            assertTrue("合法文件 data/info.txt 应写入", info.exists())
            assertTrue("合法文件 data/sub/other.bin 应写入", other.exists())
            assertEquals("hello", info.readText())
            assertTrue("字节内容应一致", other.readBytes().contentEquals(byteArrayOf(1, 2, 3, 4, 5)))

            assertNoStagingLeftover(tmp)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `含两个点路径穿越条目被拒绝且不写入`() = runTest {
        val tmp = java.nio.file.Files.createTempDirectory("em-traverse").toFile()
        try {
            // ZipOutputStream 不会规范化条目名，`../` 原样写入。
            val evilZip = File(tmp, "evil.zip")
            writeZip(evilZip, listOf("../escaped.txt" to "boom".toByteArray()))

            val outDir = File(tmp, "out")
            val result = manager.extractToMainPack(evilZip.absolutePath, outDir.absolutePath)

            // 必须 fail-closed
            assertFalse("恶意压缩包必须解压失败, 实际: $result", result.success)

            // 不得在目标目录之外留下任何写入
            assertTrue("不得写入越界文件", !File(tmp, "escaped.txt").exists())
            // 目标目录不得留下半成品
            assertDirEmpty(outDir)
            assertNoStagingLeftover(tmp)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `绝对路径条目被拒绝且不写入`() = runTest {
        val tmp = java.nio.file.Files.createTempDirectory("em-abs").toFile()
        try {
            val evilZip = File(tmp, "evil.zip")
            writeZip(evilZip, listOf("/etc/passwd" to "oops".toByteArray()))

            val outDir = File(tmp, "out")
            val result = manager.extractToMainPack(evilZip.absolutePath, outDir.absolutePath)

            assertFalse("绝对路径条目必须解压失败, 实际: $result", result.success)
            assertDirEmpty(outDir)
            assertNoStagingLeftover(tmp)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `重复条目被拒绝且不写入`() = runTest {
        val tmp = java.nio.file.Files.createTempDirectory("em-dup").toFile()
        try {
            // app/file.txt 与 app\\file.txt 归一化后为同一路径，应被拒绝。
            val dupZip = File(tmp, "dup.zip")
            writeZip(
                dupZip,
                listOf(
                    "app/file.txt" to "one".toByteArray(),
                    "app\\file.txt" to "two".toByteArray(),
                ),
            )

            val outDir = File(tmp, "out")
            val result = manager.extractToMainPack(dupZip.absolutePath, outDir.absolutePath)

            // 若 zip4j 未能保留反斜杠条目字面值导致未识别重复，则此断言需如实注释。
            // 依 validation 逻辑：validateEntries 将两条目归一化后视为重复 → fail-closed。
            assertFalse("重复条目必须解压失败, 实际: $result", result.success)
            assertDirEmpty(outDir)
            assertNoStagingLeftover(tmp)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `RAR 格式 fail-closed 且不留半成品`() = runTest {
        val tmp = java.nio.file.Files.createTempDirectory("em-rar").toFile()
        try {
            // RAR 在解包前按扩展名短路，无需构造真实 RAR 内容。
            val rar = File(tmp, "evil.rar")
            rar.writeText("garbage")

            val outDir = File(tmp, "out")
            val result = manager.extractToMainPack(rar.absolutePath, outDir.absolutePath)

            assertFalse("RAR 必须 fail-closed, 实际: $result", result.success)
            assertEquals("RAR 格式暂不支持", result.errorMessage)
            assertDirEmpty(outDir)
            assertNoStagingLeftover(tmp)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `损坏 7z 压缩包 fail-closed 且 staging 被清理`() = runTest {
        val tmp = java.nio.file.Files.createTempDirectory("em-7z").toFile()
        try {
            // 损坏的 7z 不是真实 7z 格式，SevenZFile 打开即失败，应 fail-closed。
            val corrupt = File(tmp, "corrupt.7z")
            corrupt.writeBytes(ByteArray(100) { it.toByte() })

            val outDir = File(tmp, "out")
            val result = manager.extractToMainPack(corrupt.absolutePath, outDir.absolutePath)

            assertFalse("损坏 7z 必须解压失败, 实际: $result", result.success)
            assertDirEmpty(outDir)
            assertNoStagingLeftover(tmp)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
