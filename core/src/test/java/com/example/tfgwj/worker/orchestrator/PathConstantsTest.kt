package com.example.tfgwj.worker.orchestrator

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathConstantsTest {

    // ==================== 原有测试 ====================

    @Test
    fun `maps source data package contents into selected target package`() {
        val root = Files.createTempDirectory("path-constants").toFile()
        val androidDir = File(root, "Android")
        val sourceFile = File(androidDir, "data/com.source.game/files/Config/settings.ini")
        sourceFile.parentFile?.mkdirs()
        sourceFile.writeText("fixture")

        try {
            val target = PathConstants.resolveTargetFile(androidDir, sourceFile, "com.target.game")

            assertEquals(
                "/storage/emulated/0/Android/data/com.target.game/files/Config/settings.ini",
                normalizePlatformPath(target.path),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `maps source obb package contents into selected target package`() {
        val root = Files.createTempDirectory("path-constants").toFile()
        val androidDir = File(root, "Android")
        val sourceFile = File(androidDir, "obb/com.source.game/main.1.com.source.game.obb")
        sourceFile.parentFile?.mkdirs()
        sourceFile.writeText("fixture")

        try {
            val target = PathConstants.resolveTargetFile(androidDir, sourceFile, "com.target.game")

            assertEquals(
                "/storage/emulated/0/Android/obb/com.target.game/main.1.com.source.game.obb",
                normalizePlatformPath(target.path),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rejects files outside Android data and obb package roots`() {
        val root = Files.createTempDirectory("path-constants").toFile()
        val androidDir = File(root, "Android")
        val sourceFile = File(androidDir, "unexpected/config.ini")
        sourceFile.parentFile?.mkdirs()
        sourceFile.writeText("fixture")

        try {
            val failure = runCatching {
                PathConstants.resolveTargetFile(androidDir, sourceFile, "com.target.game")
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
        } finally {
            root.deleteRecursively()
        }
    }

    // ==================== 追加：包名校验测试 ====================

    @Test
    fun `valid package name passes validation`() {
        assertTrue(PathConstants.isValidPackageName("com.example.app"))
        assertTrue(PathConstants.isValidPackageName("a.b"))
        assertTrue(PathConstants.isValidPackageName("com.example.game_123.test"))
        assertTrue(PathConstants.isValidPackageName("com.android.systemui"))
        assertTrue(PathConstants.isValidPackageName("org.example.my_app_2"))
    }

    @Test
    fun `empty package name is rejected`() {
        assertFalse(PathConstants.isValidPackageName(""))
        assertFalse(PathConstants.isValidPackageName("  "))
    }

    @Test
    fun `package name with illegal characters is rejected`() {
        assertFalse(PathConstants.isValidPackageName("com.example app"))
        assertFalse(PathConstants.isValidPackageName("com.example.app!"))
        assertFalse(PathConstants.isValidPackageName("com.example.app#"))
        assertFalse(PathConstants.isValidPackageName("com.example.app@domain"))
        assertFalse(PathConstants.isValidPackageName("com.example.app/"))
        assertFalse(PathConstants.isValidPackageName("com.example.app\\test"))
    }

    @Test
    fun `package name without at least one dot is rejected`() {
        assertFalse(PathConstants.isValidPackageName("com"))
        assertFalse(PathConstants.isValidPackageName("single"))
        assertFalse(PathConstants.isValidPackageName("no_dot_here"))
    }

    @Test
    fun `package name starting with digit is rejected`() {
        assertFalse(PathConstants.isValidPackageName("1com.example.app"))
        assertFalse(PathConstants.isValidPackageName("123.abc"))
    }

    @Test
    fun `buildTargetDataPath returns correct path`() {
        val path = PathConstants.buildTargetDataPath("com.example.app")
        assertEquals("/storage/emulated/0/Android/data/com.example.app", normalizePlatformPath(path))
    }

    @Test
    fun `buildTargetObbPath returns correct path`() {
        val path = PathConstants.buildTargetObbPath("com.example.app")
        assertEquals("/storage/emulated/0/Android/obb/com.example.app", normalizePlatformPath(path))
    }

    @Test
    fun `buildTargetDataPath throws for illegal package name`() {
        val failure = runCatching {
            PathConstants.buildTargetDataPath("")
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `buildTargetObbPath throws for illegal package name`() {
        val failure = runCatching {
            PathConstants.buildTargetObbPath("invalid")
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `normalizeSubPath rejects path traversal`() {
        // 通过 buildTargetFilePath 间接测试 normalizeSubPath，
        // 绕过 File 构造器自动解析 .. 的 Windows 行为
        val failure = runCatching {
            PathConstants.buildTargetFilePath("com.example.app", "files/../secrets")
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `calculateRelativePath correctly computes relative path`() {
        val root = Files.createTempDirectory("path-constants").toFile()
        val androidDir = File(root, "Android")
        val file = File(androidDir, "data/com.example.app/files/settings.ini")
        file.parentFile?.mkdirs()
        file.writeText("fixture")

        try {
            val relative = PathConstants.calculateRelativePath(androidDir, file.absolutePath)
            assertEquals("data/com.example.app/files/settings.ini", relative.replace('\\', '/'))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `resolveTargetFile correctly maps target path`() {
        val root = Files.createTempDirectory("path-constants").toFile()
        val androidDir = File(root, "Android")
        val sourceFile = File(androidDir, "data/com.source.game/files/settings.ini")
        sourceFile.parentFile?.mkdirs()
        sourceFile.writeText("fixture")

        try {
            val target = PathConstants.resolveTargetFile(androidDir, sourceFile, "com.target.game")
            assertEquals(
                "/storage/emulated/0/Android/data/com.target.game/files/settings.ini",
                normalizePlatformPath(target.path),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `resolveTargetFile rejects illegal package name`() {
        val root = Files.createTempDirectory("path-constants").toFile()
        val androidDir = File(root, "Android")
        val sourceFile = File(androidDir, "data/com.source.game/files/settings.ini")
        sourceFile.parentFile?.mkdirs()
        sourceFile.writeText("fixture")

        try {
            val failure = runCatching {
                PathConstants.resolveTargetFile(androidDir, sourceFile, "invalid")
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `isValidAndroidDir returns false for empty directory`() {
        val root = Files.createTempDirectory("path-constants").toFile()
        val androidDir = File(root, "Android")
        androidDir.mkdirs()

        try {
            assertFalse(PathConstants.isValidAndroidDir(androidDir))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `isValidAndroidDir returns true when data directory exists`() {
        val root = Files.createTempDirectory("path-constants").toFile()
        val androidDir = File(root, "Android")
        File(androidDir, "data").mkdirs()

        try {
            assertTrue(PathConstants.isValidAndroidDir(androidDir))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `buildTargetFilePath with empty subPath returns base`() {
        val path = PathConstants.buildTargetFilePath("com.example.app", "")
        assertEquals("/storage/emulated/0/Android/data/com.example.app", normalizePlatformPath(path))
    }

    @Test
    fun `buildTargetFilePath with obb flag returns obb path`() {
        val path = PathConstants.buildTargetFilePath("com.example.app", "main.1.obb", isObb = true)
        assertEquals("/storage/emulated/0/Android/obb/com.example.app/main.1.obb", normalizePlatformPath(path))
    }

    @Test
    fun `extractAndroidType identifies data directory`() {
        assertEquals("data", PathConstants.extractAndroidType("/storage/emulated/0/Android/data/com.example.app"))
    }

    @Test
    fun `extractAndroidType identifies obb directory`() {
        assertEquals("obb", PathConstants.extractAndroidType("/storage/emulated/0/Android/obb/com.example.app"))
    }

    @Test
    fun `extractAndroidType defaults to data for unknown`() {
        assertEquals("data", PathConstants.extractAndroidType("/storage/emulated/0/Android/misc"))
    }

    /**
     * Windows JVM 上 `File("/storage/...")` 会被解析为当前盘符下的路径
     * （如 `C:\storage\...`）；Android 真机上本来就是 `/storage/...`。
     * 该函数剥离盘符前缀，使断言在开发机（Windows）与 Android 真机行为一致。
     */
    private fun normalizePlatformPath(path: String): String =
        path.replace('\\', '/').replace(Regex("^[a-zA-Z]:/*"), "/")
}