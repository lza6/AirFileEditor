package com.example.tfgwj.security

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveEntryValidatorTest {

    @Test
    fun `resolves nested relative archive entry inside destination`() {
        val destination = Files.createTempDirectory("archive-entry-validator").toFile()

        try {
            val resolved = ArchiveEntryValidator.resolveWithin(destination, "Android/data/config.ini")

            assertEquals(
                File(destination, "Android/data/config.ini").canonicalPath,
                resolved.canonicalPath,
            )
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun `rejects traversal absolute and drive-qualified archive entries`() {
        val destination = Files.createTempDirectory("archive-entry-validator").toFile()

        try {
            listOf("../outside.txt", "/outside.txt", "C:\\outside.txt", "..\\outside.txt").forEach { entryName ->
                assertFalse("entry should be rejected: $entryName", ArchiveEntryValidator.isSafeEntryName(entryName))
            }
        } finally {
            destination.deleteRecursively()
        }
    }

    @Test
    fun `rejects a sibling with the same path prefix as destination`() {
        val parent = Files.createTempDirectory("archive-entry-validator-parent").toFile()
        val destination = File(parent, "target")
        destination.mkdirs()

        try {
            assertFalse(ArchiveEntryValidator.isWithinDestination(destination, File(parent, "target-escape/file.txt")))
            assertTrue(ArchiveEntryValidator.isWithinDestination(destination, File(destination, "nested/file.txt")))
        } finally {
            parent.deleteRecursively()
        }
    }
}
