package com.example.tfgwj

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import com.example.tfgwj.data.PreferencesManager
import com.example.tfgwj.databinding.ActivityMainBinding
import com.example.tfgwj.domain.model.TaskPhase
import com.example.tfgwj.manager.*
import com.example.tfgwj.performance.IoEngine
import com.example.tfgwj.performance.PerformanceMonitor
import com.example.tfgwj.security.ArchiveEntryValidator
import com.example.tfgwj.shizuku.ShizukuManager
import com.example.tfgwj.ui.FloatingBallController
import com.example.tfgwj.ui.FloatingBallManager
import com.example.tfgwj.ui.HelpDialog
import com.example.tfgwj.ui.HistoryDialogController
import com.example.tfgwj.ui.MainLifecycleCoordinator
import com.example.tfgwj.ui.PermissionController
import com.example.tfgwj.ui.TimePickerHelper
import com.example.tfgwj.ui.components.atoms.TaskStatus
import com.example.tfgwj.ui.components.molecules.PermissionCard
import com.example.tfgwj.ui.components.molecules.SnackbarManager
import com.example.tfgwj.ui.components.organisms.MainDashboard
import com.example.tfgwj.ui.compose.TaskProgressOverlay
import com.example.tfgwj.ui.mvi.ReplacingIntent
import com.example.tfgwj.ui.mvi.ReplacingViewModel
import com.example.tfgwj.ui.mvi.ReplacingViewModelFactory
import com.example.tfgwj.ui.theme.TfgwjTheme
import com.example.tfgwj.utils.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var shizukuManager: ShizukuManager
    private lateinit var patchManager: PatchManager
    private lateinit var mainPackManager: MainPackManager
    private lateinit var permissionManager: PermissionManager
    private val floatingBallManager: FloatingBallManager by lazy { FloatingBallManager(applicationContext) }

    private val replacingViewModel: ReplacingViewModel by viewModels {
        ReplacingViewModelFactory(applicationContext)
    }

    // 控制器委派
    private lateinit var permissionController: PermissionController
    private lateinit var floatingBallController: FloatingBallController
    private lateinit var lifecycleCoordinator: MainLifecycleCoordinator
    private lateinit var historyDialogController: HistoryDialogController

    // 权限请求
    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.all { it }) checkAllPermissions()
        }

    private val manageStorageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            checkAllPermissions()
        }

    private val folderPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) result.data?.data?.let { handleSelectedFolder(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AppLogger.init(this)
        PerformanceMonitor.init(this)
        createNotificationChannel()

        initManagers()
        initViews()
        setupObservers()
        checkAllPermissions()

        floatingBallController.observeTaskState()
        lifecycleCoordinator.launchStartupTasks()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val status = permissionManager.checkAllPermissions()
            permissionManager.updateShizukuStatus()
            if (!status.hasManageStorage) requestPermissions()
        }
    }

    private fun initManagers() {
        preferencesManager = PreferencesManager(applicationContext)
        shizukuManager = ShizukuManager.getInstance(applicationContext)
        patchManager = PatchManager.getInstance()
        mainPackManager = MainPackManager.getInstance()
        permissionManager = PermissionManager(applicationContext)
        permissionController =
            PermissionController(
                this,
                permissionManager,
                manageStorageLauncher,
                storagePermissionLauncher,
            )
        floatingBallController = FloatingBallController(this, replacingViewModel, floatingBallManager)
        lifecycleCoordinator = MainLifecycleCoordinator(this, replacingViewModel)
        historyDialogController = HistoryDialogController(this)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel("file_replace_channel", "文件替换通知", android.app.NotificationManager.IMPORTANCE_LOW)
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun initViews() {
        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_wechat -> {
                    openWechat()
                    true
                }
                R.id.action_github -> {
                    openGithub()
                    true
                }
                R.id.action_help -> {
                    HelpDialog.show(this)
                    true
                }
                R.id.action_stealth -> {
                    showPhantomStealthDialog()
                    true
                }
                R.id.action_history -> {
                    showHistoryDialog()
                    true
                }
                else -> false
            }
        }
        setupPermissionCardCompose()
        setupMainDashboardCompose()
        setupTaskOverlayCompose()
        setupMainPackCardCompose()
        setupPatchVersionCardCompose()
        setupLogConsoleCompose()
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            preferencesManager.lockedTimeEnabled.collectLatest { enabled ->
                if (enabled) {
                    preferencesManager.lockedTime.collectLatest { replacingViewModel.handleIntent(ReplacingIntent.LockFileTime(it)) }
                } else {
                    replacingViewModel.handleIntent(ReplacingIntent.UnlockFileTime)
                }
            }
        }
        lifecycleScope.launch {
            shizukuManager.isAuthorized.collectLatest { if (it != permissionManager.permissionStatus.value.hasShizukuPermission) checkAllPermissions() }
        }
    }

    private fun checkAllPermissions() {
        permissionController.checkAllPermissions()
    }

    private fun setupPermissionCardCompose() {
        binding.composeViewPermission.setContent {
            TfgwjTheme {
                val status by permissionManager.permissionStatus.collectAsState()
                replacingViewModel.updatePermissions(status.hasManageStorage, status.hasShizukuPermission)
                PermissionCard(status = status, onRequestPermission = { requestPermissions() }, onManualSelectMode = {
                    com.example.tfgwj.ui.ModeSelectionDialog.show(
                        this@MainActivity,
                        permissionManager,
                        object : com.example.tfgwj.ui.ModeSelectionDialog.Callback {
                            override fun onModeSelected(mode: PermissionChecker.AccessMode) {
                                lifecycleScope.launch { if (permissionManager.manuallySelectMode(mode)) checkEnvironment() }
                            }

                            override fun onRequestShizukuPermission() {
                                permissionManager.requestShizukuPermission()
                            }
                        },
                    )
                })
            }
        }
    }

    private fun setupMainDashboardCompose() {
        binding.composeViewDashboard.setContent {
            TfgwjTheme {
                val uiState by replacingViewModel.uiState.collectAsState()
                val ioStats =
                    PerformanceMonitor.getIOStats().let {
                        if (uiState.speedMBps > 0f) it.copy(avgSpeedMBps = uiState.speedMBps.toDouble()) else it
                    }
                val taskStatus =
                    when {
                        uiState.isReplacing -> com.example.tfgwj.ui.components.atoms.TaskStatus.RUNNING
                        uiState.errorMessage != null -> com.example.tfgwj.ui.components.atoms.TaskStatus.FAILED
                        uiState.phase == com.example.tfgwj.domain.model.TaskPhase.COMPLETED -> com.example.tfgwj.ui.components.atoms.TaskStatus.SUCCESS
                        uiState.isPaused -> com.example.tfgwj.ui.components.atoms.TaskStatus.PAUSED
                        else -> com.example.tfgwj.ui.components.atoms.TaskStatus.IDLE
                    }
                com.example.tfgwj.ui.components.organisms.MainDashboard(
                    ioStats = ioStats,
                    currentStatus = taskStatus,
                    onHistoryClick = { showHistoryDialog() },
                )
            }
        }
    }

    private fun setupTaskOverlayCompose() {
        binding.composeViewTaskOverlay.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                TfgwjTheme {
                    TaskProgressOverlay(
                        viewModel = replacingViewModel,
                        onCancel = {
                            replacingViewModel.handleIntent(ReplacingIntent.CancelReplace)
                        },
                        onRetry = { replacingViewModel.handleIntent(ReplacingIntent.RetryReplace) },
                        onDismiss = { replacingViewModel.handleIntent(ReplacingIntent.DismissTaskResult) },
                    )
                }
            }
        }
        lifecycleScope.launch {
            replacingViewModel.uiState.collectLatest {
                binding.composeViewTaskOverlay.visibility =
                    if (it.isReplacing ||
                        it.phase == com.example.tfgwj.domain.model.TaskPhase.FAILURE ||
                        it.phase == com.example.tfgwj.domain.model.TaskPhase.COMPLETED ||
                        it.phase == com.example.tfgwj.domain.model.TaskPhase.CANCELLED
                    ) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
            }
        }
    }

    private fun setupMainPackCardCompose() {
        binding.composeViewMainPack.setContent {
            TfgwjTheme {
                val state by replacingViewModel.uiState.collectAsState()
                com.example.tfgwj.ui.components.organisms.MainPackCard(
                    state = state,
                    onAppInfoClick = { showAppSelectorDialog() },
                    onCheckEnvironment = { checkEnvironment(forceRefresh = true) },
                    onSelectMainPack = { folderPickerLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)) },
                    onRandomizeTime = { replacingViewModel.handleIntent(ReplacingIntent.RandomizeFileTime) },
                    onStartTimePicker = { showTimePickerDialog() },
                    onLockTime = { lockCurrentTime() },
                    onApplyLockedTime = { applyLockedTime() },
                    onStartReplace = { startReplaceToGame() },
                    onLaunchGame = { launchGame() },
                    onCleanEnvironment = { confirmCleanEnvironment() },
                )
            }
        }
    }

    private fun setupPatchVersionCardCompose() {
        binding.composeViewUpdatePack.setContent {
            TfgwjTheme {
                val state by replacingViewModel.uiState.collectAsState()
                com.example.tfgwj.ui.components.organisms.PatchVersionCard(
                    state = state,
                    onSelectPatch = { replacingViewModel.handleIntent(ReplacingIntent.SelectPatch(it)) },
                    onScanArchives = { scanArchives() },
                    onRefreshPatches = { loadPatchVersions() },
                    onExtractAndUpdate = { scanAndExtractArchive() },
                )
            }
        }
    }

    private fun setupLogConsoleCompose() {
        binding.composeViewLogConsole.setContent {
            TfgwjTheme {
                val state by replacingViewModel.uiState.collectAsState()
                com.example.tfgwj.ui.components.organisms.LogConsole(
                    logs = if (state.logContent.isEmpty()) listOf("等待日志输出...") else state.logContent.split("\n"),
                    logSize = state.logSize,
                    onCopyLogs = { copyLogsToClipboard() },
                    onClearLogs = {
                        replacingViewModel.handleIntent(ReplacingIntent.ClearLogs)
                        AppLogger.clearMemoryLogs()
                    },
                )
            }
        }
    }

    private fun requestPermissions() {
        permissionController.requestPermissions()
    }

    private fun showAppSelectorDialog() {
        lifecycleScope.launch {
            val current = preferencesManager.appPackageName.first()
            val all =
                try {
                    packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                        .filter { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
                        .sortedBy { it.loadLabel(packageManager).toString() }
                        .map { AppInfo(it.packageName, it.loadLabel(packageManager).toString()) }
                } catch (e: Exception) {
                    PermissionChecker.getSupportedAppsList().map { AppInfo(it.packageName, it.displayName) }
                }
            MaterialAlertDialogBuilder(this@MainActivity).setTitle("选择应用").setSingleChoiceItems(
                all.map {
                    "${it.name} (${it.packageName})"
                }.toTypedArray(),
                all.indexOfFirst { it.packageName == current }.coerceAtLeast(0),
            ) { dialog, which ->
                lifecycleScope.launch {
                    preferencesManager.setAppPackageName(all[which].packageName)
                    updateAppInfoDisplay(all[which].packageName)
                    loadMainPacks()
                    delay(500)
                    checkEnvironment()
                }
                dialog.dismiss()
            }.setNegativeButton("取消", null).show()
        }
    }

    private fun updateAppInfoDisplay(packageName: String) {
        lifecycleScope.launch {
            replacingViewModel.updateMainPackInfo(
                replacingViewModel.uiState.value.selectedMainPackPath,
                AppIconHelper.getAppName(this@MainActivity, packageName),
                AppIconHelper.getAppIcon(this@MainActivity, packageName),
                packageName,
            )
        }
    }

    internal fun loadAppIcon() {
        lifecycleScope.launch { updateAppInfoDisplay(preferencesManager.appPackageName.first()) }
    }

    internal fun loadWechatIcon() {
        lifecycleScope.launch {
            AppIconHelper.getWechatIcon(this@MainActivity)?.let { binding.toolbar.menu.findItem(R.id.action_wechat)?.icon = it }
        }
    }

    internal fun loadMainPacks() {
        replacingViewModel.handleIntent(ReplacingIntent.ScanMainPacks)
        lifecycleScope.launch {
            val targetPackage = preferencesManager.appPackageName.first()
            val selectedPath = replacingViewModel.uiState.value.selectedMainPackPath
            if (selectedPath != null) {
                replacingViewModel.updateMainPackInfo(
                    selectedPath,
                    File(selectedPath).name,
                    null,
                    targetPackage,
                )
                return@launch
            }
            mainPackManager.scanMainPacks(targetPackage)
            mainPackManager.mainPacks.value.firstOrNull()?.let {
                replacingViewModel.updateMainPackInfo(it.path, it.name, null, targetPackage)
            }
        }
    }

    internal fun loadLastMainPackPath() {
        lifecycleScope.launch {
            preferencesManager.lastMainPackPath.collectLatest { path ->
                if (path != null && path.isNotEmpty() && File(path).exists()) {
                    replacingViewModel.updateMainPackInfo(
                        path,
                        File(path).name,
                        null,
                        preferencesManager.appPackageName.first(),
                    )
                }
            }
        }
    }

    internal fun loadPatchVersions() {
        replacingViewModel.handleIntent(ReplacingIntent.RefreshPatches)
    }

    private fun handleSelectedFolder(uri: Uri) {
        getPathFromUri(uri)?.let { path ->
            lifecycleScope.launch {
                val targetPackage = preferencesManager.appPackageName.first()
                replacingViewModel.updateMainPackInfo(path, File(path).name, null, targetPackage)
                preferencesManager.saveLastSelectedFolderPath(path)
                preferencesManager.saveLastMainPackPath(path)
                loadPatchVersions()
            }
            AppLogger.action("选择源文件夹", path)
        }
    }

    private fun getPathFromUri(uri: Uri): String? {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
        val split = docId.split(":")
        return if (split.size >= 2) {
            if (split[0] == "primary" || split[0] == "home") {
                "/storage/emulated/0/${split[1]}"
            } else {
                getStoragePathByUuid(split[0])?.let {
                    if (split[1].isNotEmpty()) "$it/${split[1]}" else it
                } ?: "/storage/${split[0]}${if (split[1].isNotEmpty()) "/${split[1]}" else ""}"
            }
        } else {
            "/storage/emulated/0/$docId"
        }
    }

    private fun getStoragePathByUuid(uuid: String): String? =
        try {
            getSystemService(android.os.storage.StorageManager::class.java).storageVolumes.find {
                it.uuid?.equals(uuid, true) == true
            }?.directory?.absolutePath
        } catch (e: Exception) {
            null
        }

    private fun showTimePickerDialog() {
        val path = replacingViewModel.uiState.value.selectedMainPackPath ?: return
        val helper = TimePickerHelper(this, lifecycleScope)
        helper.setOnTimeSelectedListener(
            object : TimePickerHelper.OnTimeSelectedListener {
                override fun onTimeSelected(
                    time: Long,
                    formatted: String,
                ) {
                    MaterialAlertDialogBuilder(
                        this@MainActivity,
                    ).setTitle(
                        "确认修改时间",
                    ).setMessage(
                        "将文件时间修改为: $formatted?",
                    ).setPositiveButton("确定") { _, _ -> helper.applyTimeToFolder(path, time) }.setNegativeButton("取消", null).show()
                }

                override fun onApplyStarted() {}

                override fun onApplyCompleted(
                    count: Int,
                    formatted: String,
                ) {
                    SnackbarManager.show("已修改 $count 个文件")
                }

                override fun onApplyFailed(error: String) {}
            },
        )
        helper.showDateTimePicker(FileTimeModifier.getFileTime(path) ?: System.currentTimeMillis())
    }

    private fun lockCurrentTime() {
        val path = replacingViewModel.uiState.value.selectedMainPackPath ?: return
        lifecycleScope.launch {
            val locked = preferencesManager.getLockedTimeIfEnabled()
            if (locked != null) {
                preferencesManager.unlockTime()
                replacingViewModel.handleIntent(ReplacingIntent.UnlockFileTime)
                SnackbarManager.showSuccess("已解锁时间")
            } else {
                FileTimeModifier.getFileTime(path)?.let {
                    preferencesManager.lockTime(it)
                    replacingViewModel.handleIntent(ReplacingIntent.LockFileTime(it))
                    SnackbarManager.showSuccess("已锁定时间")
                }
            }
        }
    }

    private fun applyLockedTime() {
        val path = replacingViewModel.uiState.value.selectedMainPackPath ?: return
        lifecycleScope.launch {
            preferencesManager.getLockedTimeIfEnabled()?.let { time ->
                MaterialAlertDialogBuilder(
                    this@MainActivity,
                ).setTitle("应用锁定时间").setMessage("将文件时间修改为锁定的时间: ${FileTimeModifier.formatTime(time)}?").setPositiveButton("确定") { _, _ ->
                    lifecycleScope.launch {
                        val (count, _) = FileTimeModifier.setCustomTime(path, time)
                        SnackbarManager.show("已修改 $count 个文件")
                    }
                }.setNegativeButton("取消", null).show()
            }
        }
    }

    private fun copyLogsToClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val logs = replacingViewModel.uiState.value.logContent
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Logs", logs))
        SnackbarManager.show("日志已复制")
    }

    private fun startReplaceToGame() {
        val state = replacingViewModel.uiState.value
        val path = state.selectedMainPackPath ?: return
        lifecycleScope.launch {
            val targetPackage = state.targetPackage
            if (targetPackage.isBlank() ||
                !com.example.tfgwj.worker.orchestrator.PathConstants.isValidPackageName(targetPackage)
            ) {
                SnackbarManager.showError("请先选择有效的目标应用")
                return@launch
            }
            val status = permissionManager.checkAllPermissions()
            if (!status.hasManageStorage) {
                requestPermissions()
            } else if (status.bestMode != PermissionChecker.AccessMode.NONE) {
                replacingViewModel.handleIntent(ReplacingIntent.StartReplace(path, targetPackage))
            } else {
                MaterialAlertDialogBuilder(
                    this@MainActivity,
                ).setTitle(
                    "环境未验证",
                ).setMessage(
                    "强制尝试执行替换？",
                ).setPositiveButton(
                    "强制执行",
                ) { _, _ ->
                    replacingViewModel.handleIntent(
                        ReplacingIntent.StartReplace(path, targetPackage),
                    )
                }.setNegativeButton("去授权", { _, _ -> requestPermissions() }).show()
            }
        }
    }

    private fun launchGame() {
        lifecycleScope.launch {
            val pkg = preferencesManager.appPackageName.first()
            if (pkg.isBlank()) {
                SnackbarManager.showError("请先选择目标应用")
                return@launch
            }
            try {
                packageManager.getLaunchIntentForPackage(pkg)?.let {
                    startActivity(it)
                } ?: SnackbarManager.showError("未找到游戏")
            } catch (e: Exception) {
                SnackbarManager.showError("启动失败")
            }
        }
    }

    private fun confirmCleanEnvironment() {
        MaterialAlertDialogBuilder(this).setTitle("🧹 清理环境确认").setMessage("确定要清理 Saved 目录吗？").setPositiveButton("立即清理") { _, _ ->
            lifecycleScope.launch {
                val pkg = preferencesManager.appPackageName.first()
                if (pkg.isBlank()) {
                    SnackbarManager.showError("请先选择目标应用")
                    return@launch
                }
                SmartCacheManager.cleanEnvironment(this@MainActivity, pkg, shizukuManager) { _, _, _ -> }
                SnackbarManager.showSuccess("清理完成")
            }
        }.setNegativeButton("取消", null).show()
    }

    internal fun checkEnvironment(forceRefresh: Boolean = false) {
        lifecycleScope.launch {
            if (forceRefresh) {
                try {
                    val pkg = preferencesManager.appPackageName.first()
                    if (pkg.isNotBlank()) {
                        Runtime.getRuntime().exec(arrayOf("am", "force-stop", pkg)).waitFor()
                        delay(500)
                    }
                } catch (
                    e: Exception,
                ) {
                }
            }
            replacingViewModel.handleIntent(ReplacingIntent.CheckEnvironment)
        }
    }

    private fun scanArchives() {
        lifecycleScope.launch {
            val archives = ArchiveScanner.getInstance().scanArchives()
            if (archives.isEmpty()) {
                SnackbarManager.showError("未找到压缩包")
            } else {
                MaterialAlertDialogBuilder(this@MainActivity).setTitle("选择压缩包解压").setItems(
                    archives.map {
                        "${it.name} (${it.sizeText})"
                    }.toTypedArray(),
                ) { _, which ->
                    lifecycleScope.launch {
                        if (ExtractManager.getInstance().extractToCache(archives[which].path, null).success) loadPatchVersions()
                    }
                }.setNegativeButton("取消", null).show()
            }
        }
    }

    private fun scanAndExtractArchive() {
        val path = replacingViewModel.uiState.value.selectedMainPackPath ?: return
        lifecycleScope.launch {
            val pkg = preferencesManager.appPackageName.first()
            if (pkg.isBlank() ||
                !com.example.tfgwj.worker.orchestrator.PathConstants.isValidPackageName(pkg)
            ) {
                SnackbarManager.showError("请先选择有效的目标应用")
                return@launch
            }
            val archives = ArchiveScanner.getInstance().scanArchives()
            if (archives.isEmpty()) {
                SnackbarManager.showError("未找到压缩包")
            } else {
                MaterialAlertDialogBuilder(this@MainActivity).setTitle("选择压缩包解压到主包").setItems(
                    archives.map {
                        "${it.name} (${it.sizeText})"
                    }.toTypedArray(),
                ) { _, which ->
                    lifecycleScope.launch {
                        val result =
                            ExtractManager.getInstance().extractToCache(
                                archives[which].path,
                                null,
                                File(archives[which].path).nameWithoutExtension,
                            )
                        if (result.success) {
                            val target = File(mainPackManager.getConfigPath(path, pkg))
                            if (!target.exists()) target.mkdirs()
                            val extractedRoot = File(result.outputPath)
                            val allFiles = extractedRoot.walkTopDown().filter { it.isFile }.toList()
                            val copyResult =
                                IoEngine.parallelProcess(
                                    allFiles,
                                    action = { source ->
                                        val relativePath = source.relativeTo(extractedRoot).path.replace(File.separatorChar, '/')
                                        val destination = ArchiveEntryValidator.resolveWithin(target, relativePath)
                                        destination.parentFile?.mkdirs()
                                        if (IoEngine.needsUpdate(
                                                source,
                                                destination,
                                            )
                                        ) {
                                            IoEngine.fastCopy(source, destination) > 0L
                                        } else {
                                            true
                                        }
                                    },
                                ) { _, _, _ -> }
                            if (copyResult.success) {
                                loadPatchVersions()
                            } else {
                                SnackbarManager.showError("部分更新文件复制失败")
                            }
                        }
                    }
                }.setNegativeButton("取消", null).show()
            }
        }
    }

    internal fun checkForUpdates() {
        lifecycleScope.launch {
            UpdateManager.checkUpdateAsync(this@MainActivity)?.let {
                if (it.isUpdateAvailable) {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("新版本 V${it.latestVersion}")
                        .setMessage(it.releaseNotes)
                        .setPositiveButton("立即更新") { _, _ ->
                            lifecycleScope.launch {
                                UpdateManager.downloadApk(this@MainActivity, it.downloadUrl).collectLatest { progress ->
                                    when {
                                        progress == 100 -> {
                                            val result =
                                                AppInstaller.installApk(
                                                    this@MainActivity,
                                                    File(externalCacheDir, "update_tfgwj_ota.apk"),
                                                )
                                            when (result) {
                                                is AppInstaller.InstallResult.Success ->
                                                    SnackbarManager.showSuccess("已发起安装")
                                                is AppInstaller.InstallResult.Failure ->
                                                    SnackbarManager.showError("安装失败")
                                            }
                                        }
                                        progress == -2 -> SnackbarManager.showError("更新包校验失败，已删除")
                                        progress < 0 -> SnackbarManager.showError("更新下载失败")
                                    }
                                }
                            }
                        }
                        .setNegativeButton("稍后", null)
                        .show()
                }
            }
        }
    }

    private fun openWechat() {
        val id = getString(R.string.author_wechat)
        (
            getSystemService(
                Context.CLIPBOARD_SERVICE,
            ) as android.content.ClipboardManager
        ).setPrimaryClip(android.content.ClipData.newPlainText("微信号", id))
        try {
            packageManager.getLaunchIntentForPackage("com.tencent.mm")?.let { startActivity(it) }
            SnackbarManager.show("微信号已复制")
        } catch (e: Exception) {
        }
    }

    private fun openGithub() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.github_url))))
        } catch (e: Exception) {
        }
    }

    private fun showPhantomStealthDialog() {
        MaterialAlertDialogBuilder(
            this,
        ).setTitle(
            "引爆隐匿",
        ).setMessage("是否立即引爆隐匿程序？").setPositiveButton("立刻隐匿") { _, _ -> StealthManager.execute(this) }.setNegativeButton("取消", null).show()
    }

    /**
     * 全屏展示替换历史记录（基于 HistoryScreen Compose + AlertDialog 容器）
     */
    private fun showHistoryDialog() {
        historyDialogController.show()
    }

    data class AppInfo(val packageName: String, val name: String)

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.close()
    }
}
