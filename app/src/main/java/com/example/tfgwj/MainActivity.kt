package com.example.tfgwj

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tfgwj.adapter.PatchVersionAdapter
import com.example.tfgwj.data.PreferencesManager
import com.example.tfgwj.databinding.ActivityMainBinding
import com.example.tfgwj.manager.*
import com.example.tfgwj.shizuku.ShizukuManager
import com.example.tfgwj.ui.HelpDialog
import com.example.tfgwj.ui.TimePickerHelper
import com.example.tfgwj.utils.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Semaphore
import rikka.shizuku.Shizuku
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
    private lateinit var archiveScanner: ArchiveScanner
    private lateinit var permissionManager: PermissionManager
    private lateinit var floatingBallManager: com.example.tfgwj.ui.FloatingBallManager
    
    private lateinit var patchAdapter: PatchVersionAdapter
    
    private var selectedMainPackPath: String? = null
    private var isReplacing = false  // 防止重复替换任务
    private var lockedTime: Long? = null  // 锁定的时间
    private var lastLogContent = ""  // 缓存上次的日志内容
    private var currentWorkId: String? = null  // 当前工作任务的 ID

    // 权限请求
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            checkAllPermissions()
        }
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkAllPermissions()
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "folderPickerLauncher 回调，resultCode: ${result.resultCode}")
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                Log.d(TAG, "选择的 URI: $uri")
                handleSelectedFolder(uri)
            } ?: run {
                Log.e(TAG, "URI 为空")
            }
        } else {
            Log.d(TAG, "用户取消了选择")
        }
    }

    private val extractAndUpdateLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            handleExtractAndUpdate(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化日志（优先外部存储，若无权限则使用私有目录存档）
        AppLogger.init(this)
        AppLogger.action("应用启动")

        // 创建通知渠道（用于 WorkManager 前台服务）
        createNotificationChannel()

        initManagers()
        initViews()
        setupObservers()
        checkAllPermissions()
        
        // 初始化悬浮球管理器
        floatingBallManager = com.example.tfgwj.ui.FloatingBallManager(applicationContext)
        
        // 取消之前未完成的替换任务，防止冷启动时自动恢复执行
        androidx.work.WorkManager.getInstance(this).cancelAllWorkByTag("file_replace")
        
        // 初始加载
        lifecycleScope.launch {
            loadAppIcon()
            loadWechatIcon() // 动态加载微信图标
            
            // 优先加载上次选择的主包路径
            loadLastMainPackPath() 
            
            // 延迟一点确保 preferences 加载完成，如果没恢复成功再执行自动扫描
            delay(300)
            if (selectedMainPackPath == null) {
                loadMainPacks()
            }
            
            loadPatchVersions()
            
            // 延迟 1 秒后自动验证环境
            kotlinx.coroutines.delay(1000)
            checkEnvironment()
        }
    }

    override fun onResume() {
        super.onResume()
        // 自动检查权限并更新 Shizuku 状态
        lifecycleScope.launch {
            val status = permissionManager.checkAllPermissions()
            permissionManager.updateShizukuStatus()
            if (!status.hasManageStorage) {
                Log.d(TAG, "未获得管理存储权限，自动请求...")
                requestPermissions()
            }
        }
    }


    private fun initManagers() {
        preferencesManager = PreferencesManager(applicationContext)
        shizukuManager = ShizukuManager.getInstance(applicationContext)
        patchManager = PatchManager.getInstance()
        mainPackManager = MainPackManager.getInstance()
        archiveScanner = ArchiveScanner.getInstance()
        permissionManager = PermissionManager(applicationContext)
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "file_replace_channel",
                "文件替换通知",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示文件替换进度"
            }
            
            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun initViews() {
        // 菜单按钮
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_wechat -> {
                    findViewById<View>(R.id.action_wechat)?.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    AppLogger.buttonClick("微信")
                    openWechat()
                    true
                }
                R.id.action_github -> {
                    AppLogger.buttonClick("GitHub")
                    openGithub()
                    true
                }
                R.id.action_help -> {
                    AppLogger.buttonClick("帮助")
                    HelpDialog.show(this)
                    true
                }
                else -> false
            }
        }

        // 结果卡片重试按钮
        binding.btnRetry.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            AppLogger.buttonClick("重试")
            lastFailedPath?.let { path ->
                AppLogger.action("点击重试", path)
                startReplaceToGame()
            }
        }

        // 权限卡片
        binding.btnRequestPermission.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            AppLogger.buttonClick("授权权限")
            requestPermissions()
        }

        // 主包区域
        val mainPackCard = binding.includeMainPack.root
        
        // 应用信息点击 - 切换应用
        mainPackCard.findViewById<LinearLayout>(R.id.layout_app_info).setOnClickListener {
            AppLogger.buttonClick("切换应用")
            showAppSelectorDialog()
        }
        
        // 环境验证按钮
        mainPackCard.findViewById<MaterialButton>(R.id.btn_check_env).setOnClickListener {
            AppLogger.buttonClick("验证环境")
            checkEnvironment(forceRefresh = true)
        }
        
        mainPackCard.findViewById<MaterialButton>(R.id.btn_select_main_pack).setOnClickListener {
            AppLogger.buttonClick("选择源文件夹")
            selectMainPackFolder()
        }
        mainPackCard.findViewById<MaterialButton>(R.id.btn_random_time).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            AppLogger.buttonClick("一键随机时间")
            randomizeFileTime()
        }
        // 时间框点击 - 显示时间选择器
        mainPackCard.findViewById<LinearLayout>(R.id.layout_file_time).setOnClickListener {
            AppLogger.buttonClick("选择时间")
            showTimePickerDialog()
        }
        // 锁定时间按钮
        mainPackCard.findViewById<ImageButton>(R.id.btn_lock_time).setOnClickListener {
            AppLogger.buttonClick("锁定时间")
            lockCurrentTime()
        }
        // 应用锁定时间按钮
        mainPackCard.findViewById<ImageButton>(R.id.btn_apply_locked_time).setOnClickListener {
            AppLogger.buttonClick("应用锁定时间")
            applyLockedTime()
        }
        mainPackCard.findViewById<MaterialButton>(R.id.btn_start_replace_main).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            AppLogger.buttonClick("开始替换到游戏")
            startReplaceToGame()
        }
        
        // 【新增】一键启动游戏
        mainPackCard.findViewById<MaterialButton>(R.id.btn_launch_game).setOnClickListener {
            AppLogger.buttonClick("一键启动游戏")
            launchGame()
        }

        mainPackCard.findViewById<MaterialButton>(R.id.btn_clean_env).setOnClickListener {
            AppLogger.buttonClick("清理环境")
            confirmCleanEnvironment()
        }

        // 手动选择模式按钮
        binding.btnManualMode.setOnClickListener {
            AppLogger.buttonClick("手动选择模式")
            com.example.tfgwj.ui.ModeSelectionDialog.show(
                this,
                permissionManager,
                object : com.example.tfgwj.ui.ModeSelectionDialog.Callback {
                    override fun onModeSelected(mode: PermissionChecker.AccessMode) {
                        lifecycleScope.launch {
                            val success = permissionManager.manuallySelectMode(mode)
                            if (success) {
                                AppLogger.action("手动选择模式成功", mode.name)
                                checkEnvironment() // 验证成功后重新扫描环境
                            } else {
                                AppLogger.action("手动选择模式失败", mode.name)
                            }
                        }
                    }

                    override fun onRequestShizukuPermission() {
                        permissionManager.requestShizukuPermission()
                    }
                }
            )
        }

        // 更新主包区域
        val updatePackCard = binding.includeUpdatePack.root
        
        // 小包列表适配器
        patchAdapter = PatchVersionAdapter(
            onItemClick = { patch -> showPatchPreview(patch) },
            onDeleteClick = { patch -> confirmDeletePatch(patch) }
        )
        updatePackCard.findViewById<RecyclerView>(R.id.rv_patch_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = patchAdapter
        }

        updatePackCard.findViewById<MaterialButton>(R.id.btn_scan_archives).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            AppLogger.buttonClick("扫描压缩包")
            scanArchives()
        }
        updatePackCard.findViewById<MaterialButton>(R.id.btn_refresh_patches).setOnClickListener {
            AppLogger.buttonClick("刷新小包列表")
            loadPatchVersions()
        }
        
        // 解压并更新到主包
        updatePackCard.findViewById<MaterialButton>(R.id.btn_extract_and_update).setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            AppLogger.buttonClick("解压并更新")
            scanAndExtractArchive()
        }

        // 进度卡片
        binding.btnCancelReplace.setOnClickListener {
            AppLogger.buttonClick("取消替换")
            androidx.work.WorkManager.getInstance(this).cancelAllWorkByTag("file_replace")
        }

        // 日志卡片 - 一键复制按钮
        binding.btnCopyLogs.setOnClickListener {
            AppLogger.buttonClick("复制日志")
            copyLogsToClipboard()
        }

        // 启动日志实时更新
        startLogUpdates()
    }

    /**
     * 启动日志实时更新
     */
    private fun startLogUpdates() {
        lifecycleScope.launch {
            while (isActive) {
                delay(1000) // 每秒更新一次
                updateLogDisplay()
            }
        }
    }

    /**
     * 更新日志显示
     */
    private fun updateLogDisplay() {
        try {
            val logs = AppLogger.getRecentLogs(50) // 显示最近 50 条日志
            val newContent = if (logs.isEmpty()) {
                "等待日志输出..."
            } else {
                logs.joinToString("\n")
            }
            
            // 只有当日志内容变化时才更新，避免频繁布局
            if (newContent != lastLogContent) {
                lastLogContent = newContent
                binding.tvLogContent.text = newContent
                
                // 更新日志大小显示
                binding.tvLogSize.text = AppLogger.getLogSize()
                
                // 只在用户已经在底部时才自动滚动
                binding.tvLogContent.post {
                    val scrollView = binding.tvLogContent.parent as? android.widget.ScrollView
                    if (scrollView != null) {
                        val isAtBottom = scrollView.getChildAt(0).bottom - (scrollView.height + scrollView.scrollY) <= 10
                        if (isAtBottom) {
                            scrollView.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略错误
        }
    }

    /**
     * 复制日志到剪贴板
     */
    private fun copyLogsToClipboard() {
        try {
            val fullLogs = AppLogger.getLogContent()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("听风改文件日志", fullLogs)
            clipboard.setPrimaryClip(clip)
            
            Toast.makeText(this, "✅ 日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
            AppLogger.action("日志复制成功", "共 ${fullLogs.lines().size} 行")
        } catch (e: Exception) {
            Toast.makeText(this, "❌ 复制失败: ${e.message}", Toast.LENGTH_SHORT).show()
            AppLogger.e("MainActivity", "复制日志失败", e)
        }
    }


    private fun setupObservers() {
        // 观察权限状态变更
        lifecycleScope.launch {
            permissionManager.permissionStatus.collectLatest { status ->
                updatePermissionUI(status)
            }
        }

        // 替换进度 - 已移除主界面进度显示，现在只在对话框中显示
        // lifecycleScope.launch {
        //     fileReplaceManager.replaceResult.collectLatest { result ->
        //         if (result.totalFiles > 0) {
        //             updateProgressUI(result)
        //         }
        //     }
        // }

        // 小包列表
        lifecycleScope.launch {
            patchManager.patchVersions.collectLatest { versions ->
                patchAdapter.submitList(versions)
                
                val updateCard = binding.includeUpdatePack.root
                val emptyView = updateCard.findViewById<TextView>(R.id.tv_empty_patch)
                val recyclerView = updateCard.findViewById<RecyclerView>(R.id.rv_patch_list)
                
                if (versions.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    emptyView.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                }
            }
        }

        // 压缩包扫描状态
        lifecycleScope.launch {
            archiveScanner.isScanning.collectLatest { isScanning ->
                val updateCard = binding.includeUpdatePack.root
                val scanLayout = updateCard.findViewById<View>(R.id.layout_archive_scan)
                scanLayout.visibility = if (isScanning) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            archiveScanner.scanProgress.collectLatest { progress ->
                val updateCard = binding.includeUpdatePack.root
                val progressBar = updateCard.findViewById<LinearProgressIndicator>(R.id.progress_archive_scan)
                progressBar.progress = (progress * 100).toInt()
            }
        }

        lifecycleScope.launch {
            archiveScanner.scanStatus.collectLatest { status ->
                val updateCard = binding.includeUpdatePack.root
                val statusText = updateCard.findViewById<TextView>(R.id.tv_archive_scan_status)
                statusText.text = status
            }
        }

        // 观察 Shizuku 状态变更
        lifecycleScope.launch {
            shizukuManager.isAuthorized.collectLatest { authorized ->
                Log.d(TAG, "Shizuku 授权状态变更: $authorized")
                // 仅在必要时检查权限
                val status = permissionManager.permissionStatus.value
                if (authorized != status.hasShizukuPermission) {
                    checkAllPermissions()
                }
                // 授权成功后自动验证环境
                if (authorized && shizukuManager.isServiceConnected.value) {
                    checkEnvironment()
                }
            }
        }
        lifecycleScope.launch {
            shizukuManager.isServiceConnected.collectLatest { connected ->
                Log.d(TAG, "Shizuku 服务连接状态变更: $connected")
                val status = permissionManager.permissionStatus.value
                if (connected != status.isShizukuServiceConnected) {
                    checkAllPermissions()
                }
                // 服务连接成功后自动验证环境
                if (connected && shizukuManager.isAuthorized.value) {
                    checkEnvironment()
                }
            }
        }
        
        // 观察锁定时间状态
        lifecycleScope.launch {
            preferencesManager.lockedTimeEnabled.collectLatest { enabled ->
                if (enabled) {
                    preferencesManager.lockedTime.collectLatest { time ->
                        lockedTime = time
                        updateLockButtonState(true)
                    }
                } else {
                    lockedTime = null
                    updateLockButtonState(false)
                }
            }
        }
    }

    private fun checkAllPermissions() {
        lifecycleScope.launch {
            val status = permissionManager.checkAllPermissions()
            updatePermissionUI(status)
            
            // 如果已获得存储管理权限，刷新日志到外部存储
            if (status.hasManageStorage) {
                AppLogger.reInitAfterPermission(this@MainActivity)
            }
        }
    }

    private fun updatePermissionUI(status: PermissionManager.PermissionStatus) {
        // 如果是 Shizuku 服务连接中，添加粗体红色提示
        val message = if (status.statusMessage == "Shizuku 服务连接中...") {
            "Shizuku 服务连接中...<br><br><b><font color=\"#FF0000\">如果一直在连接中请重启 Shizuku，授权管理那边关掉咱们的软件的授权，接着重新打开软件重新获取授权即可。</font></b>"
        } else {
            status.statusMessage
        }
        binding.tvPermissionStatus.text = android.text.Html.fromHtml(message, android.text.Html.FROM_HTML_MODE_LEGACY)
        
        // 优先检查可用模式
        val icon = when (status.bestMode) {
            PermissionChecker.AccessMode.ROOT -> R.drawable.ic_status_success
            PermissionChecker.AccessMode.NATIVE -> R.drawable.ic_status_success
            PermissionChecker.AccessMode.SHIZUKU -> R.drawable.ic_status_success
            else -> if (status.hasManageStorage) R.drawable.ic_status_unknown else R.drawable.ic_status_error
        }
        binding.ivPermissionStatus.setImageResource(icon)

        // Root 设备或有权限访问的设备不需要显示授权按钮
        binding.btnRequestPermission.visibility = when {
            status.hasRoot -> View.GONE  // Root 设备不显示授权按钮
            status.canAccessPrivateDir -> View.GONE  // 可以直接访问，不显示授权按钮
            !status.hasManageStorage -> View.VISIBLE
            status.hasManageStorage && status.availableModes.contains(PermissionChecker.AccessMode.SHIZUKU) && !status.hasShizukuPermission -> View.VISIBLE
            // 如果有了全量权限但拒绝了 Shizuku，也不必强求显示授权按钮，让用户尝试“开始替换”即可
            status.hasManageStorage -> View.GONE 
            else -> View.GONE
        }

        binding.btnRequestPermission.text = when {
            !status.hasManageStorage -> "授权存储权限"
            status.availableModes.contains(PermissionChecker.AccessMode.SHIZUKU) && !status.isShizukuAvailable -> "安装 Shizuku"
            status.availableModes.contains(PermissionChecker.AccessMode.SHIZUKU) && !status.hasShizukuPermission -> "授权 Shizuku"
            else -> "授权"
        }

        // 更新上次选择模式显示
        val lastModeText = when (status.lastSelectedMode) {
            PermissionChecker.AccessMode.ROOT -> "上次使用: Root 模式"
            PermissionChecker.AccessMode.SHIZUKU -> "上次使用: Shizuku 模式"
            PermissionChecker.AccessMode.NATIVE -> "上次使用: 普通模式"
            else -> "推荐使用 Omni-Mode 智能检测"
        }
        binding.tvLastMode.text = lastModeText
    }

    private fun requestPermissions() {
        lifecycleScope.launch {
            val status = permissionManager.checkAllPermissions()
            
            when {
                !status.hasManageStorage -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        permissionManager.requestManageStoragePermission(this@MainActivity, manageStorageLauncher)
                    } else {
                        // Android < 11 直接请求存储权限
                        permissionManager.requestStoragePermission(storagePermissionLauncher)
                    }
                }
                status.bestMode == PermissionChecker.AccessMode.SHIZUKU || 
                (status.availableModes.isEmpty() && Build.VERSION.SDK_INT >= 30) -> {
                    // 如果最佳模式是 Shizuku，或者环境受限且没其他路，则请求 Shizuku
                    if (!status.isShizukuAvailable) {
                        Toast.makeText(this@MainActivity, "检测到环境受限，请先安装/启动 Shizuku", Toast.LENGTH_LONG).show()
                    } else if (!status.hasShizukuPermission) {
                        permissionManager.requestShizukuPermission { granted ->
                            if (granted) checkAllPermissions()
                        }
                    }
                }
            }
        }
    }

    private fun loadAppIcon() {
        lifecycleScope.launch {
            // 获取当前选择的应用包名
            val packageName = preferencesManager.appPackageName.first()
            updateAppInfoDisplay(packageName)
        }
    }
    
    /**
     * 更新应用信息显示
     */
    private fun updateAppInfoDisplay(packageName: String) {
        lifecycleScope.launch {
            val icon = AppIconHelper.getAppIcon(this@MainActivity, packageName)
            val name = AppIconHelper.getAppName(this@MainActivity, packageName)
            
            val mainPackCard = binding.includeMainPack.root
            val iconView = mainPackCard.findViewById<ImageView>(R.id.iv_pubg_icon)
            val nameView = mainPackCard.findViewById<TextView>(R.id.tv_pubg_name)
            val packageView = mainPackCard.findViewById<TextView>(R.id.tv_pubg_package)
            
            if (icon != null) {
                iconView.setImageDrawable(icon)
            }
            nameView.text = name
            packageView.text = packageName
        }
    }
    
    /**
     * 显示应用选择对话框
     */
    private fun showAppSelectorDialog() {
        lifecycleScope.launch {
            // 获取当前选择的应用包名
            val currentPackageName = preferencesManager.appPackageName.first()
            
            // 动态获取所有已安装的应用
            val allApps = try {
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 } // 过滤系统应用
                    .sortedBy { it.loadLabel(packageManager).toString() }
                    .map { appInfo ->
                        val packageName = appInfo.packageName
                        val appName = try {
                            appInfo.loadLabel(packageManager).toString()
                        } catch (e: Exception) {
                            packageName
                        }
                        AppInfo(packageName, appName)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "获取应用列表失败", e)
                // 如果失败，使用预定义的应用列表
                PermissionChecker.getSupportedAppsList().map { AppInfo(it.packageName, it.displayName) }
            }
            
            // 添加预定义的应用（如果不在列表中）
            val predefinedApps = PermissionChecker.getSupportedAppsList().map { it.packageName }
            val mergedApps = allApps + PermissionChecker.getSupportedAppsList()
                .filter { predefined -> allApps.none { it.packageName == predefined.packageName } }
                .map { AppInfo(it.packageName, it.displayName) }
            
            val appNames = mergedApps.map { "${it.name} (${it.packageName})" }.toTypedArray()
            
            var selectedIndex = mergedApps.indexOfFirst { it.packageName == currentPackageName }
            if (selectedIndex < 0) selectedIndex = 0
            
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("选择应用")
                .setSingleChoiceItems(appNames, selectedIndex) { dialog, which ->
                    val selectedApp = mergedApps[which]
                    
                    // 保存选择的应用包名（协程上下文）
                    lifecycleScope.launch {
                        preferencesManager.setAppPackageName(selectedApp.packageName)
                        updateAppInfoDisplay(selectedApp.packageName)
                        
                        // 重新扫描主包
                        loadMainPacks()
                        
                        // 延迟 500ms 后自动验证环境
                        kotlinx.coroutines.delay(500)
                        checkEnvironment()
                        
                        AppLogger.action("切换应用", selectedApp.packageName)
                        Toast.makeText(this@MainActivity, "已切换到 ${selectedApp.name}", Toast.LENGTH_SHORT).show()
                    }
                    
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }
    
    /**
     * 应用信息数据类
     */
    data class AppInfo(val packageName: String, val name: String)

    /**
     * 动态加载微信图标 (基于 QUERY_ALL_PACKAGES 权限)
     */
    private fun loadWechatIcon() {
        lifecycleScope.launch {
            val wechatIcon = AppIconHelper.getWechatIcon(this@MainActivity)
            if (wechatIcon != null) {
                // 查找到菜单中的微信项并设置图标
                binding.toolbar.menu.findItem(R.id.action_wechat)?.icon = wechatIcon
                Log.d("MainActivity", "已动态获取微信系统图标")
            }
        }
    }

    private fun loadMainPacks() {
        // 如果已经选择了主包（通过恢复或手动选择），不再重复扫描覆盖
        if (selectedMainPackPath != null) return
        
        lifecycleScope.launch {
            mainPackManager.scanMainPacks()
            
            val packs = mainPackManager.mainPacks.value
            if (packs.isNotEmpty() && selectedMainPackPath == null) {
                // 选择第一个主包
                val pack = packs.first()
                updateMainPackUI(pack)
            }
        }
    }

    private fun updateMainPackUI(pack: MainPackManager.MainPackInfo?) {
        val mainPackCard = binding.includeMainPack.root
        val selectedText = mainPackCard.findViewById<TextView>(R.id.tv_selected_main_pack)
        val infoLayout = mainPackCard.findViewById<View>(R.id.layout_main_pack_info)
        val sizeText = mainPackCard.findViewById<TextView>(R.id.tv_main_pack_size)
        val timeText = mainPackCard.findViewById<TextView>(R.id.tv_main_pack_time)
        
        if (pack != null) {
            selectedMainPackPath = pack.path
            selectedText.text = pack.name
            infoLayout.visibility = View.VISIBLE
            sizeText.text = pack.sizeText
            
            val fileTime = FileTimeModifier.getFileTime(pack.path)
            if (fileTime != null) {
                timeText.text = FileTimeModifier.formatTime(fileTime)
            }
            
            // 更新当前文件时间显示
            val currentTimeText = mainPackCard.findViewById<TextView>(R.id.tv_current_file_time)
            if (fileTime != null) {
                currentTimeText.text = "当前时间: ${FileTimeModifier.formatTime(fileTime)}"
            }
        } else {
            selectedText.text = "未选择主包"
            infoLayout.visibility = View.GONE
        }
    }

    /**
     * 加载上次选择的主包路径
     */
    private fun loadLastMainPackPath() {
        lifecycleScope.launch {
            preferencesManager.lastMainPackPath.collectLatest { path ->
                if (path != null && path.isNotEmpty()) {
                    val file = File(path)
                    if (file.exists() && file.isDirectory) {
                        selectedMainPackPath = path
                        
                        val mainPackCard = binding.includeMainPack.root
                        mainPackCard.findViewById<TextView>(R.id.tv_selected_main_pack).text = path
                        
                        // 显示文件信息
                        val fileTime = FileTimeModifier.getFileTime(path)
                        if (fileTime != null) {
                            val timeStr = FileTimeModifier.formatTime(fileTime)
                            val currentTimeText = mainPackCard.findViewById<TextView>(R.id.tv_current_file_time)
                            currentTimeText.text = "当前时间: $timeStr"
                            mainPackCard.findViewById<TextView>(R.id.tv_main_pack_time)?.text = timeStr
                        }
                        
                        // 显示大小信息
                        val sizeText = mainPackCard.findViewById<TextView>(R.id.tv_main_pack_size)
                        val infoLayout = mainPackCard.findViewById<View>(R.id.layout_main_pack_info)
                        sizeText.text = formatSize(getDirectorySize(file))
                        infoLayout.visibility = View.VISIBLE
                        
                        Log.d(TAG, "已恢复上次选择的主包: $path")
                    }
                }
            }
        }
    }
    
    /**
     * 获取目录大小
     */
    private fun getDirectorySize(dir: File): Long {
        var size = 0L
        try {
            dir.walkTopDown().maxDepth(3).forEach { file ->
                if (file.isFile) {
                    size += file.length()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取目录大小失败", e)
        }
        return size
    }

    private fun loadPatchVersions() {
        lifecycleScope.launch {
            val updateCard = binding.includeUpdatePack.root
            val scanLayout = updateCard.findViewById<View>(R.id.layout_scan_status)
            scanLayout.visibility = View.VISIBLE
            
            patchManager.scanPatchVersions()
            
            scanLayout.visibility = View.GONE
        }
    }

    private fun selectMainPackFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        folderPickerLauncher.launch(intent)
    }

    private fun handleSelectedFolder(uri: Uri) {
        Log.d(TAG, "handleSelectedFolder 被调用，URI: $uri")
        val path = getPathFromUri(uri)
        Log.d(TAG, "解析出的路径: $path")
        
        if (path != null) {
            selectedMainPackPath = path
            Log.d(TAG, "设置 selectedMainPackPath: $path")
            
            val mainPackCard = binding.includeMainPack.root
            val selectedText = mainPackCard.findViewById<TextView>(R.id.tv_selected_main_pack)
            val infoLayout = mainPackCard.findViewById<View>(R.id.layout_main_pack_info)
            val sizeText = mainPackCard.findViewById<TextView>(R.id.tv_main_pack_size)
            val timeText = mainPackCard.findViewById<TextView>(R.id.tv_main_pack_time)
            
            // 显示文件夹名称
            val file = File(path)
            selectedText.text = file.name
            Log.d(TAG, "设置文件夹名称: ${file.name}")
            
            // 显示大小和时间信息
            val size = getDirectorySize(file)
            sizeText.text = formatSize(size)
            infoLayout.visibility = View.VISIBLE
            Log.d(TAG, "文件夹大小: $size, 显示: ${formatSize(size)}")
            
            // 显示文件夹时间
            val fileTime = FileTimeModifier.getFileTime(path)
            if (fileTime != null) {
                val timeStr = FileTimeModifier.formatTime(fileTime)
                timeText.text = timeStr
                mainPackCard.findViewById<TextView>(R.id.tv_current_file_time).text = "当前时间: $timeStr"
                Log.d(TAG, "文件夹时间: $timeStr")
            }
            
            // 保存路径（主包路径和文件夹路径都保存）
            lifecycleScope.launch {
                preferencesManager.saveLastSelectedFolderPath(path)
                preferencesManager.saveLastMainPackPath(path)
                Log.d(TAG, "已保存路径到 preferences")
                
                // 立即刷新 UI 和状态
                loadLastMainPackPath()
                loadPatchVersions()
            }
            
            AppLogger.action("选择源文件夹", path)
            Log.d(TAG, "已记录选择源文件夹日志")
        } else {
            Log.e(TAG, "路径为 null，无法处理")
            Toast.makeText(this, "无法获取文件夹路径，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getPathFromUri(uri: Uri): String? {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
        Log.d(TAG, "docId: $docId")
        
        val split = docId.split(":")
        Log.d(TAG, "docId split: $split, size: ${split.size}")
        
        val result = if (split.size >= 2) {
            when (split[0]) {
                "primary" -> "/storage/emulated/0/${split[1]}"
                "home" -> "/storage/emulated/0/${split[1]}"
                "msd" -> {
                    // SD 卡或外部存储
                    val path = getExternalStoragePath(split[1])
                    if (path != null) {
                        if (split[1] == "24" || split[1] == "0") {
                            path
                        } else {
                            "$path/${split[1]}"
                        }
                    } else {
                        val fallbackPaths = listOf(
                            "/storage/sdcard",
                            "/storage/emulated/0",
                            "/mnt/sdcard"
                        )
                        for (fallbackPath in fallbackPaths) {
                            val dir = java.io.File(fallbackPath)
                            if (dir.exists() && dir.isDirectory) {
                                Log.d(TAG, "使用备用路径: $fallbackPath")
                                return fallbackPath
                            }
                        }
                        null
                    }
                }
                else -> {
                    // 处理 UUID 类型（如 0000-0000）
                    val volumeId = split[0]
                    val subPath = if (split.size > 1) split[1] else ""
                    
                    // 尝试使用 StorageManager 获取真实路径
                    val storagePath = getStoragePathByUuid(volumeId)
                    if (storagePath != null) {
                        if (subPath.isNotEmpty()) {
                            "$storagePath/$subPath"
                        } else {
                            storagePath
                        }
                    } else {
                        // 备用方案：尝试直接构造路径
                        val candidatePath = "/storage/$volumeId${if (subPath.isNotEmpty()) "/$subPath" else ""}"
                        val dir = java.io.File(candidatePath)
                        if (dir.exists()) {
                            Log.d(TAG, "找到 UUID 路径: $candidatePath")
                            candidatePath
                        } else {
                            Log.w(TAG, "未知的存储类型: $volumeId")
                            null
                        }
                    }
                }
            }
        } else {
            // 如果没有冒号，直接使用 docId
            "/storage/emulated/0/$docId"
        }
        
        Log.d(TAG, "getPathFromUri 结果: $result")
        return result
    }
    
    /**
     * 通过 UUID 获取存储路径
     */
    private fun getStoragePathByUuid(uuid: String): String? {
        return try {
            val storageManager = getSystemService(android.os.storage.StorageManager::class.java)
            val volumes = storageManager.storageVolumes
            
            for (volume in volumes) {
                // 检查 UUID
                val volumeUuid = try {
                    volume.uuid
                } catch (e: Exception) {
                    null
                }
                
                if (volumeUuid == uuid || volumeUuid?.lowercase() == uuid.lowercase()) {
                    // 获取路径
                    val volumePath = try {
                        volume.directory?.absolutePath
                    } catch (e: Exception) {
                        null
                    }
                    
                    if (volumePath != null) {
                        Log.d(TAG, "通过 UUID 找到路径: $volumePath")
                        return volumePath
                    }
                }
            }
            
            Log.w(TAG, "未找到 UUID 为 $uuid 的存储卷")
            null
        } catch (e: Exception) {
            Log.e(TAG, "通过 UUID 获取存储路径失败", e)
            null
        }
    }
    
    /**
     * 获取外部存储路径（SD 卡）
     */
    private fun getExternalStoragePath(volumeId: String): String? {
        return try {
            val volumes = android.os.storage.StorageManager::class.java.getMethod("getVolumeList")
                .invoke(getSystemService(android.os.storage.StorageManager::class.java)) as Array<*>
            
            for (volume in volumes) {
                val uuid = volume?.javaClass?.getMethod("getUuid")?.invoke(volume) as? String
                if (uuid == volumeId || uuid == volumeId.lowercase()) {
                    val path = volume.javaClass.getMethod("getPath").invoke(volume) as? String
                    if (path != null) {
                        Log.d(TAG, "找到 SD 卡路径: $path")
                        return path
                    }
                }
            }
            
            // 如果通过 StorageManager 找不到，尝试常见路径
            val commonPaths = listOf(
                "/storage/sdcard",
                "/storage/sdcard1",
                "/storage/external_sd",
                "/mnt/sdcard",
                "/mnt/extSdCard",
                "/sdcard"
            )
            
            for (path in commonPaths) {
                val dir = java.io.File(path)
                if (dir.exists() && dir.isDirectory) {
                    Log.d(TAG, "找到常见 SD 卡路径: $path")
                    return path
                }
            }
            
            Log.w(TAG, "无法找到 SD 卡路径")
            null
        } catch (e: Exception) {
            Log.e(TAG, "获取 SD 卡路径失败", e)
            null
        }
    }

    private fun randomizeFileTime() {
        val path = selectedMainPackPath
        if (path == null) {
            Toast.makeText(this, "请先选择主包", Toast.LENGTH_SHORT).show()
            return
        }

        val mainPackCard = binding.includeMainPack.root
        val progressIndicator = mainPackCard.findViewById<CircularProgressIndicator>(R.id.progress_time_apply)
        val linearProgress = mainPackCard.findViewById<LinearProgressIndicator>(R.id.progress_time_linear)
        val statusText = mainPackCard.findViewById<TextView>(R.id.tv_time_status)

        lifecycleScope.launch {
            progressIndicator.visibility = View.VISIBLE
            linearProgress.visibility = View.VISIBLE
            statusText.visibility = View.VISIBLE
            statusText.text = "准备中..."
            linearProgress.isIndeterminate = true
            
            AppLogger.func("randomizeFileTime", "开始随机修改时间", true, "路径: $path")
            
            val (count, time) = FileTimeModifier.randomizeTime(path) { current, total ->
                runOnUiThread {
                    linearProgress.isIndeterminate = false
                    linearProgress.max = total
                    linearProgress.progress = current
                    val percent = if (total > 0) (current * 100 / total) else 0
                    statusText.text = "修改中: $current / $total ($percent%)"
                }
            }
            
            val timeStr = FileTimeModifier.formatTime(time)
            
            progressIndicator.visibility = View.GONE
            linearProgress.visibility = View.GONE
            statusText.text = "✓ 已随机修改 $count 个文件"
            
            Toast.makeText(this@MainActivity, "已修改 $count 个文件时间为 $timeStr", Toast.LENGTH_LONG).show()
            
            // 更新显示
            mainPackCard.findViewById<TextView>(R.id.tv_current_file_time).text = "当前时间: $timeStr"
            mainPackCard.findViewById<TextView>(R.id.tv_main_pack_time).text = timeStr
        }
    }

    private fun showTimePickerDialog() {
        val path = selectedMainPackPath
        if (path == null) {
            Toast.makeText(this, "请先选择主包", Toast.LENGTH_SHORT).show()
            return
        }

        val timePickerHelper = TimePickerHelper(this, lifecycleScope)
        timePickerHelper.setOnTimeSelectedListener(object : TimePickerHelper.OnTimeSelectedListener {
            override fun onTimeSelected(timeMillis: Long, formattedTime: String) {
                // 显示确认对话框
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("确认修改时间")
                    .setMessage("将文件时间修改为: $formattedTime?")
                    .setPositiveButton("确定") { _, _ ->
                        timePickerHelper.applyTimeToFolder(path, timeMillis)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }

            override fun onApplyStarted() {
                val mainPackCard = binding.includeMainPack.root
                mainPackCard.findViewById<CircularProgressIndicator>(R.id.progress_time_apply).visibility = View.VISIBLE
                mainPackCard.findViewById<LinearProgressIndicator>(R.id.progress_time_linear).visibility = View.VISIBLE
                mainPackCard.findViewById<LinearProgressIndicator>(R.id.progress_time_linear).isIndeterminate = true
                mainPackCard.findViewById<TextView>(R.id.tv_time_status).visibility = View.VISIBLE
                mainPackCard.findViewById<TextView>(R.id.tv_time_status).text = "正在修改..."
            }

            override fun onApplyCompleted(fileCount: Int, formattedTime: String) {
                val mainPackCard = binding.includeMainPack.root
                mainPackCard.findViewById<CircularProgressIndicator>(R.id.progress_time_apply).visibility = View.GONE
                mainPackCard.findViewById<LinearProgressIndicator>(R.id.progress_time_linear).visibility = View.GONE
                mainPackCard.findViewById<TextView>(R.id.tv_current_file_time).text = "当前时间: $formattedTime"
                mainPackCard.findViewById<TextView>(R.id.tv_time_status).text = "✓ 已修改 $fileCount 个文件"
                mainPackCard.findViewById<TextView>(R.id.tv_main_pack_time)?.text = formattedTime
                
                Toast.makeText(this@MainActivity, "已修改 $fileCount 个文件", Toast.LENGTH_SHORT).show()
            }

            override fun onApplyFailed(error: String) {
                val mainPackCard = binding.includeMainPack.root
                mainPackCard.findViewById<CircularProgressIndicator>(R.id.progress_time_apply).visibility = View.GONE
                mainPackCard.findViewById<LinearProgressIndicator>(R.id.progress_time_linear).visibility = View.GONE
                mainPackCard.findViewById<TextView>(R.id.tv_time_status).text = "✗ 修改失败: $error"
                mainPackCard.findViewById<TextView>(R.id.tv_time_status).setTextColor(
                    ContextCompat.getColor(this@MainActivity, R.color.error_color)
                )
            }
        })
        
        // 获取当前文件时间作为初始值
        val currentTime = FileTimeModifier.getFileTime(path) ?: System.currentTimeMillis()
        timePickerHelper.showDateTimePicker(currentTime)
    }
    
    /**
     * 切换锁定/解锁时间
     */
    private fun lockCurrentTime() {
        val path = selectedMainPackPath
        if (path == null) {
            Toast.makeText(this, "请先选择主包", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            // 检查当前是否已锁定
            val currentLockedTime = preferencesManager.getLockedTimeIfEnabled()
            
            if (currentLockedTime != null) {
                // 已锁定，执行解锁操作
                preferencesManager.unlockTime()
                lockedTime = null
                Toast.makeText(this@MainActivity, "🔓 已解锁时间", Toast.LENGTH_SHORT).show()
                AppLogger.action("解锁时间")
                updateLockButtonState(false)
            } else {
                // 未锁定，执行锁定操作
                val currentTime = FileTimeModifier.getFileTime(path)
                if (currentTime == null) {
                    Toast.makeText(this@MainActivity, "无法获取当前时间", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                preferencesManager.lockTime(currentTime)
                lockedTime = currentTime
                
                val timeStr = FileTimeModifier.formatTime(currentTime)
                Toast.makeText(this@MainActivity, "✓ 已锁定时间: $timeStr", Toast.LENGTH_SHORT).show()
                AppLogger.action("锁定时间", timeStr)
                updateLockButtonState(true)
            }
        }
    }
    
    /**
     * 应用锁定的时间
     */
    private fun applyLockedTime() {
        val path = selectedMainPackPath
        if (path == null) {
            Toast.makeText(this, "请先选择主包", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            val lockedTime = preferencesManager.getLockedTimeIfEnabled()
            if (lockedTime == null) {
                Toast.makeText(this@MainActivity, "请先锁定一个时间", Toast.LENGTH_SHORT).show()
                return@launch
            }
            
            val timeStr = FileTimeModifier.formatTime(lockedTime)
            
            // 显示确认对话框
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("应用锁定时间")
                .setMessage("将文件时间修改为锁定的时间: $timeStr?")
                .setPositiveButton("确定") { _, _ ->
                    applyTimeToFolder(path, lockedTime, timeStr)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }
    
    /**
     * 应用指定时间到文件夹
     */
    private fun applyTimeToFolder(path: String, timeMillis: Long, formattedTime: String) {
        val mainPackCard = binding.includeMainPack.root
        val progressIndicator = mainPackCard.findViewById<CircularProgressIndicator>(R.id.progress_time_apply)
        val linearProgress = mainPackCard.findViewById<LinearProgressIndicator>(R.id.progress_time_linear)
        val statusText = mainPackCard.findViewById<TextView>(R.id.tv_time_status)
        
        lifecycleScope.launch {
            progressIndicator.visibility = View.VISIBLE
            linearProgress.visibility = View.VISIBLE
            statusText.visibility = View.VISIBLE
            statusText.text = "准备中..."
            linearProgress.isIndeterminate = true
            
            AppLogger.func("applyLockedTime", "开始应用锁定时间", true, "路径: $path, 时间: $formattedTime")
            
            val (count, _) = FileTimeModifier.setCustomTime(path, timeMillis) { current, total ->
                runOnUiThread {
                    linearProgress.isIndeterminate = false
                    linearProgress.max = total
                    linearProgress.progress = current
                    val percent = if (total > 0) (current * 100 / total) else 0
                    statusText.text = "修改中: $current / $total ($percent%)"
                }
            }
            
            progressIndicator.visibility = View.GONE
            linearProgress.visibility = View.GONE
            statusText.text = "✓ 已修改 $count 个文件"
            
            Toast.makeText(this@MainActivity, "已修改 $count 个文件时间为 $formattedTime", Toast.LENGTH_LONG).show()
            
            // 更新显示
            mainPackCard.findViewById<TextView>(R.id.tv_current_file_time).text = "当前时间: $formattedTime"
            mainPackCard.findViewById<TextView>(R.id.tv_main_pack_time).text = formattedTime
        }
    }
    
    /**
     * 更新锁定按钮状态
     */
    private fun updateLockButtonState(isLocked: Boolean) {
        val mainPackCard = binding.includeMainPack.root
        val lockButton = mainPackCard.findViewById<ImageButton>(R.id.btn_lock_time)
        
        if (isLocked) {
            // 锁定状态：显示锁定图标
            lockButton.setImageResource(android.R.drawable.ic_lock_lock)
            lockButton.alpha = 1.0f
        } else {
            // 未锁定状态：显示解锁图标
            lockButton.setImageResource(android.R.drawable.ic_lock_idle_lock)
            lockButton.alpha = 0.5f
        }
    }

    private fun showPatchPreview(patch: PatchManager.PatchVersion) {
        // 获取 ini 文件列表
        lifecycleScope.launch {
            val iniFiles = patchManager.getIniFiles(patch)
            
            if (iniFiles.isEmpty()) {
                Toast.makeText(this@MainActivity, "未找到 ini 文件", Toast.LENGTH_SHORT).show()
                return@launch
            }
            
            val items = iniFiles.map { it.name }.toTypedArray()
            
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("${patch.name}\n${iniFiles.size} 个 ini 文件")
                .setItems(items, null)
                .setPositiveButton("应用到主包") { _, _ ->
                    applyPatchToMainPack(patch)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }


    private var lastFailedPath: String? = null  // 记录失败的路径用于重试

    private fun startReplaceToGame() {
        // 防抖检查：如果正在执行替换任务，则忽略重复点击
        if (isReplacing) {
            Toast.makeText(this, "正在执行替换任务，请稍候...", Toast.LENGTH_SHORT).show()
            return
        }
        
        val path = selectedMainPackPath
        if (path == null) {
            Toast.makeText(this, "请先选择主包", Toast.LENGTH_SHORT).show()
            return
        }

        // 检查并请求必要权限
        lifecycleScope.launch {
            val status = permissionManager.checkAllPermissions()
            
            if (!status.hasManageStorage) {
                Log.w(TAG, "缺少 MANAGE_EXTERNAL_STORAGE 权限，请求授权")
                Toast.makeText(this@MainActivity, "请先授予存储权限", Toast.LENGTH_SHORT).show()
                requestPermissions()
                return@launch
            }
            
            if (status.bestMode != PermissionChecker.AccessMode.NONE) {
                // 权限已通过物理验证，直接开始
                isReplacing = true
                performStartReplace(path)
                return@launch
            }

            // 如果没有物理验证通过的模式，但具备基础存储权限，提示用户尝试
            if (status.hasManageStorage) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("环境未验证")
                    .setMessage("当前系统环境下无法自动验证读写权限，是否强制尝试执行替换？\n\n(提示：部分鸿蒙、澎湃或 Android 11 以下系统可能支持直接读写)")
                    .setPositiveButton("强制执行") { _, _ ->
                        isReplacing = true
                        performStartReplace(path)
                    }
                    .setNegativeButton("去授权 (Shizuku)") { _, _ ->
                        requestPermissions()
                    }
                    .show()
            } else {
                Toast.makeText(this@MainActivity, "请先授予所有文件访问权限", Toast.LENGTH_SHORT).show()
                requestPermissions()
            }
        }
    }

    /**
     * 执行真正的替换启动逻辑
     */
    private fun performStartReplace(path: String) {
        lifecycleScope.launch {
            AppLogger.action("正式开始替换任务", path)

            // 智能检测：处理 .pixuicache 文件夹优化
            val packageName = preferencesManager.appPackageName.first()
            val cacheResult = SmartCacheManager.checkAndOptimize(this@MainActivity, packageName, shizukuManager)
            if (cacheResult != null) {
                AppLogger.action("智能优化", cacheResult)
            }

            // 显示替换进度对话框
            showReplaceProgressDialog(path)
        }
    }
    
    // 替换进度对话框相关变量
    private var replaceDialog: androidx.appcompat.app.AlertDialog? = null
    private val logBuilder = StringBuilder()
    private var logTextView: TextView? = null
    private var logScrollView: android.widget.ScrollView? = null
    
    // 重置替换状态
    private fun resetReplacingState() {
        isReplacing = false
    }
    
    private fun showReplaceProgressDialog(path: String) {
        // 创建对话框视图
        val dialogView = layoutInflater.inflate(R.layout.dialog_replace_progress, null)
        val progressBar = dialogView.findViewById<LinearProgressIndicator>(R.id.progress_bar)
        val tvPercent = dialogView.findViewById<TextView>(R.id.tv_progress_percent)
        val tvFileCount = dialogView.findViewById<TextView>(R.id.tv_file_count)
        val tvCurrentFile = dialogView.findViewById<TextView>(R.id.tv_current_file)
        val tvSpeed = dialogView.findViewById<TextView>(R.id.tv_speed)
        val tvEta = dialogView.findViewById<TextView>(R.id.tv_eta)
        val tvLog = dialogView.findViewById<TextView>(R.id.tv_log)
        val scrollLog = dialogView.findViewById<android.widget.ScrollView>(R.id.scroll_log)
        val tvErrors = dialogView.findViewById<TextView>(R.id.tv_errors)
        
        logTextView = tvLog
        logScrollView = scrollLog
        logBuilder.clear()
        
        // 初始化日志
        appendLog("📂 源路径: ${java.io.File(path).name}")
        appendLog("🎯 目标: /storage/emulated/0/Android")
        appendLog("⏳ 开始检测存储空间...")
        
        progressBar.isIndeterminate = true
        tvPercent.text = "检测中"
        tvFileCount.text = ""
        tvCurrentFile.text = "正在检测存储空间..."
        
        // 创建对话框
        replaceDialog = MaterialAlertDialogBuilder(this)
            .setTitle("📦 替换到游戏")
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton("隐藏到后台") { dialog, _ ->
                // 检查悬浮窗权限
                if (android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                    // 隐藏对话框，但保持任务运行
                    dialog.dismiss()
                    // 确保悬浮球已显示
                    if (!floatingBallManager.isShowing()) {
                        currentWorkId?.let { floatingBallManager.setWorkId(it) }
                        floatingBallManager.show()
                    }
                    AppLogger.action("隐藏到后台", "任务继续在后台运行")
                } else {
                    // 没有权限，提示用户
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("需要悬浮窗权限")
                        .setMessage("为了在后台显示进度，需要授予悬浮窗权限。是否前往设置页面授权？")
                        .setPositiveButton("去设置") { _, _ ->
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:$packageName")
                            )
                            startActivity(intent)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
            .setNegativeButton("取消") { dialog, _ ->
                // 强制停止当前工作任务
                currentWorkId?.let { id ->
                    try {
                        val uuid = java.util.UUID.fromString(id)
                        androidx.work.WorkManager.getInstance(this).cancelWorkById(uuid)
                        AppLogger.action("用户取消替换", "Work ID: $id")
                    } catch (e: Exception) {
                        AppLogger.e("MainActivity", "取消任务失败", e)
                    }
                }
                
                // 隐藏悬浮球
                floatingBallManager.hide()
                
                dialog.dismiss()
                appendLog("❌ 用户取消操作")
                resetReplacingState()
            }
            .create()
        
        replaceDialog?.show()
        
        // 异步检测存储空间
        lifecycleScope.launch {
            val checkResult = StorageChecker.checkStorageFast(
                path, 
                "/storage/emulated/0/Android"
            )
            
            AppLogger.d("MainActivity", "存储检测: ${checkResult.message}")
            appendLog("📊 ${checkResult.message}")
            
            if (!checkResult.canReplace) {
                appendLog("❌ 空间不足，无法继续")
                tvErrors.visibility = View.VISIBLE
                tvErrors.text = "错误: ${checkResult.message}"
                progressBar.isIndeterminate = false
                progressBar.progress = 0
                tvCurrentFile.text = "操作失败"
                return@launch
            }
            
            appendLog("✅ 空间充足，开始替换...")
            progressBar.isIndeterminate = false
            
            // 执行替换
            performReplaceWithDialog(path, progressBar, tvPercent, tvFileCount, tvCurrentFile, tvErrors, tvSpeed, tvEta)
        }
    }
    
    private fun appendLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        logBuilder.append("[$timestamp] $message\n")
        runOnUiThread {
            logTextView?.text = logBuilder.toString()
            // 自动滚动到底部
            logScrollView?.post {
                logScrollView?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }
    
    private fun performReplaceWithDialog(
        path: String,
        progressBar: LinearProgressIndicator,
        tvPercent: TextView,
        tvFileCount: TextView,
        tvCurrentFile: TextView,
        tvErrors: TextView,
        tvSpeed: TextView,
        tvEta: TextView
    ) {
        AppLogger.d("MainActivity", "🚀 准备启动替换任务")
        
        // 开启协程获取包名并启动任务
        lifecycleScope.launch {
            try {
                AppLogger.d("MainActivity", "⏳ 正在获取包名...")
                val packageName = preferencesManager.appPackageName.first()
                AppLogger.d("MainActivity", "✅ 获取包名成功: $packageName")
                
                // 默认不使用增量更新
                val incrementalUpdate = false 
                
                val workRequest = com.example.tfgwj.worker.FileReplaceWorker.createWorkRequest(
                    path, 
                    packageName,
                    incrementalUpdate
                )
                
                AppLogger.d("MainActivity", "✅ 创建 WorkRequest 成功: ${workRequest.id}")
                
                val workManager = androidx.work.WorkManager.getInstance(this@MainActivity)
                workManager.enqueue(workRequest)
                
                // 保存当前工作 ID
                currentWorkId = workRequest.id.toString()
                
                // 监听进度
                observeReplaceProgress(workRequest.id, incrementalUpdate, progressBar, tvPercent, tvFileCount, tvCurrentFile, tvErrors, tvSpeed, tvEta)
                
            } catch (e: Exception) {
                AppLogger.e("MainActivity", "❌ 启动替换任务失败", e)
                appendLog("❌ 启动失败: ${e.message}")
                tvErrors.visibility = View.VISIBLE
                tvErrors.text = "错误: ${e.message}"
                progressBar.isIndeterminate = false
                progressBar.progress = 0
                tvCurrentFile.text = "操作失败"
            }
        }
    }

    private fun observeReplaceProgress(
        workId: java.util.UUID,
        incrementalUpdate: Boolean,
        progressBar: LinearProgressIndicator,
        tvPercent: TextView,
        tvFileCount: TextView,
        tvCurrentFile: TextView,
        tvErrors: TextView,
        tvSpeed: TextView,
        tvEta: TextView
    ) {
        
        // 设置悬浮球工作的 ID，但不在此处立即显示，
        // 只有当用户点击“隐藏到后台”时才显示悬浮球
        floatingBallManager.setWorkId(workId.toString())
        
        AppLogger.d("MainActivity", "✅ Worker 已入队: $workId")
        appendLog("🚀 Worker 已启动，正在处理...")
        if (incrementalUpdate) {
            appendLog("📦 增量更新模式：只复制变化的文件")
        }
        
        var errorCount = 0
        var lastLoggedFile = ""
        var startTime: Long = 0
        var lastProcessed = 0
        var lastUpdateTime: Long = 0
        var lastLogTime: Long = 0 // 上次记录日志的时间
        
        // 监听实时进度 (High Frequency)
        lifecycleScope.launch {
            com.example.tfgwj.manager.ReplaceProgressManager.progressState.collectLatest { state ->
                if (state.total > 0 && state.isReplacing) {
                    val progress = state.progress
                    val processed = state.processed
                    val total = state.total
                    val currentFile = state.currentFile
                    val speed = state.speed
                    val phase = state.phase

                    // 1. 记录/初始化开始时间
                    if (startTime == 0L) {
                        startTime = System.currentTimeMillis()
                        lastUpdateTime = startTime
                    }

                    // 2. 进度条平滑动画
                    val oldProgress = progressBar.progress
                    if (progress > oldProgress) {
                        val animator = android.animation.ValueAnimator.ofInt(oldProgress, progress)
                        animator.duration = 300
                        animator.interpolator = android.view.animation.DecelerateInterpolator()
                        animator.addUpdateListener { animation ->
                            val animatedValue = animation.animatedValue as Int
                            progressBar.progress = animatedValue
                            tvPercent.text = "$animatedValue%"
                        }
                        animator.start()
                    } else if (progress < oldProgress) {
                        progressBar.progress = progress
                        tvPercent.text = "$progress%"
                    }

                    // 3. 文件计数平滑动画
                    // 在校验阶段提示已完成的总数，而不是校验的子计数
                    if (phase == "VERIFYING") {
                        tvFileCount.text = "$total / $total"
                    } else if (lastProcessed < processed) {
                        val countAnimator = android.animation.ValueAnimator.ofInt(lastProcessed, processed)
                        countAnimator.duration = 200
                        countAnimator.interpolator = android.view.animation.LinearInterpolator() 
                        countAnimator.addUpdateListener { animation ->
                            val currentCount = animation.animatedValue as Int
                            tvFileCount.text = "$currentCount / $total"
                        }
                        countAnimator.start()
                    } else {
                        tvFileCount.text = "$processed / $total"
                    }

                    // 4. 当前文件显示 (增加阶段前缀)
                    if (currentFile.isNotEmpty()) {
                        val prefix = when(phase) {
                            "REPLACING" -> "📄 正在复制: "
                            "VERIFYING" -> "🔍 正在校验: "
                            else -> "📦 "
                        }
                        tvCurrentFile.text = "$prefix$currentFile"
                    }

                    // 5. 计算速率与 ETA (每 0.5 秒更新一次)
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastUpdateTime >= 500) {
                        if (processed > lastProcessed || phase == "VERIFYING") {
                            val processedDiff = (processed - lastProcessed).coerceAtLeast(1)
                            val timeDiff = (currentTime - lastUpdateTime).coerceAtLeast(1) / 1000.0
                            val currentSpeed = processedDiff / timeDiff
                             
                            if (phase == "VERIFYING") {
                                tvSpeed.text = "速度: 正在校验..."
                                tvEta.text = "即将完成"
                            } else {
                                tvSpeed.text = "速度: ${String.format("%.0f", currentSpeed)} 文件/秒"
                                val remaining = total - processed
                                val etaSeconds = if (currentSpeed > 0) remaining / currentSpeed else 0.0
                                tvEta.text = if (etaSeconds > 60) {
                                    val minutes = (etaSeconds / 60).toInt()
                                    val seconds = (etaSeconds % 60).toInt()
                                    "预计剩余: ${minutes}分${seconds}秒"
                                } else {
                                    "预计剩余: ${etaSeconds.toInt()}秒"
                                }
                            }
                        }
                        lastUpdateTime = currentTime
                    }

                    // 6. 定期记录进度日志 (每 0.5 秒)
                    if (currentTime - lastLogTime > 500) {
                        AppLogger.d(TAG, "📊 进度($phase): $progress% ($processed/$total) - ${tvSpeed.text}")
                        lastLogTime = currentTime
                    }

                    // 7. 详细文件日志 (流式显示)
                    if (currentFile.isNotEmpty() && currentFile != lastLoggedFile) {
                        if (currentFile.startsWith("[失败]")) {
                            errorCount++
                            appendLog("❌ $currentFile")
                            tvErrors.visibility = View.VISIBLE
                            tvErrors.text = "错误: $errorCount 个文件复制失败"
                        } else {
                            // 使用时间节流优化
                            val now = System.currentTimeMillis()
                            if (now - lastLogTime >= 100 || processed <= 5 || processed >= total || phase == "VERIFYING") {
                                val prefix = if (phase == "VERIFYING") "🔍 " else "📄 "
                                appendLog("$prefix$currentFile")
                            }
                        }
                        lastLoggedFile = currentFile
                    }

                    lastProcessed = processed 
                }
            }
        }
        
        // 监听 WorkManager 状态 (主要用于检测任务完成/失败/取消)
        val workManager = androidx.work.WorkManager.getInstance(this)
        workManager.getWorkInfoByIdLiveData(workId).observe(this) { workInfo ->
            if (workInfo != null) {
                // AppLogger.d("MainActivity", "📊 Worker 状态: ${workInfo.state}")
                when (workInfo.state) {
                    androidx.work.WorkInfo.State.ENQUEUED -> {
                        AppLogger.d("MainActivity", "⏳ Worker 已入队，等待执行")
                    }
                    androidx.work.WorkInfo.State.RUNNING -> {
                        // WorkManager 的进度现在仅作为辅助，UI 主要由 ReplaceProgressManager 驱动
                        // 但我们可以记录一下 Worker 确实在运行
                        // AppLogger.d(TAG, "▶️ Worker 正在运行 (WM 进度: ${workInfo.progress})")
                    }
                    androidx.work.WorkInfo.State.SUCCEEDED -> {
                        val processed = workInfo.outputData.getInt(
                            com.example.tfgwj.worker.FileReplaceWorker.KEY_PROCESSED, 0
                        )
                        // 从 JSON 字符串解析失败文件列表
                        val failedFilesJson = workInfo.outputData.getString(
                            com.example.tfgwj.worker.FileReplaceWorker.KEY_FAILED_FILES
                        )
                        val failedFiles = try {
                            if (failedFilesJson != null) {
                                val jsonArray = org.json.JSONArray(failedFilesJson)
                                (0 until jsonArray.length()).map { jsonArray.getString(it) }
                            } else {
                                emptyList()
                            }
                        } catch (e: Exception) {
                            AppLogger.e("MainActivity", "解析失败文件列表失败", e)
                            emptyList()
                        }
                        
                        AppLogger.action("替换完成", "成功 $processed 个文件")
                        
                        // 检查是否有失败的文件
                        if (failedFiles.isNotEmpty()) {
                            appendLog("⚠️ 替换完成！共 $processed 个文件，${failedFiles.size} 个文件失败")
                            appendLog("失败文件列表:")
                            failedFiles.forEach { fileName ->
                                appendLog("  ❌ $fileName")
                            }
                            tvErrors.visibility = View.VISIBLE
                            tvErrors.text = "警告: ${failedFiles.size} 个文件复制失败，详情见日志"
                        } else {
                            appendLog("✅ 替换完成！共 $processed 个文件")
                        }
                        
                        progressBar.progress = 100
                        tvPercent.text = "100%"
                        tvCurrentFile.text = "✅ 完成"
                        
                        // 验证文件是否真的复制成功
                        appendLog("🔍 验证替换结果...")
                        val targetPath = "/storage/emulated/0/Android/data/$packageName"
                        
                        val hasRoot = com.example.tfgwj.utils.RootChecker.isRooted()
                        val verifiedFiles = verifyReplacement(packageName, hasRoot)
                        
                        if (verifiedFiles > 0) {
                            appendLog("✅ 验证成功: 目标位置发现 $verifiedFiles 个文件")
                            appendLog("   目标路径: $targetPath")
                        } else {
                            appendLog("⚠️ 验证警告: 目标位置没有发现文件")
                            appendLog("   目标路径: $targetPath")
                        }
                        
                        // 延迟关闭对话框
                        lifecycleScope.launch {
                            kotlinx.coroutines.delay(1500)
                            replaceDialog?.dismiss()
                            showSuccessResult(processed, failedFiles.size, verifiedFiles)
                            resetReplacingState()
                        }
                    }
                    androidx.work.WorkInfo.State.FAILED -> {
                        AppLogger.e("MainActivity", "❌ Worker 失败: ${workInfo.state}")
                        val errorMsg = workInfo.outputData.getString(
                            com.example.tfgwj.worker.FileReplaceWorker.KEY_ERROR_MESSAGE
                        ) ?: "替换失败，请查看日志"
                        AppLogger.e("MainActivity", "❌ 错误信息: $errorMsg")
                        
                        // 尝试获取更多错误信息
                        val outputData = workInfo.outputData
                        AppLogger.d("MainActivity", "❌ Worker 输出数据: ${outputData.keyValueMap}")
                        
                        appendLog("❌ 失败: $errorMsg")
                        tvErrors.visibility = View.VISIBLE
                        tvErrors.text = errorMsg
                        tvCurrentFile.text = "❌ 失败"
                        
                        // 更新对话框按钮
                        replaceDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.text = "关闭"
                        resetReplacingState()
                    }
                    androidx.work.WorkInfo.State.CANCELLED -> {
                        AppLogger.action("替换已取消")
                        appendLog("⚠️ 操作已取消")
                        tvCurrentFile.text = "已取消"
                        resetReplacingState()
                    }
                    else -> {}
                }
            }
        }
    }
    
    private fun showSuccessResult(fileCount: Int, failedCount: Int = 0, verifiedCount: Int = -1) {
        val toastMessage = when {
            failedCount > 0 -> "替换完成！$fileCount 个文件成功，$failedCount 个失败"
            verifiedCount >= 0 && verifiedCount != fileCount -> "替换完成！$fileCount 个文件，验证发现 $verifiedCount 个文件"
            else -> "替换完成！$fileCount 个文件"
        }
        AppLogger.action("替换完成", "成功 $fileCount 个文件，验证 $verifiedCount 个文件")
        
        android.widget.Toast.makeText(this, toastMessage, android.widget.Toast.LENGTH_LONG).show()
    }
    
    /**
     * 验证替换结果，检查目标位置是否有文件
     * @param packageName 目标应用包名
     * @param hasRoot 是否有 Root 权限
     * @return 验证到的文件数量
     */
    private fun verifyReplacement(packageName: String, hasRoot: Boolean): Int {
        val targetPath = "/storage/emulated/0/Android/data/$packageName"
        
        return if (hasRoot) {
            // Root 模式：使用 Root 命令验证
            verifyReplacementViaRoot(targetPath)
        } else {
            // 非 Root 模式：使用原生 API 验证
            verifyReplacementViaNative(targetPath)
        }
    }
    
    /**
     * 使用 Root 命令验证替换结果
     */
    private fun verifyReplacementViaRoot(targetPath: String): Int {
        return try {
            // 使用 find 命令统计文件数量
            val command = "find \"$targetPath\" -type f 2>/dev/null | wc -l"
            val result = com.example.tfgwj.utils.RootChecker.executeRootCommand(command)
            
            val count = result?.trim()?.toIntOrNull() ?: 0
            AppLogger.d("MainActivity", "Root 验证: $targetPath 有 $count 个文件")
            count
        } catch (e: Exception) {
            AppLogger.e("MainActivity", "Root 验证失败", e)
            0
        }
    }
    
    /**
     * 检查是否是增量更新
     * @return true 表示是增量更新（第二次替换）
     */
    private fun checkIsIncrementalUpdate(packageName: String): Boolean {
        val targetBase = "/storage/emulated/0/Android/data/$packageName"
        val targetDir = java.io.File(targetBase)
        
        if (!targetDir.exists()) {
            AppLogger.d("MainActivity", "首次替换：目标目录不存在")
            return false
        }
        
        // 检查是否有已经复制的文件
        val hasFiles = targetDir.walk().filter { it.isFile }.count()
        val isIncremental = hasFiles > 0
        
        AppLogger.d("MainActivity", "增量更新检查: 目标目录有 $hasFiles 个文件")
        
        return isIncremental
    }
    
    /**
     * 使用原生 API 验证替换结果
     */
    private fun verifyReplacementViaNative(targetPath: String): Int {
        return try {
            val targetDir = java.io.File(targetPath)
            if (targetDir.exists()) {
                val count = targetDir.walk().filter { it.isFile }.count()
                AppLogger.d("MainActivity", "原生验证: $targetPath 有 $count 个文件")
                count
            } else {
                AppLogger.w("MainActivity", "原生验证: $targetPath 不存在")
                0
            }
        } catch (e: Exception) {
            AppLogger.e("MainActivity", "原生验证失败", e)
            0
        }
    }

    private fun showFailedResult(error: String, path: String? = null) {
        Toast.makeText(this, "任务失败: $error", Toast.LENGTH_SHORT).show()
    }

    private fun updateProgressUI(result: com.example.tfgwj.model.FileReplaceResult) {
        if (result.isCompleted) {
            binding.cardProgress.visibility = View.GONE
            showResultUI(result)
        } else {
            binding.cardProgress.visibility = View.VISIBLE
            val progress = (result.progress * 100).toInt()
            binding.progressBar.progress = progress
            binding.tvProgressPercentage.text = "$progress%"
            binding.tvFileCount.text = "${result.successCount + result.failedCount}/${result.totalFiles}"
            binding.tvSuccessCount.text = "成功: ${result.successCount}"
            binding.tvFailedCount.text = "失败: ${result.failedCount}"
        }
    }

    private fun showResultUI(result: com.example.tfgwj.model.FileReplaceResult) {
        val message = if (result.failedCount == 0) {
            "替换成功！共替换 ${result.successCount} 个文件"
        } else {
            "替换完成！成功: ${result.successCount}, 失败: ${result.failedCount}"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun scanArchives() {
        lifecycleScope.launch {
            // 显示扫描进度对话框
            showArchiveScanDialog { archives ->
                if (archives.isEmpty()) {
                    Toast.makeText(this@MainActivity, "未找到压缩包", Toast.LENGTH_SHORT).show()
                } else {
                    // 显示压缩包列表对话框
                    showArchiveListDialog(archives)
                }
            }
        }
    }

    private fun showArchiveListDialog(archives: List<ArchiveScanner.ArchiveInfo>) {
        val items = archives.map { "${it.name} (${it.sizeText})" }.toTypedArray()
        
        MaterialAlertDialogBuilder(this)
            .setTitle("选择压缩包解压")
            .setItems(items) { _, which ->
                val selected = archives[which]
                extractArchive(selected)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 显示扫描进度对话框
     */
    private fun showArchiveScanDialog(onComplete: (List<ArchiveScanner.ArchiveInfo>) -> Unit) {
        // 创建进度对话框视图
        val dialogView = layoutInflater.inflate(R.layout.dialog_progress, null)
        val progressBar = dialogView.findViewById<LinearProgressIndicator>(R.id.progress_bar)
        val tvProgress = dialogView.findViewById<TextView>(R.id.tv_progress)
        val tvCurrentItem = dialogView.findViewById<TextView>(R.id.tv_current_item)
        
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        tvProgress.text = "0%"
        tvCurrentItem.text = "准备扫描..."
        
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle("🔍 扫描压缩包")
            .setView(dialogView)
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ ->
                // 取消扫描
                dialog.dismiss()
            }
            .create()
        
        dialog.show()
        
        // 开始扫描
        lifecycleScope.launch {
            // 监听扫描进度
            var lastStatus = ""
            var lastPercent = -1
            
            // 使用一个单独的协程来收集进度更新
            val statusJob = launch {
                archiveScanner.scanStatus.collectLatest { status ->
                    if (status != lastStatus) {
                        lastStatus = status
                        runOnUiThread {
                            tvCurrentItem.text = status
                        }
                    }
                }
            }
            
            val percentJob = launch {
                archiveScanner.scanProgress.collectLatest { progress ->
                    val percent = (progress * 100).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        runOnUiThread {
                            progressBar.progress = percent
                            tvProgress.text = "${percent}%"
                        }
                    }
                }
            }
            
            // 执行扫描（这是阻塞的，会等待扫描完成）
            val archives = archiveScanner.scanArchives()
            
            // 取消监听任务
            statusJob.cancel()
            percentJob.cancel()
            
            // 关闭对话框并回调
            dialog.dismiss()
            onComplete(archives)
        }
    }

    private fun handleExtractAndUpdate(uri: Uri) {
        // 从 URI 获取路径 (使用 ContentResolver 获取实际路径或文件名)
        val path = getPathFromContentUri(uri) ?: run {
            Toast.makeText(this, "无法获取文件路径", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            // 显示解压进度对话框
            val dialogView = layoutInflater.inflate(R.layout.dialog_progress, null)
            val progressBar = dialogView.findViewById<LinearProgressIndicator>(R.id.progress_bar)
            val tvProgress = dialogView.findViewById<TextView>(R.id.tv_progress)
            val tvCurrentItem = dialogView.findViewById<TextView>(R.id.tv_current_item)
            
            progressBar.isIndeterminate = true
            tvProgress.text = "准备中..."
            tvCurrentItem.text = "正在读取文件..."
            
            lateinit var dialog: androidx.appcompat.app.AlertDialog
            
            dialog = MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("📦 解压压缩包")
                .setView(dialogView)
                .setCancelable(false)
                .setNegativeButton("取消") { _, _ ->
                    dialog.dismiss()
                }
                .create()
            
            dialog.show()
            
            var password: String? = null
            var retryCount = 0
            
            while (retryCount < 3) {
                // 更新对话框状态
                runOnUiThread {
                    progressBar.isIndeterminate = true
                    tvProgress.text = "解压中..."
                    tvCurrentItem.text = if (password != null) {
                        "正在解压（尝试 $retryCount/3）..."
                    } else {
                        "正在解压..."
                    }
                }
                
                // 尝试解压
                val result = ExtractManager.getInstance().extractToCache(path, password)
                
                if (result.success) {
                    runOnUiThread {
                        progressBar.isIndeterminate = false
                        progressBar.progress = 100
                        tvProgress.text = "100%"
                        tvCurrentItem.text = "✅ 解压成功"
                    }
                    
                    kotlinx.coroutines.delay(500)
                    dialog.dismiss()
                    
                    Toast.makeText(this@MainActivity, "解压成功", Toast.LENGTH_SHORT).show()
                    
                    // 构建 PatchVersion 对象
                    val outputDir = File(result.outputPath)
                    val patchName = outputDir.name
                    // 计算目录大小和文件信息
                    var totalSize = 0L
                    var fileCount = 0
                    var hasIniFiles = false
                    outputDir.walkTopDown().forEach { file ->
                        if (file.isFile) {
                            totalSize += file.length()
                            fileCount++
                            if (file.extension.lowercase() == "ini") {
                                hasIniFiles = true
                            }
                        }
                    }
                    val sizeText = formatSize(totalSize)
                    val patch = PatchManager.PatchVersion(
                        name = patchName,
                        path = result.outputPath,
                        sizeBytes = totalSize,
                        sizeText = sizeText,
                        fileCount = fileCount,
                        hasIniFiles = hasIniFiles
                    )
                    
                    // 自动应用到主包
                    applyPatchToMainPack(patch)
                    return@launch
                }
                
                // 处理失败
                if (result.errorMessage == "需要密码" || result.errorMessage == "密码错误") {
                    // 弹出密码输入框 (挂起函数)
                    val input = promptForPassword(File(path).name, result.errorMessage == "密码错误")
                    if (input != null) {
                        password = input
                        retryCount++
                    } else {
                        // 用户取消
                        dialog.dismiss()
                        Toast.makeText(this@MainActivity, "已取消", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                } else {
                    runOnUiThread {
                        progressBar.isIndeterminate = false
                        progressBar.progress = 0
                        tvCurrentItem.text = "❌ 解压失败"
                    }
                    
                    kotlinx.coroutines.delay(500)
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, "解压失败: ${result.errorMessage}", Toast.LENGTH_LONG).show()
                    return@launch
                }
            }
            dialog.dismiss()
            Toast.makeText(this@MainActivity, "多次尝试失败", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 挂起函数：显示密码输入框并等待结果
     */
    private suspend fun promptForPassword(fileName: String, isRetry: Boolean): String? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val dialogView = layoutInflater.inflate(R.layout.dialog_password_input, null)
        val etPassword = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_password)
        val tvName = dialogView.findViewById<TextView>(R.id.tv_file_name)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tv_suggested_password)
        val btnUseSuggested = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_use_suggested)
        
        // 提取文件名作为建议密码
        val suggestedPassword = fileName.substringBeforeLast(".")
        
        tvName.text = "解压: $fileName"
        tvStatus.text = "建议密码: $suggestedPassword"
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        
        if (isRetry) {
            tvStatus.text = "密码错误，请重试\n建议密码: $suggestedPassword"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.error_color))
        }
        
        // 点击建议密码按钮
        btnUseSuggested.setOnClickListener {
            etPassword.setText(suggestedPassword)
            btnUseSuggested.visibility = View.GONE
        }
        
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("请输入密码")
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                val pwd = etPassword.text.toString()
                if (cont.isActive) cont.resume(pwd, null)
            }
            .setNegativeButton("取消") { _, _ ->
                if (cont.isActive) cont.resume(null, null)
            }
            .setOnCancelListener {
                if (cont.isActive) cont.resume(null, null)
            }
            .create()
            .show()
    }

    private fun extractArchive(archive: ArchiveScanner.ArchiveInfo) {
        extractArchiveToCache(archive)
    }
    
    private fun extractArchiveToMainPack(archive: ArchiveScanner.ArchiveInfo) {
        lifecycleScope.launch {
            if (selectedMainPackPath == null) {
                Toast.makeText(this@MainActivity, "请先选择主包", Toast.LENGTH_SHORT).show()
                return@launch
            }
            
            // 检查是否已有该版本
            val versionName = File(archive.path).nameWithoutExtension
            val cacheDir = File(PermissionChecker.CACHE_DIR)
            val existingVersionDir = File(cacheDir, versionName)
            
            if (existingVersionDir.exists()) {
                // 提示用户版本已存在
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("版本已存在")
                    .setMessage("版本 \"$versionName\" 已存在，是否覆盖？")
                    .setPositiveButton("覆盖") { _, _ ->
                        // 继续执行解压和更新
                        extractAndUpdateToMainPack(archive, versionName, existingVersionDir)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            } else {
                // 直接执行解压和更新
                extractAndUpdateToMainPack(archive, versionName, existingVersionDir)
            }
        }
    }
    
    private fun extractAndUpdateToMainPack(archive: ArchiveScanner.ArchiveInfo, versionName: String, versionDir: File) {
        lifecycleScope.launch {
            var password: String? = null
            var retryCount = 0
            
            // 如果已知需要密码，先弹窗
            val extractManager = ExtractManager.getInstance()
            if (extractManager.isPasswordRequired(archive.path) && password == null) {
                password = promptForPassword(archive.name, false)
                if (password == null) {
                    Toast.makeText(this@MainActivity, "已取消", Toast.LENGTH_SHORT).show()
                    return@launch
                }
            }
            
            while (retryCount < 3) {
                // 显示进度对话框（带实时进度更新）
                val dialogView = layoutInflater.inflate(R.layout.dialog_progress, null)
                val progressBar = dialogView.findViewById<LinearProgressIndicator>(R.id.progress_bar)
                val tvProgress = dialogView.findViewById<TextView>(R.id.tv_progress)
                val tvCurrentItem = dialogView.findViewById<TextView>(R.id.tv_current_item)
                
                progressBar.isIndeterminate = true
                tvProgress.text = "准备解压..."
                
                val sourcePath = archive.path
                val targetPath = File(PermissionChecker.CACHE_DIR, versionName).absolutePath
                val sourceName = File(sourcePath).name
                
                // 显示详细的源和目标路径
                tvCurrentItem.text = "步骤 1/2: 解压压缩包\n源: $sourcePath\n目标: $targetPath"
                
                val progressDialog = MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("📦 解压并更新到主包")
                    .setMessage("版本: $versionName")
                    .setView(dialogView)
                    .setCancelable(true)
                    .setNegativeButton("取消") { dialog, _ ->
                        extractManager.cancelExtraction()
                        dialog.dismiss()
                    }
                    .create()
                progressDialog.show()
                
                // 监听解压进度
                var isDismissed = false
                
                val progressJob = launch {
                    extractManager.extractProgress.collectLatest { progress ->
                        if (!isDismissed) {
                            runOnUiThread {
                                progressBar.isIndeterminate = false
                                progressBar.progress = progress
                                tvProgress.text = "解压进度: $progress%"
                            }
                        }
                    }
                }
                
                val statusJob = launch {
                    extractManager.extractStatus.collectLatest { status ->
                        if (!isDismissed) {
                            runOnUiThread {
                                // 保留步骤信息，追加当前状态
                                val lines = tvCurrentItem.text.toString().split("\n").take(3)
                                tvCurrentItem.text = "${lines[0]}\n${lines[1]}\n${lines[2]}\n$status"
                            }
                        }
                    }
                }
                
                // 步骤1: 解压到缓存目录
                val result = extractManager.extractToCache(archive.path, password, versionName)
                
                if (result.success) {
                    // 步骤2: 复制文件到主包
                    // 获取当前选择的应用包名
                    val packageName = preferencesManager.appPackageName.first()
                    val configTargetPath = PermissionChecker.getAppConfigPath(packageName)
                        .replace("/storage/emulated/0/Android/data/", "$selectedMainPackPath/Android/data/")
                    val configTargetDir = File(configTargetPath)
                    
                    if (!configTargetDir.exists()) {
                        configTargetDir.mkdirs()
                    }
                    
                    // 更新对话框显示步骤2
                    runOnUiThread {
                        tvCurrentItem.text = "步骤 2/2: 复制文件到主包\n源: $targetPath\n目标: $configTargetPath"
                        progressBar.isIndeterminate = true
                        tvProgress.text = "准备复制..."
                    }
                    
                    // 批量并行复制文件
                    val copyResult = copyFilesToMainPack(File(targetPath), configTargetDir) { current, total, currentFile ->
                        if (!isDismissed) {
                            runOnUiThread {
                                progressBar.isIndeterminate = false
                                progressBar.progress = (current * 100) / total
                                tvProgress.text = "复制进度: $current/$total"
                                tvCurrentItem.text = "步骤 2/2: 复制文件到主包\n源: $targetPath\n目标: $configTargetPath\n正在复制: $currentFile"
                            }
                        }
                    }
                    
                    isDismissed = true
                    progressDialog.dismiss()
                    progressJob.cancel()
                    statusJob.cancel()
                    
                    if (copyResult.success) {
                        Toast.makeText(this@MainActivity, "✅ 完成！\n解压: ${result.extractedCount} 个文件\n复制: ${copyResult.copiedCount} 个文件", Toast.LENGTH_LONG).show()
                        loadPatchVersions() // 刷新小包列表
                        return@launch
                    } else {
                        Toast.makeText(this@MainActivity, "❌ 复制失败: ${copyResult.errorMessage}", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                } else {
                    isDismissed = true
                    progressDialog.dismiss()
                    progressJob.cancel()
                    statusJob.cancel()
                    
                    // 处理密码错误
                    if (result.errorMessage == "需要密码" || result.errorMessage == "密码错误") {
                        val input = promptForPassword(archive.name, result.errorMessage == "密码错误")
                        if (input != null) {
                            password = input
                            retryCount++
                        } else {
                            Toast.makeText(this@MainActivity, "已取消", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "解压失败: ${result.errorMessage}", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                }
            }
            Toast.makeText(this@MainActivity, "多次尝试失败", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showArchiveListDialogForMainPack(archives: List<ArchiveScanner.ArchiveInfo>) {
        Log.d(TAG, "显示压缩包列表对话框，共 ${archives.size} 个压缩包")
        
        if (selectedMainPackPath == null) {
            Log.e(TAG, "selectedMainPackPath 为空，无法显示列表")
            Toast.makeText(this, "请先选择主包", Toast.LENGTH_SHORT).show()
            return
        }
        
        val items = archives.map { "${it.name} (${it.sizeText})" }.toTypedArray()
        val mainPackName = File(selectedMainPackPath!!).name
        
        Log.d(TAG, "主包名称: $mainPackName，压缩包列表: ${items.contentToString()}")
        
        try {
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle("选择压缩包解压到主包")
                .setPositiveButton("取消", null)
                .setItems(items) { _, which ->
                    val selected = archives[which]
                    Log.d(TAG, "用户选择压缩包: ${selected.name}")
                    extractArchiveToMainPack(selected)
                }
                .create()
            
            // 显示到 Toast 提示用户目标
            Toast.makeText(this, "将解压到: $mainPackName", Toast.LENGTH_SHORT).show()
            
            dialog.show()
            Log.d(TAG, "压缩包列表对话框已显示")
        } catch (e: Exception) {
            Log.e(TAG, "显示压缩包列表对话框失败", e)
            Toast.makeText(this, "显示列表失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun scanAndExtractArchive() {
        // 检查是否已选择主包
        if (selectedMainPackPath == null) {
            Toast.makeText(this, "请先选择主包", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            // 显示扫描进度对话框
            showArchiveScanDialog { archives ->
                if (archives.isEmpty()) {
                    Toast.makeText(this@MainActivity, "未找到压缩包", Toast.LENGTH_SHORT).show()
                } else {
                    // 显示压缩包列表对话框
                    showArchiveListDialogForMainPack(archives)
                }
            }
        }
    }
    
    /**
     * 检查文件是否需要更新（增量更新）
     * 通过文件哈希和大小判断是否需要复制
     */
    private suspend fun needsUpdate(source: File, target: File): Boolean = withContext(Dispatchers.IO) {
        // 目标文件不存在，需要更新
        if (!target.exists()) return@withContext true
        
        // 文件大小不同，需要更新
        if (source.length() != target.length()) return@withContext true
        
        // 小于10MB的文件做完整哈希校验
        if (source.length() < 10 * 1024 * 1024) {
            !FileHasher.areFilesEqual(source, target)
        } else {
            // 大文件仅比较修改时间
            source.lastModified() > target.lastModified()
        }
    }
    
    /**
     * 批量并行复制文件到主包（优化版 - 支持增量更新）
     * 只保留文件名，去掉子目录层级
     */
    private suspend fun copyFilesToMainPack(
        sourceDir: File,
        targetDir: File,
        progressCallback: ((current: Int, total: Int, currentFile: String) -> Unit)? = null
    ): CopyResult = withContext(Dispatchers.IO) {
        try {
            // 收集所有文件
            val allFiles = sourceDir.walkTopDown().filter { it.isFile }.toList()
            val total = allFiles.size
            
            val result = com.example.tfgwj.utils.IoOptimizer.parallelProcess(
                items = allFiles,
                action = { sourceFile ->
                    // 只取文件名，去掉所有子目录层级
                    val fileName = sourceFile.name
                    val targetFile = File(targetDir, fileName)
                    
                    // 增量检查：如果文件内容相同则跳过
                    if (com.example.tfgwj.utils.IoOptimizer.needsUpdate(sourceFile, targetFile)) {
                        com.example.tfgwj.utils.IoOptimizer.fastCopy(sourceFile, targetFile)
                    } else {
                        true
                    }
                },
                progressCallback = progressCallback
            )
            
            val success = result.successCount
            val failed = result.failedCount
            val skipped = result.total - success - failed
            
            Log.d(TAG, "复制完成: 成功 $success 个, 跳过 $skipped 个, 失败 $failed 个")
            
            CopyResult(
                success = failed == 0,
                copiedCount = success,
                skippedCount = skipped,
                failedCount = failed,
                errorMessage = if (failed > 0) "有 $failed 个文件复制失败" else null
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "批量复制失败", e)
            CopyResult(false, 0, 0, 0, e.message)
        }
    }
    
    data class CopyResult(
        val success: Boolean,
        val copiedCount: Int,
        val skippedCount: Int = 0,
        val failedCount: Int,
        val errorMessage: String? = null
    )
    
    private fun extractArchiveToCache(archive: ArchiveScanner.ArchiveInfo) {
        lifecycleScope.launch {
            var password: String? = null
            var retryCount = 0
            
            // 如果已知需要密码，先弹窗
            val extractManager = ExtractManager.getInstance()
            if (extractManager.isPasswordRequired(archive.path) && password == null) {
                password = promptForPassword(archive.name, false)
                if (password == null) {
                    Toast.makeText(this@MainActivity, "已取消", Toast.LENGTH_SHORT).show()
                    return@launch
                }
            }
            
            while (retryCount < 3) {
                // 显示进度对话框（带实时进度更新）
                val dialogView = layoutInflater.inflate(R.layout.dialog_progress, null)
                val progressBar = dialogView.findViewById<LinearProgressIndicator>(R.id.progress_bar)
                val tvProgress = dialogView.findViewById<TextView>(R.id.tv_progress)
                val tvCurrentItem = dialogView.findViewById<TextView>(R.id.tv_current_item)
                
                progressBar.isIndeterminate = true
                tvProgress.text = "准备解压..."
                tvCurrentItem.text = "${archive.name} (${archive.sizeText})"
                
                val progressDialog = MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("📦 正在解压")
                    .setView(dialogView)
                    .setCancelable(true)
                    .setNegativeButton("取消") { dialog, _ ->
                        extractManager.cancelExtraction()
                        dialog.dismiss()
                    }
                    .create()
                progressDialog.show()
                
                // 监听解压进度
                var isDismissed = false
                
                val progressJob = launch {
                    extractManager.extractProgress.collectLatest { progress ->
                        if (!isDismissed) {
                            runOnUiThread {
                                progressBar.isIndeterminate = false
                                progressBar.progress = progress
                                tvProgress.text = "解压进度: $progress%"
                            }
                        }
                    }
                }
                
                val statusJob = launch {
                    extractManager.extractStatus.collectLatest { status ->
                        if (!isDismissed) {
                            runOnUiThread {
                                tvCurrentItem.text = status
                            }
                        }
                    }
                }
                
                val result = extractManager.extractToCache(archive.path, password)
                
                isDismissed = true
                progressDialog.dismiss()
                progressJob.cancel()
                statusJob.cancel()
                
                if (result.success) {
                    Toast.makeText(this@MainActivity, "解压成功: ${result.extractedCount} 个文件", Toast.LENGTH_LONG).show()
                    loadPatchVersions()
                    return@launch
                }
                
                // 处理密码错误
                if (result.errorMessage == "需要密码" || result.errorMessage == "密码错误") {
                    val input = promptForPassword(archive.name, result.errorMessage == "密码错误")
                    if (input != null) {
                        password = input
                        retryCount++
                    } else {
                        Toast.makeText(this@MainActivity, "已取消", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                } else {
                    Toast.makeText(this@MainActivity, "解压失败: ${result.errorMessage}", Toast.LENGTH_LONG).show()
                    return@launch
                }
            }
            Toast.makeText(this@MainActivity, "多次尝试失败", Toast.LENGTH_SHORT).show()
        }
    }
    



    private fun applyPatchToMainPack(patch: PatchManager.PatchVersion) {
        val mainPackPath = selectedMainPackPath
        if (mainPackPath == null) {
            Toast.makeText(this, "请先选择主包", Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("应用小包")
            .setMessage("将 ${patch.name} 的 ini 文件复制到主包的 Config 目录？")
            .setPositiveButton("确定") { _, _ ->
                // 显示更新进度对话框
                val dialogView = layoutInflater.inflate(R.layout.dialog_progress, null)
                val progressBar = dialogView.findViewById<LinearProgressIndicator>(R.id.progress_bar)
                val tvProgress = dialogView.findViewById<TextView>(R.id.tv_progress)
                val tvCurrentItem = dialogView.findViewById<TextView>(R.id.tv_current_item)
                
                progressBar.isIndeterminate = true
                tvProgress.text = "准备更新..."
                tvCurrentItem.text = "从: ${patch.name}"
                
                val progressDialog = MaterialAlertDialogBuilder(this)
                    .setTitle("🔄 更新中")
                    .setView(dialogView)
                    .setCancelable(false)
                    .create()
                progressDialog.show()
                
                lifecycleScope.launch {
                    val success = patchManager.applyPatchToMainPack(patch, mainPackPath) { current, total ->
                        runOnUiThread {
                            progressBar.isIndeterminate = false
                            progressBar.max = total
                            progressBar.progress = current
                            tvProgress.text = "进度: $current / $total"
                            tvCurrentItem.text = "已复制: $current 个文件"
                        }
                    }
                    
                    progressDialog.dismiss()
                    
                    if (success) {
                        Toast.makeText(this@MainActivity, "✅ 小包应用成功", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "❌ 小包应用失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeletePatch(patch: PatchManager.PatchVersion) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除小包")
            .setMessage("确定删除 ${patch.name}？\n大小: ${patch.sizeText}")
            .setPositiveButton("删除") { _, _ ->
                // 显示删除进度对话框
                val dialogView = layoutInflater.inflate(R.layout.dialog_progress, null)
                val progressBar = dialogView.findViewById<LinearProgressIndicator>(R.id.progress_bar)
                val tvProgress = dialogView.findViewById<TextView>(R.id.tv_progress)
                val tvCurrentItem = dialogView.findViewById<TextView>(R.id.tv_current_item)
                
                progressBar.isIndeterminate = true
                tvProgress.text = "准备删除..."
                tvCurrentItem.text = patch.name
                
                val progressDialog = MaterialAlertDialogBuilder(this)
                    .setTitle("🗑️ 删除中")
                    .setView(dialogView)
                    .setCancelable(false)
                    .create()
                progressDialog.show()
                
                lifecycleScope.launch {
                    val deleted = patchManager.deletePatchVersionWithProgress(patch) { current, total, currentItem ->
                        runOnUiThread {
                            progressBar.isIndeterminate = false
                            progressBar.max = total
                            progressBar.progress = current
                            tvProgress.text = "进度: $current / $total"
                            tvCurrentItem.text = "正在删除: $currentItem"
                        }
                    }
                    
                    progressDialog.dismiss()
                    
                    if (deleted) {
                        Toast.makeText(this@MainActivity, "✅ 已删除", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "❌ 删除失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    
    override fun onDestroy() {
        super.onDestroy()
        AppLogger.action("应用退出")
        AppLogger.close()
    }

    /**
     * 打开微信并复制作者微信号
     */
    private fun openWechat() {
        val wechatId = getString(R.string.author_wechat)
        
        // 复制到剪贴板
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("微信号", wechatId)
        clipboard.setPrimaryClip(clip)
        
        AppLogger.action("复制微信号", wechatId)
        
        // 尝试打开微信
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.tencent.mm")
            if (intent != null) {
                startActivity(intent)
                Toast.makeText(this, "作者微信号已复制: $wechatId\n请在微信中搜索添加", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "微信未安装\n作者微信号已复制: $wechatId", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "作者微信号已复制: $wechatId", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 打开 GitHub 开源仓库
     */
    private fun openGithub() {
        val githubUrl = getString(R.string.github_url)
        
        AppLogger.action("打开 GitHub", githubUrl)
        
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 清理环境二次确认（显示进度弹窗）
     */
    private fun confirmCleanEnvironment() {
        MaterialAlertDialogBuilder(this)
            .setTitle("🧹 清理环境确认")
            .setMessage("确定要清理 Saved 目录吗？\n\n注意：除 Paks、PandoraV2、ImageDownloadV3 以外的所有文件和文件夹将被删除，用于重置游戏配置环境。")
            .setPositiveButton("立即清理") { _, _ ->
                showCleanProgressDialog()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 显示清理进度弹窗
     */
    private fun showCleanProgressDialog() {
        // 创建进度弹窗
        val dialogView = layoutInflater.inflate(R.layout.dialog_progress, null)
        val progressBar = dialogView.findViewById<LinearProgressIndicator>(R.id.progress_bar)
        val tvProgress = dialogView.findViewById<TextView>(R.id.tv_progress)
        val tvCurrentItem = dialogView.findViewById<TextView>(R.id.tv_current_item)
        
        progressBar.isIndeterminate = true
        tvProgress.text = "正在准备..."
        tvCurrentItem.text = ""
        
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("🧹 清理中")
            .setView(dialogView)
            .setCancelable(false)
            .create()
        
        dialog.show()

        lifecycleScope.launch {
            val packageName = preferencesManager.appPackageName.first()
            val result = SmartCacheManager.cleanEnvironment(
                this@MainActivity,
                packageName,
                shizukuManager
            ) { current, total, currentItem ->
                // 在主线程更新 UI
                runOnUiThread {
                    progressBar.isIndeterminate = false
                    progressBar.max = total
                    progressBar.progress = current
                    tvProgress.text = "进度: $current / $total"
                    tvCurrentItem.text = "正在删除: $currentItem"
                }
            }
            
            dialog.dismiss()
            
            result.onSuccess { count ->
                AppLogger.action("环境清理", "成功删除 $count 个文件/文件夹")
                Toast.makeText(this@MainActivity, "✅ 清理完成，共移除 $count 个项目", Toast.LENGTH_LONG).show()
            }.onFailure { e ->
                AppLogger.e("环境清理", "清理失败", e)
                Toast.makeText(this@MainActivity, "❌ 清理失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 一键启动游戏
     */
    private fun launchGame() {
        // 获取当前选择的应用包名（使用 runBlocking 在非协程上下文中获取）
        val packageName = kotlinx.coroutines.runBlocking {
            preferencesManager.appPackageName.first()
        }
        AppLogger.action("启动游戏", packageName)
        
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                startActivity(intent)
            } else {
                Toast.makeText(this, "未找到游戏：$packageName", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            AppLogger.e("MainActivity", "启动游戏失败", e)
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 验证环境
     * 1. 强制停止目标应用
     * 2. 检测是否需要 Shizuku
     * 3. 更新 UI 显示验证结果
     */
    private fun checkEnvironment(forceRefresh: Boolean = false) {
        lifecycleScope.launch {
            val mainPackCard = binding.includeMainPack.root
            val statusText = mainPackCard.findViewById<TextView>(R.id.tv_env_status)
            val detailText = mainPackCard.findViewById<TextView>(R.id.tv_env_detail)
            val progressBar = mainPackCard.findViewById<ProgressBar>(R.id.progress_env_check)
            
            // 如果已经在授权流程中，不重复触发
            if (statusText.text == "⏳ 等待权限" && !forceRefresh) return@launch

            // 显示加载状态 (只在非强制刷新且没缓存时显示明显加载)
            statusText.text = "检测中..."
            detailText.text = "正在停止应用并验证权限..."
            progressBar.visibility = View.VISIBLE
            
            try {
                // 如果是强制刷新，先强制停止目标应用 (符合用户之前逻辑)
                if (forceRefresh) {
                    val packageName = preferencesManager.appPackageName.first()
                    forceStopApp(packageName)
                }

                // 直接使用权限管理器的统一入口
                // 它内部已经适配了 [Root -> Normal -> Shizuku] 的三层检测逻辑
                val status = permissionManager.checkAllPermissions(forceRefresh)
                
                // 更新 UI
                detailText.text = status.statusMessage
                
                // 根据最佳模式更新 UI
                when (status.bestMode) {
                    PermissionChecker.AccessMode.ROOT -> {
                        statusText.text = "✅ Root"
                        statusText.setTextColor(getColor(R.color.success_color))
                    }
                    PermissionChecker.AccessMode.NATIVE -> {
                        statusText.text = if (Build.VERSION.SDK_INT < 30) "✅ 正常" else "✅ 原生"
                        statusText.setTextColor(getColor(R.color.success_color))
                    }
                    PermissionChecker.AccessMode.SHIZUKU -> {
                        statusText.text = "✅ Shizuku"
                        statusText.setTextColor(getColor(R.color.success_color))
                    }
                    PermissionChecker.AccessMode.NONE -> {
                        // 如果没有识别到最佳模式，但支持 Shizuku，引导授权
                        if (Build.VERSION.SDK_INT >= 30 && !status.hasShizukuPermission) {
                            statusText.text = "⏳ 等待权限"
                            statusText.setTextColor(getColor(R.color.warning_color))
                            
                            if (status.isShizukuAvailable) {
                                if (shizukuManager.isAuthorized.value && !shizukuManager.isServiceConnected.value) {
                                    statusText.text = "⏳ 连接中"
                                    shizukuManager.bindUserService() 
                                } else {
                                    shizukuManager.requestPermission { granted ->
                                        if (granted) checkEnvironment(forceRefresh = true)
                                    }
                                }
                            }
                        } else {
                             statusText.text = "⚠️ 需授权"
                             statusText.setTextColor(getColor(R.color.error_color))
                        }
                    }
                }
                
                if (status.bestMode != PermissionChecker.AccessMode.NONE) {
                    AppLogger.action("环境验证成功", "最佳模式: ${status.bestMode}")
                }
                
            } catch (e: Exception) {
                statusText.text = "❌ 异常"
                statusText.setTextColor(getColor(R.color.error_color))
                detailText.text = "检测失败: ${e.message}"
                AppLogger.e("MainActivity", "环境验证异常", e)
            } finally {
                if (statusText.text != "⏳ 等待权限" && statusText.text != "⏳ 连接中") {
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    /**
     * 强制停止应用
     */
    private suspend fun forceStopApp(packageName: String) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("am", "force-stop", packageName))
            process.waitFor()
            Log.d(TAG, "已强制停止应用: $packageName")
            kotlinx.coroutines.delay(500) // 等待应用完全停止
        } catch (e: Exception) {
            Log.w(TAG, "强制停止应用失败: $packageName", e)
        }
    }

/**
     * 检测是否需要 Shizuku
     * 注意：Root 设备使用 Root 命令验证，非 Root 设备使用普通 API 验证
     */
    private suspend fun checkIfNeedShizuku(packageName: String): Boolean {
        // 直接复用 PermissionChecker 的逻辑，避免重复实现
        val checkResult = PermissionChecker.checkPermissionAccess(packageName, stopAppFirst = false)
        return checkResult.bestMode == PermissionChecker.AccessMode.SHIZUKU
    }

    /**
     * 从 Content URI 获取实际文件路径
     */
    private fun getPathFromContentUri(uri: Uri): String? {
        return try {
            // 尝试从 DocumentsContract 获取路径
            if (android.provider.DocumentsContract.isDocumentUri(this, uri)) {
                val docId = android.provider.DocumentsContract.getDocumentId(uri)
                
                when {
                    // 外部存储
                    uri.authority == "com.android.externalstorage.documents" -> {
                        val split = docId.split(":")
                        if (split[0] == "primary") {
                            "/storage/emulated/0/${split.getOrElse(1) { "" }}"
                        } else {
                            null
                        }
                    }
                    // 下载目录
                    uri.authority == "com.android.providers.downloads.documents" -> {
                        // 尝试直接获取
                        contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                cursor.getString(0)
                            } else null
                        }
                    }
                    // 媒体文件
                    uri.authority == "com.android.providers.media.documents" -> {
                        val split = docId.split(":")
                        val contentUri = when (split[0]) {
                            "image" -> android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            "video" -> android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            "audio" -> android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                            else -> null
                        }
                        contentUri?.let {
                            contentResolver.query(it, arrayOf(android.provider.MediaStore.MediaColumns.DATA), "_id=?", arrayOf(split[1]), null)?.use { cursor ->
                                if (cursor.moveToFirst()) cursor.getString(0) else null
                            }
                        }
                    }
                    else -> null
                }
            } else if (uri.scheme == "file") {
                uri.path
            } else {
                // 尝试通过 content resolver 获取
                contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取文件路径失败", e)
            null
        }
    }

    /**
     * 格式化文件大小
     */
    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L * 1024L -> {
                String.format("%.2f GB", bytes.toDouble() / (1024L * 1024L * 1024L))
            }
            bytes >= 1024L * 1024L -> {
                String.format("%.2f MB", bytes.toDouble() / (1024L * 1024L))
            }
            bytes >= 1024L -> {
                String.format("%.2f KB", bytes.toDouble() / 1024L)
            }
            else -> "$bytes B"
        }
    }
}
