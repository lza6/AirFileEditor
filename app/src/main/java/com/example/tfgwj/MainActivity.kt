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
    
    private lateinit var patchAdapter: PatchVersionAdapter
    
    private var selectedMainPackPath: String? = null
    private var isReplacing = false  // 防止重复替换任务
    private var lockedTime: Long? = null  // 锁定的时间

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
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                handleSelectedFolder(uri)
            }
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

        initManagers()
        initViews()
        setupObservers()
        checkAllPermissions()
        
        // 取消之前未完成的替换任务，防止冷启动时自动恢复执行
        androidx.work.WorkManager.getInstance(this).cancelAllWorkByTag("file_replace")
        
        // 初始加载
        lifecycleScope.launch {
            loadPubgIcon()
            loadWechatIcon() // 动态加载微信图标
            loadMainPacks()
            loadPatchVersions()
            loadLastMainPackPath()  // 加载上次选择的主包路径
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

    private fun initViews() {
        // 菜单按钮
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
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
                else -> false
            }
        }

        // 结果卡片重试按钮
        binding.btnRetry.setOnClickListener {
            lastFailedPath?.let { path ->
                AppLogger.action("点击重试", path)
                startReplaceToGame()
            }
        }

        // 权限卡片
        binding.btnRequestPermission.setOnClickListener {
            requestPermissions()
        }

        // 主包区域
        val mainPackCard = binding.includeMainPack.root
        mainPackCard.findViewById<MaterialButton>(R.id.btn_select_main_pack).setOnClickListener {
            selectMainPackFolder()
        }
        mainPackCard.findViewById<MaterialButton>(R.id.btn_random_time).setOnClickListener {
            randomizeFileTime()
        }
        // 时间框点击 - 显示时间选择器
        mainPackCard.findViewById<LinearLayout>(R.id.layout_file_time).setOnClickListener {
            showTimePickerDialog()
        }
        // 锁定时间按钮
        mainPackCard.findViewById<ImageButton>(R.id.btn_lock_time).setOnClickListener {
            lockCurrentTime()
        }
        // 应用锁定时间按钮
        mainPackCard.findViewById<ImageButton>(R.id.btn_apply_locked_time).setOnClickListener {
            applyLockedTime()
        }
        mainPackCard.findViewById<MaterialButton>(R.id.btn_start_replace_main).setOnClickListener {
            startReplaceToGame()
        }
        
        // 【新增】一键启动游戏
        mainPackCard.findViewById<MaterialButton>(R.id.btn_launch_game).setOnClickListener {
            launchGame()
        }

        mainPackCard.findViewById<MaterialButton>(R.id.btn_clean_env).setOnClickListener {
            confirmCleanEnvironment()
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
            scanArchives()
        }
        updatePackCard.findViewById<MaterialButton>(R.id.btn_refresh_patches).setOnClickListener {
            loadPatchVersions()
        }
        
        // 解压并更新到主包
        updatePackCard.findViewById<MaterialButton>(R.id.btn_extract_and_update).setOnClickListener {
            scanAndExtractArchive()
        }

        // 进度卡片
        binding.btnCancelReplace.setOnClickListener {
            androidx.work.WorkManager.getInstance(this).cancelAllWorkByTag("file_replace")
        }
    }


    private fun setupObservers() {
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
            }
        }
        lifecycleScope.launch {
            shizukuManager.isServiceConnected.collectLatest { connected ->
                Log.d(TAG, "Shizuku 服务连接状态变更: $connected")
                val status = permissionManager.permissionStatus.value
                if (connected != status.isShizukuServiceConnected) {
                    checkAllPermissions()
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
        
        val icon = when {
            status.canAccessPrivateDir -> R.drawable.ic_status_success
            status.hasManageStorage -> R.drawable.ic_status_unknown
            else -> R.drawable.ic_status_error
        }
        binding.ivPermissionStatus.setImageResource(icon)

        binding.btnRequestPermission.visibility = when {
            !status.hasManageStorage -> View.VISIBLE
            status.needsShizuku && !status.hasShizukuPermission -> View.VISIBLE
            else -> View.GONE
        }

        binding.btnRequestPermission.text = when {
            !status.hasManageStorage -> "授权存储权限"
            status.needsShizuku && !status.isShizukuAvailable -> "安装 Shizuku"
            status.needsShizuku && !status.hasShizukuPermission -> "授权 Shizuku"
            else -> "授权"
        }
    }

    private fun requestPermissions() {
        lifecycleScope.launch {
            val status = permissionManager.checkAllPermissions()
            
            when {
                !status.hasManageStorage -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        permissionManager.requestManageStoragePermission(this@MainActivity, manageStorageLauncher)
                    } else {
                        permissionManager.requestStoragePermission(storagePermissionLauncher)
                    }
                }
                status.needsShizuku && !status.isShizukuAvailable -> {
                    // 跳转到应用商店或官网下载 Shizuku
                    Toast.makeText(this@MainActivity, "请安装并启动 Shizuku", Toast.LENGTH_LONG).show()
                }
                status.needsShizuku && !status.hasShizukuPermission -> {
                    permissionManager.requestShizukuPermission { granted ->
                        if (granted) {
                            checkAllPermissions()
                        }
                    }
                }
            }
        }
    }

    private fun loadPubgIcon() {
        lifecycleScope.launch {
            val icon = AppIconHelper.getPubgIcon(this@MainActivity)
            val name = AppIconHelper.getPubgAppName(this@MainActivity)
            
            val mainPackCard = binding.includeMainPack.root
            val iconView = mainPackCard.findViewById<ImageView>(R.id.iv_pubg_icon)
            val nameView = mainPackCard.findViewById<TextView>(R.id.tv_pubg_name)
            
            if (icon != null) {
                iconView.setImageDrawable(icon)
            }
            nameView.text = name
        }
    }

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
        lifecycleScope.launch {
            mainPackManager.scanMainPacks()
            
            val packs = mainPackManager.mainPacks.value
            if (packs.isNotEmpty()) {
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
        val path = getPathFromUri(uri)
        if (path != null) {
            selectedMainPackPath = path
            
            val mainPackCard = binding.includeMainPack.root
            mainPackCard.findViewById<TextView>(R.id.tv_selected_main_pack).text = path
            
            // 立即显示当前文件夹时间
            val fileTime = FileTimeModifier.getFileTime(path)
            if (fileTime != null) {
                val timeStr = FileTimeModifier.formatTime(fileTime)
                mainPackCard.findViewById<TextView>(R.id.tv_current_file_time).text = "当前时间: $timeStr"
                mainPackCard.findViewById<TextView>(R.id.tv_main_pack_time).text = timeStr
            }
            
            // 保存路径（主包路径和文件夹路径都保存）
            lifecycleScope.launch {
                preferencesManager.saveLastSelectedFolderPath(path)
                preferencesManager.saveLastMainPackPath(path)
            }
        }
    }

    private fun getPathFromUri(uri: Uri): String? {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
        val split = docId.split(":")
        return if (split.size >= 2 && split[0] == "primary") {
            "/storage/emulated/0/${split[1]}"
        } else {
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

        isReplacing = true  // 标记为正在替换
        AppLogger.action("开始替换", path)
        
        // 智能检测：处理 .pixuicache 文件夹优化
        val cacheResult = SmartCacheManager.checkAndOptimize(this, shizukuManager)
        if (cacheResult != null) {
            AppLogger.action("智能优化", cacheResult)
        }
        
        // 显示替换进度对话框
        showReplaceProgressDialog(path)
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
            .setNegativeButton("取消") { dialog, _ ->
                // 取消 WorkManager 任务
                androidx.work.WorkManager.getInstance(this).cancelAllWorkByTag("file_replace")
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
        // 使用 WorkManager 在后台执行
        val workRequest = com.example.tfgwj.worker.FileReplaceWorker.createWorkRequest(
            path, 
            PermissionChecker.PUBG_PACKAGE_NAME
        )
        
        val workManager = androidx.work.WorkManager.getInstance(this)
        workManager.enqueue(workRequest)
        
        var errorCount = 0
        var lastLoggedFile = ""
        var startTime: Long = 0
        var lastProcessed = 0
        var lastUpdateTime: Long = 0
        
        // 监听进度
        workManager.getWorkInfoByIdLiveData(workRequest.id).observe(this) { workInfo ->
            if (workInfo != null) {
                when (workInfo.state) {
                    androidx.work.WorkInfo.State.RUNNING -> {
                        val progress = workInfo.progress.getInt(
                            com.example.tfgwj.worker.FileReplaceWorker.KEY_PROGRESS, 0
                        )
                        val processed = workInfo.progress.getInt(
                            com.example.tfgwj.worker.FileReplaceWorker.KEY_PROCESSED, 0
                        )
                        val total = workInfo.progress.getInt(
                            com.example.tfgwj.worker.FileReplaceWorker.KEY_TOTAL, 0
                        )
                        val currentFile = workInfo.progress.getString(
                            com.example.tfgwj.worker.FileReplaceWorker.KEY_CURRENT_FILE
                        ) ?: ""
                        
                        // 记录开始时间
                        if (startTime == 0L) {
                            startTime = System.currentTimeMillis()
                            lastUpdateTime = startTime
                        }
                        
                        progressBar.progress = progress
                        tvPercent.text = "$progress%"
                        tvFileCount.text = "$processed / $total"
                        tvCurrentFile.text = currentFile
                        
                        // 计算速度和预估时间（每秒更新一次）
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastUpdateTime >= 1000 && processed > lastProcessed) {
                            val elapsedTime = (currentTime - startTime) / 1000.0 // 秒
                            val processedDiff = processed - lastProcessed
                            val timeDiff = (currentTime - lastUpdateTime) / 1000.0
                            
                            // 计算速度（文件/秒）
                            val speed = if (timeDiff > 0) processedDiff / timeDiff else 0.0
                            tvSpeed.text = "速度: ${String.format("%.1f", speed)} 文件/秒"
                            
                            // 计算预估剩余时间
                            val remaining = total - processed
                            val etaSeconds = if (speed > 0) remaining / speed else 0.0
                            tvEta.text = if (etaSeconds > 60) {
                                val minutes = (etaSeconds / 60).toInt()
                                val seconds = (etaSeconds % 60).toInt()
                                "预计剩余: ${minutes}分${seconds}秒"
                            } else {
                                "预计剩余: ${etaSeconds.toInt()}秒"
                            }
                            
                            lastProcessed = processed
                            lastUpdateTime = currentTime
                        }
                        
                        // 记录日志（检查是否是错误或新文件）
                        if (currentFile.isNotEmpty() && currentFile != lastLoggedFile) {
                            if (currentFile.startsWith("[失败]")) {
                                errorCount++
                                appendLog("❌ $currentFile")
                                tvErrors.visibility = View.VISIBLE
                                tvErrors.text = "错误: $errorCount 个文件复制失败"
                            } else {
                                // 每 50 个文件记录一次日志，避免日志过多
                                if (processed % 50 == 0 || processed <= 5) {
                                    appendLog("📄 $currentFile")
                                }
                            }
                            lastLoggedFile = currentFile
                        }
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
                        
                        // 延迟关闭对话框
                        lifecycleScope.launch {
                            kotlinx.coroutines.delay(1500)
                            replaceDialog?.dismiss()
                            showSuccessResult(processed, failedFiles.size)
                            resetReplacingState()
                        }
                    }
                    androidx.work.WorkInfo.State.FAILED -> {
                        val errorMsg = workInfo.outputData.getString(
                            com.example.tfgwj.worker.FileReplaceWorker.KEY_ERROR_MESSAGE
                        ) ?: "替换失败，请查看日志"
                        AppLogger.e("MainActivity", "替换失败: $errorMsg")
                        
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
    
    private fun showSuccessResult(fileCount: Int, failedCount: Int = 0) {
        val toastMessage = if (failedCount > 0) {
            "替换完成！$fileCount 个文件成功，$failedCount 个失败"
        } else {
            "替换完成！$fileCount 个文件"
        }
        Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()
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
                    val configTargetPath = "$selectedMainPackPath/Android/data/com.tencent.tmgp.pubgmhd/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/Config/Android"
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
            
            // 使用协程并发限制，避免 OOM
            val semaphore = kotlinx.coroutines.sync.Semaphore(permits = 16) // 最多16个并发
            
            val successCount = java.util.concurrent.atomic.AtomicInteger(0)
            val failedCount = java.util.concurrent.atomic.AtomicInteger(0)
            val skippedCount = java.util.concurrent.atomic.AtomicInteger(0)
            
            coroutineScope {
                val deferredList = allFiles.map { sourceFile ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            try {
                                // 只取文件名，去掉所有子目录层级
                                val fileName = sourceFile.name
                                val targetFile = File(targetDir, fileName)
                                
                                // 增量检查：如果文件内容相同则跳过
                                val needsUpdateResult = needsUpdate(sourceFile, targetFile)
                                if (needsUpdateResult) {
                                    // 使用 NIO 快速复制
                                    java.nio.file.Files.copy(
                                        sourceFile.toPath(),
                                        targetFile.toPath(),
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                                    )
                                    successCount.incrementAndGet()
                                } else {
                                    skippedCount.incrementAndGet()
                                }
                                
                                val current = successCount.get() + skippedCount.get()
                                progressCallback?.invoke(current, total, fileName)
                                true
                            } catch (e: Exception) {
                                Log.e(TAG, "复制文件失败: ${sourceFile.name}", e)
                                failedCount.incrementAndGet()
                                false
                            }
                        }
                    }
                }
                deferredList.awaitAll()
            }
            
            val skipped = skippedCount.get()
            val success = successCount.get()
            val failed = failedCount.get()
            
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
            val result = SmartCacheManager.cleanEnvironment(
                this@MainActivity,
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
        val packageName = PermissionChecker.PUBG_PACKAGE_NAME
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
