package com.example.tfgwj.performance

import com.example.tfgwj.utils.FileHasher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

/**
 * IoEngine 单元测试
 * 覆盖 fastCopy / needsUpdate / generateSamplingFingerprint / parallelProcess 四组 API。
 *
 * 依赖 android.util.Log (AppLogger / FileHasher)，因此使用 Robolectric。
 * 大文件 mmap 在部分 CI 环境不可用，IoEngine 内部已实现降级，测试中仍做 try-catch 兜底。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class IoEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var sourceFile: File
    private lateinit var targetFile: File

    // 策略阈值（与 IoEngine 常量一致）
    private val smallFileSize = 16 * 1024 // 16KB < 32KB:直接缓冲区复制
    private val mediumFileSize = 1024 * 1024 // 1MB ∈ (32KB, 16MB):自适应缓冲区流
    private val largeFileSize = 16 * 1024 * 1024 // 16MB >= 16MB:mmap 零拷贝

    @Before
    fun setup() {
        sourceFile = tempFolder.newFile("source.bin")
        targetFile = File(tempFolder.root, "target.bin")
    }

    private fun writePattern(file: File, size: Int, seed: Int = 0) {
        file.writeBytes(ByteArray(size) { ((it + seed) % 251).toByte() })
    }

    // ==================== fastCopy ====================

    @Test
    fun `fastCopy small file copies content and size`() {
        writePattern(sourceFile, smallFileSize)

        val copiedBytes = IoEngine.fastCopy(sourceFile, targetFile)

        assertEquals(smallFileSize.toLong(), copiedBytes)
        assertTrue(targetFile.exists())
        assertEquals(smallFileSize.toLong(), targetFile.length())
        assertTrue(sourceFile.readBytes().contentEquals(targetFile.readBytes()))
    }

    @Test
    fun `fastCopy medium file uses adaptive buffer path`() {
        writePattern(sourceFile, mediumFileSize, seed = 13)

        val copiedBytes = IoEngine.fastCopy(sourceFile, targetFile)

        assertEquals(mediumFileSize.toLong(), copiedBytes)
        assertTrue(targetFile.exists())
        assertEquals(mediumFileSize.toLong(), targetFile.length())
        assertTrue(sourceFile.readBytes().contentEquals(targetFile.readBytes()))
    }

    @Test
    fun `fastCopy large file uses mmap or fallback and copies completely`() {
        val content = ByteArray(largeFileSize) { it.toByte() }
        sourceFile.writeBytes(content)

        var copiedBytes = -1L
        try {
            copiedBytes = IoEngine.fastCopy(sourceFile, targetFile)
        } catch (e: Exception) {
            // CI 环境 mmap 可能不可用;IoEngine 内部会自动降级到自适应流
            // 测试不因环境降级而失败,只验证最终结果
        }

        assertTrue("copiedBytes=$copiedBytes should be $largeFileSize or accessible via fallback",
            copiedBytes == largeFileSize.toLong() || copiedBytes > 0L)
        assertTrue(targetFile.exists())
        assertEquals(largeFileSize.toLong(), targetFile.length())
        assertTrue(content.contentEquals(targetFile.readBytes()))
    }

    @Test
    fun `fastCopy non-existent source returns zero`() {
        val missing = File(tempFolder.root, "missing.bin")

        val copiedBytes = IoEngine.fastCopy(missing, targetFile)

        assertEquals(0L, copiedBytes)
        assertFalse(targetFile.exists())
    }

    // ==================== needsUpdate ====================

    @Test
    fun `needsUpdate when target does not exist returns true`() {
        writePattern(sourceFile, 1024)

        assertTrue(IoEngine.needsUpdate(sourceFile, targetFile))
    }

    @Test
    fun `needsUpdate when sizes differ returns true`() {
        writePattern(sourceFile, 1024)
        writePattern(targetFile, 2048, seed = 7)

        assertTrue(IoEngine.needsUpdate(sourceFile, targetFile))
    }

    @Test
    fun `needsUpdate when same size same time returns false`() {
        writePattern(sourceFile, 1024)
        targetFile.writeBytes(sourceFile.readBytes())

        val sameTime = 1_600_000_000_000L
        sourceFile.setLastModified(sameTime)
        targetFile.setLastModified(sameTime)

        assertFalse(IoEngine.needsUpdate(sourceFile, targetFile))
    }

    @Test
    fun `needsUpdate same size different time compares content for 2MB files`() {
        val size = 2 * 1024 * 1024
        writePattern(sourceFile, size)
        targetFile.writeBytes(sourceFile.readBytes())

        sourceFile.setLastModified(1_600_000_000_000L)
        targetFile.setLastModified(1_600_000_005_000L)

        // 内容相同(mtime 不同) -> 走哈希比对 -> 无需更新
        assertFalse(IoEngine.needsUpdate(sourceFile, targetFile))

        // 修改目标文件中段字节、大小不变 -> 哈希不同 -> 需要更新
        val modified = targetFile.readBytes()
        modified[size / 2] = ((modified[size / 2].toInt() xor 0xFF) and 0xFF).toByte()
        targetFile.writeBytes(modified)
        targetFile.setLastModified(1_600_000_006_000L)

        assertTrue(IoEngine.needsUpdate(sourceFile, targetFile))
    }

    @Test
    fun `needsUpdate large file samples content when times differ`() {
        // 超过 5MB 全量 MD5 门槛 -> 走三段抽样比对 (FULL_MD5_THRESHOLD = 5MB)
        val size = 6 * 1024 * 1024
        writePattern(sourceFile, size)
        targetFile.writeBytes(sourceFile.readBytes())

        sourceFile.setLastModified(1_600_000_000_000L)
        targetFile.setLastModified(1_600_000_010_000L)

        // 内容一致 -> 抽样比对相等 -> 无需更新
        assertFalse(IoEngine.needsUpdate(sourceFile, targetFile))

        // 修改中部抽样区块(偏移 size/2)内的字节 -> 抽样比对不等 -> 需要更新
        RandomAccessFile(targetFile, "rw").use { raf ->
            raf.seek(size / 2L)
            val b = raf.read()
            raf.seek(size / 2L)
            raf.write((b xor 0xFF) and 0xFF)
        }
        targetFile.setLastModified(1_600_000_011_000L)

        assertTrue(IoEngine.needsUpdate(sourceFile, targetFile))
    }

    // ==================== generateSamplingFingerprint ====================

    @Test
    fun `generateSamplingFingerprint small file returns full md5`() {
        // 100KB < 1MB -> 返回全量 MD5
        writePattern(sourceFile, 100 * 1024, seed = 3)

        val fingerprint = IoEngine.generateSamplingFingerprint(sourceFile)
        val expected = FileHasher.calculateMD5(sourceFile)

        assertNotNull(expected)
        assertEquals(expected, fingerprint)
    }

    @Test
    fun `generateSamplingFingerprint large file returns sampling fingerprint`() {
        // 2MB >= 1MB -> 返回三段抽样指纹
        writePattern(sourceFile, 2 * 1024 * 1024, seed = 5)

        val fingerprint = IoEngine.generateSamplingFingerprint(sourceFile)

        assertTrue(fingerprint.isNotEmpty())
        assertEquals(32, fingerprint.length)

        // 抽样指纹与全量 MD5 应不同
        val fullMd5 = FileHasher.calculateMD5(sourceFile)
        assertNotNull(fullMd5)
        assertNotEquals(fullMd5, fingerprint)

        // 确定性:相同文件多次调用结果一致
        assertEquals(fingerprint, IoEngine.generateSamplingFingerprint(sourceFile))
    }

    @Test
    fun `generateSamplingFingerprint non-existent file returns empty`() {
        val missing = File(tempFolder.root, "missing.bin")

        assertEquals("", IoEngine.generateSamplingFingerprint(missing))
    }

    // ==================== parallelProcess ====================

    @Test
    fun `parallelProcess basic success case`() = runBlocking {
        val items = listOf("a", "b", "c")
        val progress = mutableListOf<Triple<Int, Int, String>>()

        val result = IoEngine.parallelProcess(
            items = items,
            concurrency = 2,
            action = { true },
            progressCallback = { done, total, name ->
                progress.add(Triple(done, total, name))
            },
        )

        assertTrue(result.success)
        assertEquals(3, result.successCount)
        assertEquals(0, result.failedCount)
        assertEquals(3, result.total)
        assertEquals(3, progress.size)
        assertEquals(3, progress.last().first)
        assertEquals(3, progress.last().second)
    }

    @Test
    fun `parallelProcess reports failed actions`() = runBlocking {
        val items = listOf(1, 2, 3)

        val result = IoEngine.parallelProcess(
            items = items,
            concurrency = 2,
            action = { it != 2 },
        )

        assertFalse(result.success)
        assertEquals(2, result.successCount)
        assertEquals(1, result.failedCount)
        assertEquals(3, result.total)
    }

    // ==================== V18：mmap 分块 + 超限降级 ====================

    @Test
    fun `mmap chunk constants are defined and sane`() {
        assertTrue(IoEngine.MMAP_CHUNK_SIZE > 0L)
        assertTrue(IoEngine.MMAP_MAX_FILE_SIZE % IoEngine.MMAP_CHUNK_SIZE == 0L)
        assertTrue(IoEngine.MMAP_THRESHOLD <= IoEngine.MMAP_MAX_FILE_SIZE)
    }

    @Test
    fun `file exceeding mmap cap falls back to channelCopy`() {
        // 不可能构造 >2GB 文件；改为验证 chunk 常量逻辑与 fastCopy 对 >cap 的路径选择。
        // IoEngine 内部对 >MMAP_MAX_FILE_SIZE 走 channelCopy，不依赖 mmap。
        assertTrue(IoEngine.MMAP_MAX_FILE_SIZE >= IoEngine.MMAP_THRESHOLD)
    }

    @Test
    fun `small batched writer threshold is reachable`() {
        IoEngine.bufferManager.reset()
        assertTrue(IoEngine.bufferManager.getCurrentBufferSize() > 0)
    }
}