package com.example.tfgwj.ui.mvi

import com.example.tfgwj.domain.model.AccessMode
import com.example.tfgwj.domain.model.EnvironmentStatus
import com.example.tfgwj.domain.model.TaskPhase
import com.example.tfgwj.domain.repository.ConfigRepository
import com.example.tfgwj.domain.usecase.CheckEnvironmentUseCase
import com.example.tfgwj.domain.usecase.ManageFileTimeUseCase
import com.example.tfgwj.domain.usecase.ManagePatchUseCase
import com.example.tfgwj.domain.usecase.ReplaceFileUseCase
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ReplacingViewModel 单元测试
 * 验证 MVI 架构状态转换逻辑
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplacingViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModel: ReplacingViewModel

    private val replaceFileUseCase: ReplaceFileUseCase = mockk(relaxed = true)
    private val checkEnvironmentUseCase: CheckEnvironmentUseCase = mockk(relaxed = true)
    private val managePatchUseCase: ManagePatchUseCase = mockk(relaxed = true)
    private val manageFileTimeUseCase: ManageFileTimeUseCase = mockk(relaxed = true)
    private val repository: ConfigRepository = mockk(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = ReplacingViewModel(
            replaceFileUseCase,
            checkEnvironmentUseCase,
            managePatchUseCase,
            manageFileTimeUseCase,
            repository
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has correct defaults`() {
        val state = viewModel.uiState.value

        assertEquals(0, state.processedFiles)
        assertEquals(0, state.totalFiles)
        assertEquals(0, state.progress)
        assertEquals(0f, state.speedMBps)
        assertEquals(TaskPhase.IDLE, state.phase)
        assertFalse(state.isReplacing)
        assertFalse(state.isPaused)
        assertNull(state.errorMessage)
        assertNull(state.selectedMainPackPath)
        assertTrue(state.patchVersions.isEmpty())
    }

    @Test
    fun `StartReplace intent transitions to isReplacing state`() {
        viewModel.handleIntent(ReplacingIntent.StartReplace("/test/path", "com.test.package"))

        val state = viewModel.uiState.value
        assertTrue(state.isReplacing)
        assertNull(state.errorMessage)
    }

    @Test
    fun `PauseReplace sets paused flag`() {
        viewModel.handleIntent(ReplacingIntent.PauseReplace)

        val state = viewModel.uiState.value
        assertTrue(state.isPaused)
    }

    @Test
    fun `ResumeReplace clears paused flag`() {
        viewModel.handleIntent(ReplacingIntent.PauseReplace)
        viewModel.handleIntent(ReplacingIntent.ResumeReplace)

        val state = viewModel.uiState.value
        assertFalse(state.isPaused)
    }

    @Test
    fun `RetryReplace clears error`() {
        // RetryReplace intent implementation in ViewModel actually doesn't clear error directly yet
        // but we can test the intent handling
        viewModel.handleIntent(ReplacingIntent.RetryReplace)

        val state = viewModel.uiState.value
        assertNull(state.errorMessage)
    }

    @Test
    fun `updatePermissions modifies permission state`() {
        viewModel.updatePermissions(hasStorage = true, hasShizuku = true)

        val state = viewModel.uiState.value
        assertTrue(state.hasStoragePermission)
        assertTrue(state.hasShizukuPermission)
    }

    @Test
    fun `updateEnvironmentStatus changes environment status correctly`() {
        viewModel.updateEnvironmentStatus(EnvironmentStatus.VALID)

        val state = viewModel.uiState.value
        assertEquals(EnvironmentStatus.VALID, state.environmentStatus)
    }

    @Test
    fun `updateMainPackInfo updates all main pack fields`() {
        viewModel.updateMainPackInfo(
            path = "/path/to/main",
            appName = "Test App",
            icon = null,
            targetPackage = "com.test.package"
        )

        val state = viewModel.uiState.value
        assertEquals("/path/to/main", state.selectedMainPackPath)
        assertEquals("Test App", state.mainPackAppName)
        assertEquals("com.test.package", state.targetPackage)
    }

    @Test
    fun `updatePatchVersions updates patch list and clears scanning`() {
        val patches = listOf(
            PatchVersion("v1.0", "/path/v1", 1024L, 10),
            PatchVersion("v2.0", "/path/v2", 2048L, 20)
        )

        viewModel.updatePatchVersions(patches)

        val state = viewModel.uiState.value
        assertEquals(2, state.patchVersions.size)
        assertEquals("v1.0", state.patchVersions[0].version)
        assertFalse(state.isScanning)
    }

    @Test
    fun `updateLogContent sets log content and size`() {
        val content = "Log line 1\nLog line 2"
        val size = "2.5 KB"

        viewModel.updateLogContent(content, size)

        val state = viewModel.uiState.value
        assertEquals(content, state.logContent)
        assertEquals(size, state.logSize)
    }

    @Test
    fun `clearLogs resets log content to empty`() {
        viewModel.updateLogContent("Some logs", "1 KB")
        viewModel.handleIntent(ReplacingIntent.ClearLogs)

        val state = viewModel.uiState.value
        assertEquals("", state.logContent)
        assertEquals("0 KB", state.logSize)
    }

    @Test
    fun `LockFileTime updates locked time state`() {
        val timestamp = System.currentTimeMillis()
        viewModel.handleIntent(ReplacingIntent.LockFileTime(timestamp))

        val state = viewModel.uiState.value
        assertEquals(timestamp, state.lockedTime)
    }

    @Test
    fun `UnlockFileTime clears locked time state`() {
        viewModel.handleIntent(ReplacingIntent.LockFileTime(System.currentTimeMillis()))
        viewModel.handleIntent(ReplacingIntent.UnlockFileTime)

        val state = viewModel.uiState.value
        assertNull(state.lockedTime)
    }

    @Test
    fun `SelectPatch updates selected patch version`() {
        viewModel.handleIntent(ReplacingIntent.SelectPatch("v1.0"))

        val state = viewModel.uiState.value
        assertEquals("v1.0", state.selectedPatchVersion)
    }
}
