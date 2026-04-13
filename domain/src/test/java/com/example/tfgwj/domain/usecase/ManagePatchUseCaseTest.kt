package com.example.tfgwj.domain.usecase

import com.example.tfgwj.domain.repository.ArchiveInfo
import com.example.tfgwj.domain.repository.ConfigRepository
import com.example.tfgwj.domain.repository.PatchVersion
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagePatchUseCaseTest {

    private val repository: ConfigRepository = mockk()
    private val useCase = ManagePatchUseCase(repository)

    @Test
    fun `scanPatches calls repository`() = runTest {
        val patches = listOf(PatchVersion("1.0", "/path/1", 100, 10))
        coEvery { repository.scanPatchVersions() } returns patches

        val result = useCase.scanPatches()

        assertEquals(patches, result)
    }

    @Test
    fun `scanArchives calls repository`() = runTest {
        val archives = listOf(ArchiveInfo("test.zip", "/path/test.zip", "1MB", 1024))
        coEvery { repository.scanArchives() } returns archives

        val result = useCase.scanArchives()

        assertEquals(archives, result)
    }

    @Test
    fun `extractAndInstall calls repository with correct version name`() = runTest {
        val archive = ArchiveInfo("v1.2.3.zip", "/path/v1.2.3.zip", "1MB", 1024)
        coEvery { repository.extractArchive(archive.path, "pass", "v1.2.3") } returns Result.success("OK")

        val result = useCase.extractAndInstall(archive, "pass")

        assertEquals(Result.success("OK"), result)
    }
}
