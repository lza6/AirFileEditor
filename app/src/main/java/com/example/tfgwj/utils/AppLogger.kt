package com.example.tfgwj.utils

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Environment
import android.os.Process
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/**
 * 日志管理器
 * - 日志保存在 /听风改文件/logs/
 * - 文件大小限制 10MB
 * - 每次启动清空上次日志
 * - 记录所有操作细节
 * - 内存敏感型日志队列
 * - 批量写入减少 I/O
 */
object AppLogger {
    
    private const val TAG = "AppLogger"
    private const val MAX_LOG_SIZE = 10 * 1024 * 1024L  // 10MB
    private const val LOG_FILE_NAME = "app_log.txt"
    
    private var logFile: File? = null
    private var printWriter: PrintWriter? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    
    // 使用 Channel 限制待写入日志数量（内存敏感）
    private val logChannel = Channel<String>(
        capacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    
    // 异步日志作用域，使用单线程确保日志顺序
    private val logScope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher() + SupervisorJob())
    
    // 批量写入标志
    private var isFlushing = false
    
    /**
     * 初始化日志
     * @param context 用于获取备选路径
     */
    fun init(context: android.content.Context? = null) {
        if (printWriter != null && logFile != null && logFile!!.absolutePath.contains("听风改文件")) {
            // 如果已经初始化在外部存储，直接返回
            return
        }
        
        try {
            // 默认优先尝试私有目录，保证刚安装时也能记录
            var dir = context?.getExternalFilesDir("logs") ?: File(Environment.getExternalStorageDirectory(), "听风改文件/logs")
            
            // 如果有权限且外部目录可用，则尝试使用外部目录
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R || 
                Environment.isExternalStorageManager()) {
                val publicDir = File(Environment.getExternalStorageDirectory(), "听风改文件/logs")
                if (publicDir.exists() || publicDir.mkdirs()) {
                    dir = publicDir
                }
            }
            
            logFile = File(dir, LOG_FILE_NAME)
            if (!logFile!!.exists()) logFile?.createNewFile()
            
            checkFileSize()
            
            // 重新打开流
            printWriter?.close()
            printWriter = PrintWriter(FileWriter(logFile!!, true), true)
            
            // 启动批量写入协程
            startBatchWriter()
            
            // 注册低内存回调
            context?.let { registerLowMemoryCallback(it) }
            
            writeHeader()
            Log.d(TAG, "日志系统初始化: ${logFile!!.absolutePath}")
            
        } catch (e: Exception) {
            Log.e(TAG, "初始化日志失败", e)
        }
    }
    
    /**
     * 注册低内存警告处理
     */
    fun registerLowMemoryCallback(context: Context) {
        context.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
                    flushAndReduceBuffer()
                }
            }
            
            override fun onLowMemory() {
                flushAndReduceBuffer()
            }
            
            override fun onConfigurationChanged(c: Configuration) {}
        })
    }
    
    /**
     * 低内存时刷新日志并减少缓冲区
     */
    private fun flushAndReduceBuffer() {
        Log.w(TAG, "内存不足，刷新日志缓冲区")
        logScope.launch {
            flushLogs()
            // 清空 Channel 以释放内存
            while (logChannel.tryReceive().getOrNull() != null) {
                // 丢弃所有待写入日志
            }
        }
    }

    /**
     * 授权完成后调用，尝试切换到外部存储
     */
    fun reInitAfterPermission(context: android.content.Context) {
        Log.d(TAG, "权限变更，刷新日志路径...")
        init(context)
    }
    
    private fun writeHeader() {
        val header = """
            听风改文件 日志记录
            启动时间: ${dateFormat.format(Date())}
            设备信息: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}
            Android 版本: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})
            ----------------------------------------------------------------
        """.trimIndent()
        printWriter?.println(header)
    }
    
    fun i(tag: String, message: String) = log("INFO", tag, message)
    fun d(tag: String, message: String) = log("DEBUG", tag, message)
    fun w(tag: String, message: String) = log("WARN", tag, message)

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        log("ERROR", tag, message)
        throwable?.let {
            val errorLog = "  异常堆栈: ${Log.getStackTraceString(it)}"
            logScope.launch {
                try {
                    logChannel.trySend(errorLog)
                } catch (e: Exception) {
                    // 忽略
                }
            }
        }
        Log.e(tag, message, throwable)
    }
    
    fun action(action: String, details: String = "") {
        val msg = if (details.isNotEmpty()) "$action | $details" else action
        log("ACTION", "USER", msg)
    }
    
    /**
     * 记录函数执行
     */
    fun func(functionName: String, action: String, success: Boolean, details: String = "") {
        val result = if (success) "SUCCESS" else "FAILED"
        val msg = "$action | Function: $functionName | Result: $result | $details"
        log("FUNC", "APP", msg)
    }
    
    fun file(operation: String, path: String, success: Boolean = true, error: String? = null) {
        val status = if (success) "SUCCESS" else "FAILED"
        val msg = "$operation | Result: $status | Path: $path"
        log("FILE", "IO", if (error != null) "$msg | ERR: $error" else msg)
    }
    
    fun progress(current: Int, total: Int, currentFile: String) {
        val percent = if (total > 0) (current * 100 / total) else 0
        log("PROGRESS", "REPLACE", "[$percent%] $current/$total | $currentFile")
    }
    
    /**
     * 批量写入日志到文件，减少 I/O 操作
     */
    private suspend fun flushLogs() {
        if (isFlushing) return
        isFlushing = true
        
        try {
            val batch = mutableListOf<String>()
            while (logChannel.tryReceive().getOrNull()?.also { batch.add(it) } != null) {
                if (batch.size >= 50) break
            }
            
            if (batch.isNotEmpty()) {
                printWriter?.println(batch.joinToString("\n"))
            }
        } catch (e: Exception) {
            // 忽略异常
        } finally {
            isFlushing = false
        }
    }
    
    /**
     * 启动批量写入协程
     */
    private fun startBatchWriter() {
        logScope.launch {
            while (isActive) {
                delay(500) // 每 500ms 批量写入一次
                flushLogs()
            }
        }
    }
    
    private fun log(level: String, tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val logLine = "[$timestamp] [$level] [$tag] $message"
        
        // 系统日志立即打印
        Log.println(when(level) {
            "ERROR" -> Log.ERROR
            "WARN" -> Log.WARN
            "INFO" -> Log.INFO
            else -> Log.DEBUG
        }, tag, message)

        // 文件日志通过 Channel 异步写入
        logScope.launch {
            try {
                logChannel.trySend(logLine)
            } catch (e: Exception) {
                // Channel 满时自动丢弃最旧的日志
            }
        }
    }
    
    /**
     * 检查文件大小，采用低内存消耗的方式保留末尾
     */
    private fun checkFileSize() {
        val file = logFile ?: return
        if (file.length() > MAX_LOG_SIZE) {
            try {
                // 仅保留最后 2MB。使用 RandomAccessFile 避免将整个 10MB 读入内存
                val keepSize = 2 * 1024 * 1024L
                val tempFile = File(file.parent, "log_temp.txt")
                
                java.io.RandomAccessFile(file, "r").use { raf ->
                    val startPos = file.length() - keepSize
                    raf.seek(if (startPos > 0) startPos else 0)
                    
                    java.io.FileOutputStream(tempFile).use { fos ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        fos.write("═══ 日志已清理 (保留最新) ═══\n".toByteArray())
                        while (raf.read(buffer).also { read = it } != -1) {
                            fos.write(buffer, 0, read)
                        }
                    }
                }
                
                printWriter?.close()
                if (file.delete()) {
                    tempFile.renameTo(file)
                }
                printWriter = PrintWriter(FileWriter(file, true), true)
                
            } catch (e: Exception) {
                Log.e(TAG, "清理日志 OOM 保护失败", e)
            }
        }
    }
    
    fun separator(title: String = "") {
        printWriter?.println(if (title.isNotEmpty()) "--- $title ---" else "--------------------------------")
    }
    
    fun close() {
        try {
            printWriter?.println("\n--- 日志结束: ${dateFormat.format(Date())} ---")
            printWriter?.close()
            printWriter = null
        } catch (e: Exception) {
            Log.e(TAG, "关闭日志失败", e)
        }
    }
    
    fun getLogContent(): String = logFile?.readText() ?: "无法读取日志"
    
    fun getLogSize(): String {
        val size = logFile?.length() ?: 0
        return when {
            size >= 1024 * 1024 -> String.format("%.2f MB", size / 1024.0 / 1024.0)
            size >= 1024 -> String.format("%.2f KB", size / 1024.0)
            else -> "$size B"
        }
    }
}
