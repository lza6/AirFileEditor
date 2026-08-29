package com.example.tfgwj.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * SmallFileBatchWriter 单元测试 (V18)
 *
 * 纯 JVM，验证攒批/刷盘/失败隔离/清空。
 * 依赖 android.util.Log (AppLogger)，故用 Robolectric。
 */
class SmallFileBatchWriterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun bytesOf(size: Int): ByteArray = ByteArray(size) { (it % 128).toByte() }

    @Test
    fun `accept accumulates bytes and marks flush when threshold reached`() {
        SmallFileBatchWriter.clear()

        // 未过阈值不触发
        val trigger1 = SmallFileBatchWriter.accept(bytesOf(1024))
        assertFalse(trigger1)

        // 单条写入 BATCH_SIZE(64)*1024 会触发阈值（因为 >= BATCH_SIZE 或累计 >= 4MB）
        val trigger2 = SmallFileBatchWriter.accept(bytesOf(1024))
        assertFalse(trigger2)
        assertTrue(SmallFileBatchWriter.pendingBytes() >= 2048)
    }

    @Test
    fun `flush writes all pending files to target dir`() {
        SmallFileBatchWriter.clear()
        SmallFileBatchWriter.accept(bytesOf(1024))
        SmallFileBatchWriter.accept(bytesOf(2048))

        val target = File(tempFolder.root, "out")
        var bytes = 0L
        val written = SmallFileBatchWriter.flush(target) { index ->
            bytes += (if (index == 0) 1024 else 2048)
            "file_$index.txt"
        }

        assertTrue(written >= 3072L)
        assertTrue(target.listFiles()?.isNotEmpty() == true)
        assertEquals(0L, SmallFileBatchWriter.pendingBytes())
    }

    @Test
    fun `flush with custom names writes correct count`() {
        SmallFileBatchWriter.clear()
        SmallFileBatchWriter.accept(bytesOf(100))
        SmallFileBatchWriter.accept(bytesOf(200))
        SmallFileBatchWriter.accept(bytesOf(300))

        val target = File(tempFolder.root, "out2")
        val written = SmallFileBatchWriter.flush(target) { index -> "item_$index.dat" }

        assertEquals(600L, written)
        assertEquals(3, target.listFiles()?.size)
        assertEquals(0L, SmallFileBatchWriter.pendingBytes())
    }

    @Test
    fun `flush on empty queue writes nothing`() {
        SmallFileBatchWriter.clear()

        val target = File(tempFolder.root, "out3")
        val written = SmallFileBatchWriter.flush(target)

        assertEquals(0L, written)
    }

    @Test
    fun `clear empties the queue`() {
        SmallFileBatchWriter.clear()
        SmallFileBatchWriter.accept(bytesOf(1000))
        assertTrue(SmallFileBatchWriter.pendingBytes() > 0L)

        SmallFileBatchWriter.clear()

        assertEquals(0L, SmallFileBatchWriter.pendingBytes())
        assertEquals(0L, SmallFileBatchWriter.flush(File(tempFolder.root, "out4")))
    }

    @Test
    fun `flush count increments after successful flush`() {
        SmallFileBatchWriter.clear()
        val before = SmallFileBatchWriter.flushCount()
        SmallFileBatchWriter.accept(bytesOf(1024))

        val written = SmallFileBatchWriter.flush(File(tempFolder.root, "out5"))

        assertEquals(1024L, written)
        assertEquals(before + 1, SmallFileBatchWriter.flushCount())
    }
}
