package com.example.tfgwj.worker.orchestrator

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PathConstantsTest {

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

    /**
     * Windows JVM 上 `File("/storage/...")` 会被解析为当前盘符下的路径
     * （如 `C:\storage\...`）；Android 真机上本来就是 `/storage/...`。
     * 该函数剥离盘符前缀，使断言在开发机（Windows）与 Android 真机行为一致。
     */
    private fun normalizePlatformPath(path: String): String =
        path.replace('\\', '/').replace(Regex("^[a-zA-Z]:/*"), "/")
}