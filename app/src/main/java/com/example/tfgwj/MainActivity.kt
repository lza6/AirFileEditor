package com.example.tfgwj

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.tfgwj.data.PreferencesManager
import com.example.tfgwj.databinding.ActivityMainBinding
import com.example.tfgwj.domain.model.EnvironmentStatus
import com.example.tfgwj.domain.model.TaskPhase
import com.example.tfgwj.manager.*
import com.example.tfgwj.performance.PerformanceMonitor
import com.example.tfgwj.shizuku.ShizukuManager
import com.example.tfgwj.ui.FloatingBallManager
import com.example.tfgwj.ui.HelpDialog
import com.example.tfgwj.ui.TimePickerHelper
import com.example.tfgwj.ui.components.atoms.TaskStatus
import com.example.tfgwj.ui.components.molecules.PermissionCard
import com.example.tfgwj.ui.components.organisms.MainDashboard
import com.example.tfgwj.ui.compose.TaskProgressOverlay
import com.example.tfgwj.ui.mvi.*
import com.example.tfgwj.ui.theme.TfgwjTheme
import com.example.tfgwj.utils.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
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

    // 权限请求
    private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) checkAllPermissions()
    }

    private val manageStorageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        checkAllPermissions()
    }

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
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

        androidx.work.WorkManager.getInstance(this).cancelAllWorkByTag("file_replace")

        lifecycleScope.launch {
            ReplaceProgressManager.progressState.collectLatest { state ->
                if (state.total > 0 && state.isReplacing && !floatingBallManager.isShowing()) {
                    floatingBallManager.show()
                } else if (!state.isReplacing && floatingBallManager.isShowing()) {
                    floatingBallManager.hide()
                }
            }
        }

        lifecycleScope.launch {
            loadAppIcon()
            loadWechatIcon()
            loadLastMainPackPath()
            delay(300)
            if (replacingViewModel.uiState.value.selectedMainPackPath == null) loadMainPacks()
            loadPatchVersions()
            delay(1000)
            checkEnvironment()
            delay(2000)
            checkForUpdates()
            RuleEngine.fetchCloudRules()
        }
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
                R.id.action_wechat -> { openWechat(); true }
                R.id.action_github -> { openGithub(); true }
                R.id.action_help -> { HelpDialog.show(this); true }
                R.id.action_stealth -> { showPhantomStealthDialog(); true }
                else -> false
            }
        }
        setupPermissionCardCompose()
        setupMainDashboardCompose()
        setupTaskOverlayCompose()
        setupMainPackCardCompose()
        setupPatchVersionCardCompose()
        setupLogConsoleCompose()
        startLogUpdates()
    }

    private fun startLogUpdates() {
        lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                val logs = AppLogger.getRecentLogs(50)
                replacingViewModel.updateLogContent(if (logs.isEmpty()) "等待日志输出..." else logs.joinToString("\n"), AppLogger.getLogSize())
            }
        }
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            preferencesManager.lockedTimeEnabled.collectLatest { enabled ->
                if (enabled) preferencesManager.lockedTime.collectLatest { replacingViewModel.handleIntent(ReplacingIntent.LockFileTime(it)) }
                else replacingViewModel.handleIntent(ReplacingIntent.UnlockFileTime)
            }
        }
        lifecycleScope.launch {
            shizukuManager.isAuthorized.collectLatest { if (it != permissionManager.permissionStatus.value.hasShizukuPermission) checkAllPermissions() }
        }
    }

    private fun checkAllPermissions() {
        lifecycleScope.launch {
            val status = permissionManager.checkAllPermissions()
            if (status.hasManageStorage) AppLogger.reInitAfterPermission(this@MainActivity)
        }
    }

    private fun setupPermissionCardCompose() {
        binding.composeViewPermission.setContent {
            TfgwjTheme {
                val status by permissionManager.permissionStatus.collectAsState()
                replacingViewModel.updatePermissions(status.hasManageStorage, status.hasShizukuPermission)
                PermissionCard(status = status, onRequestPermission = { requestPermissions() }, onManualSelectMode = {
                    com.example.tfgwj.ui.ModeSelectionDialog.show(this@MainActivity, permissionManager, object : com.example.tfgwj.ui.ModeSelectionDialog.Callback {
                        override fun onModeSelected(mode: PermissionChecker.AccessMode) { lifecycleScope.launch { if (permissionManager.manuallySelectMode(mode)) checkEnvironment() } }
                        override fun onRequestShizukuPermission() { permissionManager.requestShizukuPermission() }
                    })
                })
            }
        }
    }

    private fun setupMainDashboardCompose() {
        binding.composeViewDashboard.setContent {
            TfgwjTheme {
                val uiState by replacingViewModel.uiState.collectAsState()
                val progressState by ReplaceProgressManager.progressState.collectAsState()
                val ioStats = PerformanceMonitor.getIOStats().let { if (progressState.speed > 0f) it.copy(avgSpeedMBps = progressState.speed.toDouble()) else it }
                val taskStatus = when {
                    uiState.isReplacing -> com.example.tfgwj.ui.components.atoms.TaskStatus.RUNNING
                    uiState.errorMessage != null -> com.example.tfgwj.ui.components.atoms.TaskStatus.FAILED
                    uiState.phase == com.example.tfgwj.domain.model.TaskPhase.COMPLETED -> com.example.tfgwj.ui.components.atoms.TaskStatus.SUCCESS
                    uiState.isPaused -> com.example.tfgwj.ui.components.atoms.TaskStatus.PAUSED
                    else -> com.example.tfgwj.ui.components.atoms.TaskStatus.IDLE
                }
                com.example.tfgwj.ui.components.organisms.MainDashboard(
                    ioStats = ioStats,
                    currentStatus = taskStatus
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
                            androidx.work.WorkManager.getInstance(this@MainActivity)
                                .cancelAllWorkByTag("file_replace")
                        }
                    )
                }
            }
        }
        lifecycleScope.launch {
            replacingViewModel.uiState.collectLatest {
                binding.composeViewTaskOverlay.visibility =
                    if (it.isReplacing || it.phase != com.example.tfgwj.domain.model.TaskPhase.IDLE) View.VISIBLE else View.GONE
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
                    onCleanEnvironment = { confirmCleanEnvironment() }
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
                    onExtractAndUpdate = { scanAndExtractArchive() }
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
                    onClearLogs = { replacingViewModel.handleIntent(ReplacingIntent.ClearLogs); AppLogger.clearMemoryLogs() }
                )
            }
        }
    }

    private fun requestPermissions() {
        lifecycleScope.launch {
            val status = permissionManager.checkAllPermissions()
            if (!status.hasManageStorage) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) permissionManager.requestManageStoragePermission(this@MainActivity, manageStorageLauncher)
                else permissionManager.requestStoragePermission(storagePermissionLauncher)
            } else if (status.bestMode == PermissionChecker.AccessMode.SHIZUKU && !status.hasShizukuPermission) {
                permissionManager.requestShizukuPermission { if (it) checkAllPermissions() }
            }
        }
    }

    private fun showAppSelectorDialog() {
        lifecycleScope.launch {
            val current = preferencesManager.appPackageName.first()
            val all = try {
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
                    .sortedBy { it.loadLabel(packageManager).toString() }
                    .map { AppInfo(it.packageName, it.loadLabel(packageManager).toString()) }
            } catch (e: Exception) { PermissionChecker.getSupportedAppsList().map { AppInfo(it.packageName, it.displayName) } }
            MaterialAlertDialogBuilder(this@MainActivity).setTitle("选择应用").setSingleChoiceItems(all.map { "${it.name} (${it.packageName})" }.toTypedArray(), all.indexOfFirst { it.packageName == current }.coerceAtLeast(0)) { dialog, which ->
                lifecycleScope.launch {
                    preferencesManager.setAppPackageName(all[which].packageName)
                    updateAppInfoDisplay(all[which].packageName)
                    loadMainPacks()
                    delay(500); checkEnvironment()
                }
                dialog.dismiss()
            }.setNegativeButton("取消", null).show()
        }
    }

    private fun updateAppInfoDisplay(packageName: String) {
        lifecycleScope.launch { replacingViewModel.updateMainPackInfo(replacingViewModel.uiState.value.selectedMainPackPath, AppIconHelper.getAppName(this@MainActivity, packageName), AppIconHelper.getAppIcon(this@MainActivity, packageName), packageName) }
    }

    private fun loadAppIcon() { lifecycleScope.launch { updateAppInfoDisplay(preferencesManager.appPackageName.first()) } }

    private fun loadWechatIcon() { lifecycleScope.launch { AppIconHelper.getWechatIcon(this@MainActivity)?.let { binding.toolbar.menu.findItem(R.id.action_wechat)?.icon = it } } }

    private fun loadMainPacks() {
        if (replacingViewModel.uiState.value.selectedMainPackPath != null) return
        replacingViewModel.handleIntent(ReplacingIntent.ScanMainPacks)
        lifecycleScope.launch {
            mainPackManager.scanMainPacks()
            mainPackManager.mainPacks.value.firstOrNull()?.let { replacingViewModel.updateMainPackInfo(it.path, it.name, null, "") }
        }
    }

    private fun loadLastMainPackPath() {
        lifecycleScope.launch {
            preferencesManager.lastMainPackPath.collectLatest { path ->
                if (path != null && path.isNotEmpty() && File(path).exists()) replacingViewModel.updateMainPackInfo(path, File(path).name, null, "")
            }
        }
    }

    private fun loadPatchVersions() { replacingViewModel.handleIntent(ReplacingIntent.RefreshPatches) }

    private fun handleSelectedFolder(uri: Uri) {
        getPathFromUri(uri)?.let { path ->
            replacingViewModel.updateMainPackInfo(path, File(path).name, null, "com.tencent.tmgp.pubgmhd")
            lifecycleScope.launch {
                preferencesManager.saveLastSelectedFolderPath(path); preferencesManager.saveLastMainPackPath(path)
                loadPatchVersions()
            }
            AppLogger.action("选择源文件夹", path)
        }
    }

    private fun getPathFromUri(uri: Uri): String? {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
        val split = docId.split(":")
        return if (split.size >= 2) {
            if (split[0] == "primary" || split[0] == "home") "/storage/emulated/0/${split[1]}"
            else getStoragePathByUuid(split[0])?.let { if (split[1].isNotEmpty()) "$it/${split[1]}" else it } ?: "/storage/${split[0]}${if (split[1].isNotEmpty()) "/${split[1]}" else ""}"
        } else "/storage/emulated/0/$docId"
    }

    private fun getStoragePathByUuid(uuid: String): String? = try { getSystemService(android.os.storage.StorageManager::class.java).storageVolumes.find { it.uuid?.equals(uuid, true) == true }?.directory?.absolutePath } catch (e: Exception) { null }

    private fun showTimePickerDialog() {
        val path = replacingViewModel.uiState.value.selectedMainPackPath ?: return
        val helper = TimePickerHelper(this, lifecycleScope)
        helper.setOnTimeSelectedListener(object : TimePickerHelper.OnTimeSelectedListener {
            override fun onTimeSelected(time: Long, formatted: String) { MaterialAlertDialogBuilder(this@MainActivity).setTitle("确认修改时间").setMessage("将文件时间修改为: $formatted?").setPositiveButton("确定") { _, _ -> helper.applyTimeToFolder(path, time) }.setNegativeButton("取消", null).show() }
            override fun onApplyStarted() {}
            override fun onApplyCompleted(count: Int, formatted: String) { Toast.makeText(this@MainActivity, "已修改 $count 个文件", Toast.LENGTH_SHORT).show() }
            override fun onApplyFailed(error: String) {}
        })
        helper.showDateTimePicker(FileTimeModifier.getFileTime(path) ?: System.currentTimeMillis())
    }

    private fun lockCurrentTime() {
        val path = replacingViewModel.uiState.value.selectedMainPackPath ?: return
        lifecycleScope.launch {
            val locked = preferencesManager.getLockedTimeIfEnabled()
            if (locked != null) { preferencesManager.unlockTime(); replacingViewModel.handleIntent(ReplacingIntent.UnlockFileTime); Toast.makeText(this@MainActivity, "🔓 已解锁时间", Toast.LENGTH_SHORT).show() }
            else FileTimeModifier.getFileTime(path)?.let { preferencesManager.lockTime(it); replacingViewModel.handleIntent(ReplacingIntent.LockFileTime(it)); Toast.makeText(this@MainActivity, "✓ 已锁定时间: ${FileTimeModifier.formatTime(it)}", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun applyLockedTime() {
        val path = replacingViewModel.uiState.value.selectedMainPackPath ?: return
        lifecycleScope.launch {
            preferencesManager.getLockedTimeIfEnabled()?.let { time ->
                MaterialAlertDialogBuilder(this@MainActivity).setTitle("应用锁定时间").setMessage("将文件时间修改为锁定的时间: ${FileTimeModifier.formatTime(time)}?").setPositiveButton("确定") { _, _ -> lifecycleScope.launch { val (count, _) = FileTimeModifier.setCustomTime(path, time); Toast.makeText(this@MainActivity, "已修改 $count 个文件", Toast.LENGTH_LONG).show() } }.setNegativeButton("取消", null).show()
            }
        }
    }

    private fun copyLogsToClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val logs = replacingViewModel.uiState.value.logContent
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Logs", logs))
        Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
    }

    private fun startReplaceToGame() {
        val state = replacingViewModel.uiState.value
        val path = state.selectedMainPackPath ?: return
        lifecycleScope.launch {
            val status = permissionManager.checkAllPermissions()
            if (!status.hasManageStorage) requestPermissions()
            else if (status.bestMode != PermissionChecker.AccessMode.NONE) replacingViewModel.handleIntent(ReplacingIntent.StartReplace(path, state.targetPackage))
            else MaterialAlertDialogBuilder(this@MainActivity).setTitle("环境未验证").setMessage("强制尝试执行替换？").setPositiveButton("强制执行") { _, _ -> replacingViewModel.handleIntent(ReplacingIntent.StartReplace(path, state.targetPackage)) }.setNegativeButton("去授权", { _, _ -> requestPermissions() }).show()
        }
    }

    private fun launchGame() { lifecycleScope.launch { val pkg = preferencesManager.appPackageName.first(); try { packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) } ?: Toast.makeText(this@MainActivity, "未找到游戏", Toast.LENGTH_SHORT).show() } catch (e: Exception) { Toast.makeText(this@MainActivity, "启动失败", Toast.LENGTH_SHORT).show() } } }

    private fun confirmCleanEnvironment() {
        MaterialAlertDialogBuilder(this).setTitle("🧹 清理环境确认").setMessage("确定要清理 Saved 目录吗？").setPositiveButton("立即清理") { _, _ ->
            lifecycleScope.launch { val pkg = preferencesManager.appPackageName.first(); SmartCacheManager.cleanEnvironment(this@MainActivity, pkg, shizukuManager) { _, _, _ -> }; Toast.makeText(this@MainActivity, "✅ 清理完成", Toast.LENGTH_LONG).show() }
        }.setNegativeButton("取消", null).show()
    }

    private fun checkEnvironment(forceRefresh: Boolean = false) {
        lifecycleScope.launch {
            if (forceRefresh) {
                try { Runtime.getRuntime().exec(arrayOf("am", "force-stop", preferencesManager.appPackageName.first())).waitFor(); delay(500) } catch (e: Exception) {}
            }
            replacingViewModel.handleIntent(ReplacingIntent.CheckEnvironment)
        }
    }

    private fun scanArchives() { lifecycleScope.launch { val archives = ArchiveScanner.getInstance().scanArchives(); if (archives.isEmpty()) Toast.makeText(this@MainActivity, "未找到压缩包", Toast.LENGTH_SHORT).show() else MaterialAlertDialogBuilder(this@MainActivity).setTitle("选择压缩包解压").setItems(archives.map { "${it.name} (${it.sizeText})" }.toTypedArray()) { _, which -> lifecycleScope.launch { if (ExtractManager.getInstance().extractToCache(archives[which].path, null).success) loadPatchVersions() } }.setNegativeButton("取消", null).show() } }

    private fun scanAndExtractArchive() {
        val path = replacingViewModel.uiState.value.selectedMainPackPath ?: return
        lifecycleScope.launch {
            val archives = ArchiveScanner.getInstance().scanArchives()
            if (archives.isEmpty()) Toast.makeText(this@MainActivity, "未找到压缩包", Toast.LENGTH_SHORT).show()
            else MaterialAlertDialogBuilder(this@MainActivity).setTitle("选择压缩包解压到主包").setItems(archives.map { "${it.name} (${it.sizeText})" }.toTypedArray()) { _, which ->
                lifecycleScope.launch {
                    val result = ExtractManager.getInstance().extractToCache(archives[which].path, null, File(archives[which].path).nameWithoutExtension)
                    if (result.success) {
                        val pkg = preferencesManager.appPackageName.first(); val target = File(PermissionChecker.getAppConfigPath(pkg).replace("/storage/emulated/0/Android/data/", "$path/Android/data/"))
                        if (!target.exists()) target.mkdirs()
                        val allFiles = File(result.outputPath).walkTopDown().filter { it.isFile }.toList()
                        IoOptimizer.parallelProcess(allFiles, action = { if (IoOptimizer.needsUpdate(it, File(target, it.name))) IoOptimizer.fastCopy(it, File(target, it.name)) else true }) { _, _, _ -> }
                        loadPatchVersions()
                    }
                }
            }.setNegativeButton("取消", null).show()
        }
    }

    private fun checkForUpdates() { lifecycleScope.launch { UpdateManager.checkUpdateAsync(this@MainActivity)?.let { if (it.isUpdateAvailable) MaterialAlertDialogBuilder(this@MainActivity).setTitle("新版本 V${it.latestVersion}").setMessage(it.releaseNotes).setPositiveButton("立即更新") { _, _ -> lifecycleScope.launch { UpdateManager.downloadApk(this@MainActivity, it.downloadUrl).collectLatest { if (it == 100) AppInstaller.installApk(this@MainActivity, File(externalCacheDir, "update_tfgwj_ota.apk")) } } }.setNegativeButton("稍后", null).show() } } }

    private fun openWechat() { val id = getString(R.string.author_wechat); (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("微信号", id)); try { packageManager.getLaunchIntentForPackage("com.tencent.mm")?.let { startActivity(it) }; Toast.makeText(this, "微信号已复制", Toast.LENGTH_LONG).show() } catch (e: Exception) {} }

    private fun openGithub() { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.github_url)))) } catch (e: Exception) {} }

    private fun showPhantomStealthDialog() { MaterialAlertDialogBuilder(this).setTitle("引爆隐匿").setMessage("是否立即引爆隐匿程序？").setPositiveButton("立刻隐匿") { _, _ -> StealthManager.execute(this) }.setNegativeButton("取消", null).show() }

    data class AppInfo(val packageName: String, val name: String)

    override fun onDestroy() { super.onDestroy() ; AppLogger.close() }
}
