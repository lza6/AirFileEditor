package com.example.tfgwj.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HighPerformanceIoEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var sourceFile: File
    private lateinit var targetFile: File

    @Before
    fun setup() {
        sourceFile = tempFolder.newFile("source.bin")
        targetFile = File(tempFolder.root, "target.bin")
    }

    @Test
    fun `fastCopy correctly copies small and large files`() {
        val testData = "Test IO Stream Pipeline Data Content".toByteArray()
        sourceFile.writeBytes(testData)

        val copiedBytes = HighPerformanceIoEngine.fastCopy(sourceFile, targetFile)
        assertEquals(testData.size.toLong(), copiedBytes)
        assertTrue(targetFile.exists())
        assertEquals(testData.size.toLong(), targetFile.length())
        assertEquals(String(testData), targetFile.readText())
    }

    @Test
    fun `generateSamplingFingerprint differentiates modified content`() {
        // 创建 2MB 文件
        val largeData1 = ByteArray(2 * 1024 * 1024) { (it % 128).toByte() }
        val largeData2 = largeData1.clone()
        // 修改中间字节
        largeData2[1024 * 1024] = 99

        sourceFile.writeBytes(largeData1)
        val file2 = tempFolder.newFile("source2.bin")
        file2.writeBytes(largeData2)

        val fp1 = HighPerformanceIoEngine.generateSamplingFingerprint(sourceFile)
        val fp2 = HighPerformanceIoEngine.generateSamplingFingerprint(file2)

        assertNotEquals(fp1, fp2)
    }
}
