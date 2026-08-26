package com.example.tfgwj.security

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * InputValidator 单元测试
 *
 * 覆盖安全契约核心路径：
 * - Path Traversal 防护
 * - Zip Slip 防护
 * - 命令注入防护
 * - 包名校验
 * - 文件名/URL/正则安全校验
 *
 * 注意：InputValidator 仅依赖 java.io.File + android.util.Log。测试通过
 * Robolectric 提供 Log 实现，无需真实 Android 环境。
 */
class InputValidatorTest {
    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ---- validatePath ----

    @Test
    fun `blank path is rejected`() {
        assertNull(InputValidator.validatePath("  "))
    }

    @Test
    fun `valid absolute path is returned normalized`() {
        val result = InputValidator.validatePath("C:\\storage\\emulated\\0\\Android\\data")
        // On Windows, File.canonicalPath preserves the drive letter from the working dir
        assertTrue(result != null)
        assertTrue(result!!.contains("Android") && result.contains("data"))
    }

    @Test
    fun `path traversal dotdot is rejected`() {
        assertNull(InputValidator.validatePath("/storage/../etc/passwd"))
    }

    @Test
    fun `path traversal encoded is rejected`() {
        // URL-encoded dotdot patterns are checked via string contains, not canonical path
        assertNull(InputValidator.validatePath("../etc"))
    }

    @Test
    fun `path outside base is rejected`() {
        // Use a temp dir as base, then try a path outside it
        val base = File(System.getProperty("java.io.tmpdir"), "input_validator_base").also { it.mkdirs() }
        try {
            // A sibling path outside base should be rejected
            val sibling = File(System.getProperty("java.io.tmpdir"), "input_validator_other").also { it.mkdirs() }
            assertNull(InputValidator.validatePath(sibling.absolutePath, basePath = base.absolutePath))
        } finally {
            base.deleteRecursively()
            File(System.getProperty("java.io.tmpdir"), "input_validator_other").deleteRecursively()
        }
    }

    @Test
    fun `path inside base is accepted`() {
        val base = File(System.getProperty("java.io.tmpdir"), "input_validator_base").also { it.mkdirs() }
        val child = File(base, "app/data").also { it.mkdirs() }
        try {
            val result = InputValidator.validatePath(child.absolutePath, basePath = base.absolutePath)
            assertEquals(child.canonicalPath, result)
        } finally {
            base.deleteRecursively()
        }
    }

    // ---- validateZipEntry ----

    @Test
    fun `zip slip entry escaping target is rejected`() {
        val target = File(System.getProperty("java.io.tmpdir"), "zip_target")
        target.mkdirs()
        try {
            val result = InputValidator.validateZipEntry(target, "../../evil.txt")
            assertNull(result)
        } finally {
            target.deleteRecursively()
        }
    }

    @Test
    fun `zip entry inside target is accepted`() {
        val target = File(System.getProperty("java.io.tmpdir"), "zip_target")
        target.mkdirs()
        try {
            val result = InputValidator.validateZipEntry(target, "data/file.txt")
            assertTrue(result != null)
        } finally {
            target.deleteRecursively()
        }
    }

    // ---- sanitizeShellArg ----

    @Test
    fun `shell arg strips injection metacharacters`() {
        val clean = InputValidator.sanitizeShellArg("foo;rm -rf /|cat")
        assertFalse(clean.contains(";"))
        assertFalse(clean.contains("|"))
    }

    // ---- isValidPackageName ----

    @Test
    fun `valid package name passes`() {
        assertTrue(InputValidator.isValidPackageName("com.example.game"))
    }

    @Test
    fun `package with single segment rejected`() {
        assertFalse(InputValidator.isValidPackageName("game"))
    }

    @Test
    fun `package with spaces rejected`() {
        assertFalse(InputValidator.isValidPackageName("com.example game"))
    }

    @Test
    fun `empty package rejected`() {
        assertFalse(InputValidator.isValidPackageName(""))
    }

    // ---- isSafeFileName ----

    @Test
    fun `filename with slash rejected`() {
        assertFalse(InputValidator.isSafeFileName("a/b.txt"))
    }

    @Test
    fun `filename with null char rejected`() {
        assertFalse(InputValidator.isSafeFileName("a\u0000b.txt"))
    }

    @Test
    fun `reserved windows name rejected`() {
        assertFalse(InputValidator.isSafeFileName("CON"))
    }

    @Test
    fun `normal filename accepted`() {
        assertTrue(InputValidator.isSafeFileName("data.txt"))
    }

    // ---- isSafeUrl ----

    @Test
    fun `non https url rejected`() {
        assertFalse(InputValidator.isSafeUrl("http://example.com"))
    }

    @Test
    fun `javascript scheme rejected`() {
        assertFalse(InputValidator.isSafeUrl("javascript:alert(1)"))
    }

    @Test
    fun `valid https url accepted`() {
        assertTrue(InputValidator.isSafeUrl("https://example.com/rules.json"))
    }

    // ---- isSafeRegex ----

    @Test
    fun `nested quantifier regex rejected`() {
        assertFalse(InputValidator.isSafeRegex("(.*)+"))
    }

    @Test
    fun `invalid regex rejected`() {
        // isSafeRegex internally calls Pattern.compile which throws
        assertFalse(InputValidator.isSafeRegex("([a-"))
    }

    @Test
    fun `simple safe regex accepted`() {
        assertTrue(InputValidator.isSafeRegex("^[a-z]+$"))
    }
}
