package com.example.tfgwj.utils

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class FileHasherTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @Test
    fun `calculateMD5 returns correct hash`() {
        val file = tempFolder.newFile("test.txt")
        file.writeText("hello world")

        // md5("hello world") = 5eb63bbbe01eeed093cb22bb8f5acdc3
        val hash = FileHasher.calculateMD5(file)
        assertEquals("5eb63bbbe01eeed093cb22bb8f5acdc3", hash)
    }

    @Test
    fun `calculateSHA256 returns correct hash`() {
        val file = tempFolder.newFile("test_sha.txt")
        file.writeText("hello world")

        // sha256("hello world") = b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9
        val hash = FileHasher.calculateSHA256(file)
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", hash)
    }

    @Test
    fun `verifyFileHash returns true for matching hash`() {
        val file = tempFolder.newFile("verify.txt")
        file.writeText("test data")
        val md5 = FileHasher.calculateMD5(file)!!

        assertTrue(FileHasher.verifyFileHash(file, md5, "MD5"))
        assertTrue(FileHasher.verifyFileHash(file, md5.uppercase(), "MD5"))
    }

    @Test
    fun `areFilesEqual returns true for identical files`() {
        val file1 = tempFolder.newFile("file1.bin")
        val file2 = tempFolder.newFile("file2.bin")
        val content = "consistent content"
        file1.writeText(content)
        file2.writeText(content)

        assertTrue(FileHasher.areFilesEqual(file1, file2))
    }

    @Test
    fun `areFilesEqual returns false for different files`() {
        val file1 = tempFolder.newFile("file1_diff.bin")
        val file2 = tempFolder.newFile("file2_diff.bin")
        file1.writeText("content A")
        file2.writeText("content B")

        assertFalse(FileHasher.areFilesEqual(file1, file2))
    }

    @Test
    fun `areFilesEqualWithSampling works for large files`() {
        val file1 = tempFolder.newFile("large1.bin")
        val file2 = tempFolder.newFile("large2.bin")

        // Create 2MB files (sampleSize is 512KB, so 2MB > 512KB * 3)
        val content = ByteArray(2 * 1024 * 1024) { it.toByte() }
        file1.writeBytes(content)
        file2.writeBytes(content)

        assertTrue(FileHasher.areFilesEqualWithSampling(file1, file2, 512 * 1024L))

        // Modify middle
        file2.run {
            val bytes = readBytes()
            bytes[1024 * 1024] = (bytes[1024 * 1024] + 1).toByte()
            writeBytes(bytes)
        }
        assertFalse(FileHasher.areFilesEqualWithSampling(file1, file2, 512 * 1024L))
    }

    @Test
    fun `calculateDirectoryHash returns consistent hash`() {
        val subDir = tempFolder.newFolder("sub")
        File(subDir, "a.txt").writeText("content a")
        File(subDir, "b.txt").writeText("content b")

        val hash1 = FileHasher.calculateDirectoryHash(subDir)
        assertNotNull(hash1)

        val hash2 = FileHasher.calculateDirectoryHash(subDir)
        assertEquals(hash1, hash2)
    }
}
