package com.example.tfgwj.domain.usecase

import com.example.tfgwj.domain.model.AccessMode
import com.example.tfgwj.domain.model.PermissionStatus
import com.example.tfgwj.domain.repository.ConfigRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CheckEnvironmentUseCaseTest {

    private val repository: ConfigRepository = mockk()
    private val useCase = CheckEnvironmentUseCase(repository)

    @Test
    fun `invoke calls repository with correct params`() = runTest {
        val pkg = "com.example.target"
        val expectedStatus = PermissionStatus(
            hasStoragePermission = true,
            hasShizukuPermission = true,
            bestMode = AccessMode.SHIZUKU
        )
        coEvery { repository.checkEnvironment(pkg, false) } returns expectedStatus

        val result = useCase(pkg)

        assertEquals(expectedStatus, result)
    }

    @Test
    fun `invoke with forceRefresh calls repository correctly`() = runTest {
        val pkg = "com.example.target"
        val expectedStatus = PermissionStatus(
            hasStoragePermission = true,
            hasShizukuPermission = false,
            bestMode = AccessMode.NATIVE
        )
        coEvery { repository.checkEnvironment(pkg, true) } returns expectedStatus

        val result = useCase(pkg, forceRefresh = true)

        assertEquals(expectedStatus, result)
    }
}
