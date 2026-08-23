package com.example.tfgwj.domain.usecase

import com.example.tfgwj.domain.repository.ConfigRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplaceFileUseCaseTest {

    private val repository: ConfigRepository = mockk()
    private val useCase = ReplaceFileUseCase(repository)

    @Test
    fun `invoke with empty sourcePath returns failure`() = runTest {
        val result = useCase("", "com.example.target")
        assertTrue(result.isFailure)
        assertEquals("源路径不能为空", result.exceptionOrNull()?.message)
    }

    @Test
    fun `invoke with empty targetPackage returns failure`() = runTest {
        val result = useCase("/path/to/source", "")
        assertTrue(result.isFailure)
        assertEquals("目标包名不能为空", result.exceptionOrNull()?.message)
    }

    @Test
    fun `invoke with illegal package name returns failure before repository`() = runTest {
        val result = useCase("/path/to/source", "not_a_package")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.startsWith("非法目标包名") == true)
    }

    @Test
    fun `invoke with valid params calls repository and returns success`() = runTest {
        val source = "/path/to/source"
        val target = "com.example.target"
        coEvery { repository.startReplace(source, target, false) } returns Result.success("Success")

        val result = useCase(source, target)

        assertTrue(result.isSuccess)
        assertEquals("Success", result.getOrNull())
    }

    @Test
    fun `invoke with incremental flag calls repository correctly`() = runTest {
        val source = "/path/to/source"
        val target = "com.example.target"
        coEvery { repository.startReplace(source, target, true) } returns Result.success("Incremental Success")

        val result = useCase(source, target, incremental = true)

        assertTrue(result.isSuccess)
        assertEquals("Incremental Success", result.getOrNull())
    }
}
