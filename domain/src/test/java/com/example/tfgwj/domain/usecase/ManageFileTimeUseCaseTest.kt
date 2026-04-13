package com.example.tfgwj.domain.usecase

import com.example.tfgwj.domain.repository.ConfigRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ManageFileTimeUseCaseTest {

    private val repository: ConfigRepository = mockk()
    private val useCase = ManageFileTimeUseCase(repository)

    @Test
    fun `randomize calls repository`() = runTest {
        val path = "/some/file"
        val expected = Result.success(Pair(1, 123456789L))
        coEvery { repository.randomizeTime(path) } returns expected

        val result = useCase.randomize(path)

        assertEquals(expected, result)
    }

    @Test
    fun `applyTime calls repository`() = runTest {
        val path = "/some/file"
        val time = 987654321L
        coEvery { repository.setCustomTime(path, time) } returns Result.success(1)

        val result = useCase.applyTime(path, time)

        assertEquals(Result.success(1), result)
    }

    @Test
    fun `getCurrentTime calls repository`() {
        val path = "/some/file"
        every { repository.getFileTime(path) } returns 555L

        val result = useCase.getCurrentTime(path)

        assertEquals(555L, result)
    }
}
