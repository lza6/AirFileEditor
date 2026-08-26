package com.example.tfgwj.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.tfgwj.R
import com.example.tfgwj.domain.model.TaskPhase
import com.example.tfgwj.domain.repository.TaskState
import com.example.tfgwj.utils.AppLogger
import com.example.tfgwj.utils.PauseControl
import com.example.tfgwj.worker.FileReplaceWorkerV2
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 悬浮球管理器
 * 功能：
 * - 显示/隐藏悬浮球
 * - 拖拽功能
 * - 位置记忆
 * - 进度显示
 * - 点击显示详情
 * - 取消任务
 */
class FloatingBallManager(private val context: Context) {
    companion object {
        private const val PREF_NAME = "floating_ball"
        private const val PREF_X = "ball_x"
        private const val PREF_Y = "ball_y"
        private const val DEFAULT_X = 100
        private const val DEFAULT_Y = 500
    }

    private val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private var floatingBallView: View? = null
    private var detailDialog: AlertDialog? = null
    private var lastWorkInfo: androidx.work.WorkInfo? = null // 缓存最新进度，以便重新弹出时立即显示
    private var workId: String? = null
    private var isShowing = false

    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f

    private val scope = CoroutineScope(Dispatchers.Main)

    /**
     * 显示悬浮球
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        AppLogger.d("FloatingBallManager", "📍 show() 被调用")

        if (isShowing) {
            AppLogger.d("FloatingBallManager", "⚠️ 悬浮球已显示，尝试弹出详情")
            showDetailDialog()
            return
        }

        // 检查权限
        if (!android.provider.Settings.canDrawOverlays(context)) {
            AppLogger.e("FloatingBallManager", "❌ 没有悬浮窗权限")
            return
        }

        try {
            // 使用 ContextThemeWrapper 为 applicationContext 注入主题，防止 Material 组件报错
            val themedContext = ContextThemeWrapper(context, R.style.Theme_Tfgwj)
            val layoutInflater = LayoutInflater.from(themedContext)
            floatingBallView = layoutInflater.inflate(R.layout.view_floating_ball, null)

            // 设置初始位置
            val layoutParams =
                WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    // 设置明确的宽高，避免 WRAP_CONTENT 在某些情况下占满全屏
                    width = 150 // 约 56dp (根据屏幕密度可能需要调整，这里先用像素估算或转换)
                    height = 150

                    // 将 dp 转 px
                    val density = context.resources.displayMetrics.density
                    width = (56 * density).toInt()
                    height = (56 * density).toInt()

                    gravity = Gravity.TOP or Gravity.START

                    // 获取屏幕尺寸以确保初始位置合理
                    val displayMetrics = themedContext.resources.displayMetrics
                    val screenWidth = displayMetrics.widthPixels
                    val screenHeight = displayMetrics.heightPixels

                    x = prefs.getInt(PREF_X, screenWidth - width - 50) // 默认靠右
                    y = prefs.getInt(PREF_Y, screenHeight / 2) // 默认居中

                    AppLogger.d("FloatingBallManager", "📐 初始位置: ($x, $y), 尺寸: ${width}x$height, 屏幕: ${screenWidth}x$screenHeight")
                }

            // 设置拖拽监听
            floatingBallView?.setOnTouchListener(
                object : View.OnTouchListener {
                    override fun onTouch(
                        view: View?,
                        event: MotionEvent,
                    ): Boolean {
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                initialX = layoutParams.x
                                initialY = layoutParams.y
                                initialTouchX = event.rawX
                                initialTouchY = event.rawY
                                return true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                                layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                                windowManager.updateViewLayout(floatingBallView, layoutParams)
                                return true
                            }
                            MotionEvent.ACTION_UP -> {
                                // 保存位置
                                prefs.edit()
                                    .putInt(PREF_X, layoutParams.x)
                                    .putInt(PREF_Y, layoutParams.y)
                                    .apply()

                                // 检测点击（没有移动过则视为点击）
                                val distance =
                                    Math.sqrt(
                                        Math.pow((event.rawX - initialTouchX).toDouble(), 2.0) +
                                            Math.pow((event.rawY - initialTouchY).toDouble(), 2.0),
                                    )
                                if (distance < 15) {
                                    onBallClick()
                                }
                                return true
                            }
                        }
                        return false
                    }
                },
            )

            windowManager.addView(floatingBallView, layoutParams)
            isShowing = true
            AppLogger.d("FloatingBallManager", "✅ 悬浮球已成功添加到窗口")

            // 启动实时进度监听
            startRealtimeProgressObserver()
        } catch (e: Exception) {
            AppLogger.e("FloatingBallManager", "❌ 添加悬浮球失败", e)
            isShowing = false
            floatingBallView = null
        }
    }

    /**
     * 隐藏悬浮球
     */
    fun hide() {
        if (!isShowing) return

        floatingBallView?.let {
            windowManager.removeView(it)
        }

        floatingBallView = null
        isShowing = false

        // 关闭详情对话框
        detailDialog?.dismiss()
        detailDialog = null
    }

    /**
     * 设置工作 ID 并开始监听进度
     */
    fun setWorkId(id: String) {
        workId = id

        try {
            val uuid = java.util.UUID.fromString(id)
            WorkManager.getInstance(context).getWorkInfoByIdLiveData(uuid).observeForever { workInfo ->
                updateProgress(workInfo)

                // 任务完成或失败后隐藏悬浮球
                if (workInfo.state == WorkInfo.State.SUCCEEDED ||
                    workInfo.state == WorkInfo.State.FAILED ||
                    workInfo.state == WorkInfo.State.CANCELLED
                ) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        hide()
                    }, 2000) // 延迟 2 秒隐藏，让用户看到最终结果
                }
            }
        } catch (e: Exception) {
            AppLogger.e("FloatingBallManager", "无效的工作 ID: $id", e)
        }
    }

    /**
     * 更新进度
     */
    private fun updateProgress(
        progress: Int,
        processed: Int,
        total: Int,
        currentFile: String,
        mode: String,
        speed: Float,
        phase: TaskPhase = TaskPhase.REPLACING,
    ) {
        if (progress % 10 == 0 || progress == 100) {
            AppLogger.d("FloatingBallManager", "📈 更新进度: $progress%, 速度: $speed MB/s, 文件: $currentFile")
        }

        floatingBallView?.findViewById<CircularProgressIndicator>(R.id.progressRing)?.progress = progress

        // 更新迷你图表
        floatingBallView?.findViewById<MiniChartView>(R.id.miniChart)?.apply {
            visibility = View.VISIBLE
            addPoint(speed)
        }

        val icon = floatingBallView?.findViewById<ImageView>(R.id.floatingBallIcon)
        val progressText = floatingBallView?.findViewById<TextView>(R.id.progressText)

        if (progress >= 100) {
            icon?.setImageResource(R.drawable.ic_status_success)
            progressText?.visibility = View.GONE
        } else {
            icon?.setImageResource(R.drawable.ic_replace)
            progressText?.text = "$progress%"
            progressText?.visibility = View.VISIBLE
        }

        // 更新详情对话框
        detailDialog?.let { dialog ->
            dialog.findViewById<LinearProgressIndicator>(R.id.progressBar)?.progress = progress
            dialog.findViewById<TextView>(R.id.tvProgressPercent)?.text = "$progress%"
            dialog.findViewById<TextView>(R.id.tvFileCount)?.text = "$processed / $total"
            dialog.findViewById<TextView>(R.id.tvCurrentFile)?.text = currentFile

            val modeText =
                when (mode) {
                    "ROOT_BATCH" -> "Root 模式（批量复制 + 验证）"
                    "SHIZUKU_BATCH" -> "Shizuku 模式（批量复制）"
                    "NORMAL" -> "普通模式（逐个复制）"
                    else -> "替换中..."
                }
            dialog.findViewById<TextView>(R.id.tvMode)?.text = "模式: $modeText"

            // 更新速度
            val speedText = String.format("%.1f MB/s", speed)
            dialog.findViewById<TextView>(R.id.tvSpeed)?.text = speedText
        }
    }

    /**
     * 启动实时进度监听
     *
     * 任务状态流统一由 ConfigRepository（经 TaskController）提供，不再在此直接实例化
     * TaskControllerImpl，避免绕过 ConfigRepository 契约、便于注入与测试。
     */
    private fun startRealtimeProgressObserver() {
        val taskController = ConfigRepositoryProvider.getTaskController()
        observeProgress(taskController.state)
    }

    /**
     * 订阅外部注入的任务进度流
     */
    fun observeProgress(flow: Flow<TaskState>) {
        scope.launch {
            flow.collectLatest { state ->
                if (state.total > 0 && state.isReplacing && isShowing) {
                    updateProgress(
                        progress = state.progress,
                        processed = state.processed,
                        total = state.total,
                        currentFile = state.currentFile,
                        mode = "", // 实时控制器暂不带 mode，由 updateProgress 处理
                        speed = state.speed,
                        phase = state.phase,
                    )
                }
            }
        }
    }

    /**
     * 从 WorkInfo 更新进度 (仅作为后备或任务状态管理)
     */
    private fun updateProgress(workInfo: WorkInfo) {
        lastWorkInfo = workInfo

        // 如果实时监听正在运行且数据有效，WorkManager 的更新可以被跳过或仅用于处理完成状态
        if (workInfo.state == WorkInfo.State.SUCCEEDED ||
            workInfo.state == WorkInfo.State.FAILED ||
            workInfo.state == WorkInfo.State.CANCELLED
        ) {
            // 处理最终状态
            updateProgress(
                progress = 100,
                processed = workInfo.progress.getInt(FileReplaceWorkerV2.KEY_PROCESSED, 0),
                total = workInfo.progress.getInt(FileReplaceWorkerV2.KEY_TOTAL, 0),
                currentFile = "完成",
                mode = workInfo.progress.getString(FileReplaceWorkerV2.KEY_MODE) ?: "",
                speed = 0f,
                phase = TaskPhase.COMPLETED,
            )
            return
        }

        val progress = workInfo.progress.getInt(FileReplaceWorkerV2.KEY_PROGRESS, 0)
        val processed = workInfo.progress.getInt(FileReplaceWorkerV2.KEY_PROCESSED, 0)
        val total = workInfo.progress.getInt(FileReplaceWorkerV2.KEY_TOTAL, 0)
        val currentFile = workInfo.progress.getString(FileReplaceWorkerV2.KEY_CURRENT_FILE) ?: ""
        val mode = workInfo.progress.getString(FileReplaceWorkerV2.KEY_MODE) ?: ""
        val speed = workInfo.progress.getFloat("speed", 0f)

        updateProgress(progress, processed, total, currentFile, mode, speed)
    }

    /**
     * 悬浮球点击事件
     */
    private fun onBallClick() {
        showDetailDialog()
    }

    /**
     * 显示详情对话框
     */
    private fun showDetailDialog() {
        if (detailDialog?.isShowing == true) {
            detailDialog?.dismiss()
            return
        }

        // 使用 ContextThemeWrapper 为对话框注入主题，防止 Material 组件和属性解析报错
        val themedContext = ContextThemeWrapper(context, R.style.Theme_Tfgwj)
        val builder = AlertDialog.Builder(themedContext)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.dialog_floating_ball_detail, null)

        builder.setView(view)

        detailDialog =
            builder.create().apply {
                window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                show()
            }

        // 如果有缓存数据，立即更新显示
        lastWorkInfo?.let { updateProgress(it) }

        // 设置隐藏按钮
        view.findViewById<ImageView>(R.id.btnHide)?.setOnClickListener {
            // 只关闭详情对话框，不隐藏悬浮球
            detailDialog?.dismiss()
            detailDialog = null
        }

        // 设置暂停/恢复按钮
        val btnPause = view.findViewById<Button>(R.id.btnPause)
        val btnResume = view.findViewById<Button>(R.id.btnResume)

        // 监控暂停状态
        scope.launch {
            PauseControl.isPaused.collectLatest { isPaused ->
                if (isPaused) {
                    btnPause.visibility = View.GONE
                    btnResume.visibility = View.VISIBLE
                } else {
                    btnPause.visibility = View.VISIBLE
                    btnResume.visibility = View.GONE
                }
            }
        }

        btnPause.setOnClickListener {
            scope.launch { PauseControl.pause() }
        }

        btnResume.setOnClickListener {
            scope.launch { PauseControl.resume() }
        }

        // 设置取消按钮
        view.findViewById<Button>(R.id.btnCancel)?.setOnClickListener {
            workId?.let { id ->
                try {
                    val uuid = java.util.UUID.fromString(id)
                    WorkManager.getInstance(context).cancelWorkById(uuid)
                } catch (e: Exception) {
                    AppLogger.e("FloatingBallManager", "取消任务失败，无效的工作 ID: $id", e)
                }
            }
            // 只有取消任务才彻底隐藏悬浮球
            hide()
            detailDialog?.dismiss()
            detailDialog = null
        }

        // 设置回到前台/隐藏详情按钮
        view.findViewById<Button>(R.id.btnForeground)?.setOnClickListener {
            // 只关闭详情对话框，不隐藏悬浮球
            detailDialog?.dismiss()
            detailDialog = null
        }
    }

    /**
     * 获取悬浮球是否正在显示
     */
    fun isShowing(): Boolean = isShowing
}
