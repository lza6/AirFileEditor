package com.example.tfgwj.shizuku

import android.util.Log
import com.example.tfgwj.IFileOperationService
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.system.exitProcess

/**
 * Shizuku UserService 实现
 * 此服务在 Shizuku 特权进程中运行，具有 root 或 adb 权限
 */
class FileOperationService : IFileOperationService.Stub() {
    companion object {
        private const val TAG = "FileOperationService"

        // 命令白名单（fail-closed）：仅允许文件操作/诊断类命令
        private val ALLOWED_COMMANDS =
            setOf(
                "ls",
                "pidof",
                "stat",
                "find",
                "sync",
                "mkdir",
                "cp",
                "touch",
                "mv",
                "rm",
                "pm",
                "am",
            )

        // 允许操作的根目录（canonical 前缀）
        private val ALLOWED_PATH_ROOTS =
            listOf(
                "/storage/emulated/0/Android/data/",
                "/storage/emulated/0/Android/obb/",
            )

        fun isAllowedCommand(command: String): Boolean {
            val firstToken = command.trim().split(Regex("\\s+")).firstOrNull() ?: return false
            return firstToken in ALLOWED_COMMANDS
        }

        fun isAllowedPath(path: String): Boolean {
            return try {
                val canonical = File(path).canonicalPath.replace('\\', '/')
                // Windows 测试环境 canonical 可能带盘符前缀（如 "C:/storage/..."），
                // 归一化后仍按白名单根匹配；Android 真机上无盘符，行为不变。
                val withoutDrive = canonical.substringAfter(':')
                ALLOWED_PATH_ROOTS.any { canonical.startsWith(it) || withoutDrive.startsWith(it) }
            } catch (e: Exception) {
                false
            }
        }
    }

    // 复制状态数据类
    private data class CopyState(
        var current: Int = 0,
        var errorCount: Int = 0,
        var lastReportedCount: Int = 0,
        var lastReportTime: Long = 0L,
    )

    init {
        Log.d(TAG, "FileOperationService 已创建，运行在 UID: ${android.os.Process.myUid()}")
    }

    /**
     * 销毁服务
     */
    override fun destroy() {
        Log.d(TAG, "FileOperationService 正在销毁...")
        exitProcess(0)
    }

    /**
     * 检查服务是否存活
     */
    override fun isAlive(): Boolean {
        return true
    }

    /**
     * 创建目录（路径必须在允许根目录下）
     */
    override fun createDirectory(path: String): Boolean {
        if (!isAllowedPath(path)) {
            Log.w(TAG, "createDirectory 越界拒绝: $path")
            return false
        }
        return try {
            val dir = File(path)
            if (!dir.exists()) {
                dir.mkdirs()
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建目录失败: $path", e)
            false
        }
    }

    /**
     * 删除文件或目录（路径必须在允许根目录下）
     */
    override fun deleteFile(path: String): Boolean {
        if (!isAllowedPath(path)) {
            Log.w(TAG, "deleteFile 越界拒绝: $path")
            return false
        }
        return try {
            val file = File(path)
            if (file.isDirectory) {
                deleteRecursively(file)
            } else {
                file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "删除失败: $path", e)
            false
        }
    }

    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                deleteRecursively(child)
            }
        }
        return file.delete()
    }

    /**
     * 复制文件 (使用 cp -p 保留属性)
     * 自动创建目标文件夹（如果不存在）
     */
    override fun copyFile(
        sourcePath: String,
        targetPath: String,
    ): Boolean {
        if (!isAllowedPath(targetPath)) {
            Log.w(TAG, "copyFile 目标越界拒绝: $targetPath")
            return false
        }
        // 先确保目标目录存在
        val targetFile = File(targetPath)
        val parentDir = targetFile.parent
        if (parentDir != null) {
            val mkdirCmd = "mkdir -p \"$parentDir\""
            val mkdirResult = executeCommand(mkdirCmd)
            if (mkdirResult != 0) {
                Log.e(TAG, "创建目标目录失败: $parentDir, exitCode=$mkdirResult")
            }
        }

        // 使用 shell cp 命令，保留时间戳 (-p) 和权限，捕获错误输出
        val cmd = "cp -p \"$sourcePath\" \"$targetPath\" 2>&1"
        Log.d(TAG, "执行复制: $cmd")

        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val errorOutput = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0 || errorOutput.isNotEmpty()) {
                Log.e(TAG, "复制失败: $sourcePath -> $targetPath, exitCode=$exitCode, error=$errorOutput")
            }
            return exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "复制异常: $sourcePath -> $targetPath", e)
            return false
        }
    }

    /**
     * 复制目录 (递归, 使用 cp -r -p)
     */
    override fun copyDirectory(
        sourcePath: String,
        targetPath: String,
    ): Boolean {
        if (!isAllowedPath(targetPath)) {
            Log.w(TAG, "copyDirectory 目标越界拒绝: $targetPath")
            return false
        }
        // 确保目标父目录存在 (mkdir -p)
        val targetFile = File(targetPath)
        val parentDir = targetFile.parent
        if (parentDir != null) {
            executeCommand("mkdir -p \"$parentDir\"")
        }

        val cmd = "cp -r -p \"$sourcePath\" \"$targetPath\""
        Log.d(TAG, "执行目录复制: $cmd")
        return executeCommand(cmd) == 0
    }

    /**
     * 检查文件是否存在
     */
    override fun fileExists(path: String): Boolean {
        return try {
            File(path).exists()
        } catch (e: Exception) {
            Log.e(TAG, "检查文件存在失败: $path", e)
            false
        }
    }

    /**
     * 内部命令执行（带白名单校验）。
     * 此方法为内部方法，不通过 AIDL 暴露。fail-closed 白名单仍然生效。
     */
    internal fun executeCommand(command: String): Int {
        if (!isAllowedCommand(command)) {
            Log.w(TAG, "命令白名单拒绝: $command")
            return -1
        }
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            process.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "执行命令失败: $command", e)
            -1
        }
    }

    /**
     * 内部命令执行并返回输出（带白名单校验）。
     * 此方法为内部方法，不通过 AIDL 暴露。
     */
    internal fun executeCommandWithOutput(command: String): String {
        if (!isAllowedCommand(command)) {
            Log.w(TAG, "命令白名单拒绝: $command")
            return ""
        }
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output
        } catch (e: Exception) {
            Log.e(TAG, "执行命令失败: $command", e)
            ""
        }
    }

    /**
     * 停止应用（包名必须合法）
     */
    override fun stopApp(packageName: String): Boolean {
        if (!packageName.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$"))) {
            Log.w(TAG, "stopApp 非法包名拒绝: $packageName")
            return false
        }
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "am force-stop \"$packageName\""))
            process.waitFor() == 0
        } catch (e: Exception) {
            Log.e(TAG, "stopApp 失败: $packageName", e)
            false
        }
    }

    /**
     * 检查应用是否运行（包名必须合法）
     */
    override fun isAppRunning(packageName: String): Boolean {
        if (!packageName.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$"))) {
            Log.w(TAG, "isAppRunning 非法包名拒绝: $packageName")
            return false
        }
        val output = executeCommandWithOutput("pidof \"$packageName\"")
        return output.trim().isNotEmpty()
    }

    /**
     * 复制目录并带进度回调 (高性能优化版)
     */
    override fun copyDirectoryWithProgress(
        sourcePath: String,
        targetPath: String,
        callback: com.example.tfgwj.ICopyCallback?,
    ) {
        Thread {
            try {
                // 1. 获取文件总数 (使用 find 命令，速度快)
                val countCmd = "find \"$sourcePath\" -type f | wc -l"
                val countProcess = Runtime.getRuntime().exec(arrayOf("sh", "-c", countCmd))
                val totalFiles = countProcess.inputStream.bufferedReader().readText().trim().toIntOrNull() ?: 0
                countProcess.waitFor()

                Log.d(TAG, "待复制文件总数: $totalFiles")

                if (totalFiles == 0) {
                    callback?.onCompleted(0)
                    return@Thread
                }

                // 2. 确保目标父目录存在
                val targetFile = File(targetPath)
                val parentDir = targetFile.parent
                if (parentDir != null) {
                    executeCommand("mkdir -p \"$parentDir\"")
                }

                // 3. 执行复制 (cp -v -r -p)
                val cpCmd = "cp -v -r -p \"$sourcePath/.\" \"$targetPath/\""
                Log.d(TAG, "执行批量复制: $cpCmd")

                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cpCmd))

                // 同时读取 stdout 和 stderr
                val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
                val stderrReader = BufferedReader(InputStreamReader(process.errorStream))

                val state = CopyState()
                var line: String?

                // 先读取 stdout
                var stdoutLineCount = 0
                while (stdoutReader.readLine().also { line = it } != null) {
                    stdoutLineCount++
                    if (stdoutLineCount <= 5) {
                        Log.d(TAG, "stdout[$stdoutLineCount]: $line")
                    }
                    processLine(line!!, state, totalFiles, callback)
                }
                Log.d(TAG, "stdout 总共读取 $stdoutLineCount 行")

                // 再读取 stderr（可能包含错误信息和详细输出）
                var stderrLineCount = 0
                while (stderrReader.readLine().also { line = it } != null) {
                    stderrLineCount++
                    val text = line!!
                    Log.d(TAG, "stderr[$stderrLineCount]: $text")
                    processLine(text, state, totalFiles, callback)
                }
                Log.d(TAG, "stderr 总共读取 $stderrLineCount 行")

                val exitCode = process.waitFor()

                // 执行 sync 确保数据写入磁盘
                Runtime.getRuntime().exec("sync").waitFor()

                // 计算错误率
                val errorRate = if (totalFiles > 0) state.errorCount.toFloat() / totalFiles else 0f

                Log.d(
                    TAG,
                    "复制统计: 成功=${state.current}, 错误=${state.errorCount}, 总数=$totalFiles, 退出码=$exitCode, 错误率=${String.format(
                        "%.2f",
                        errorRate * 100,
                    )}%",
                )

                // 只有在退出码为 0（成功）时才视为完成
                if (exitCode == 0) {
                    // 如果 state.current 为 0 但命令成功，可能是 cp -v 输出格式不匹配
                    // 尝试重新统计目标目录中的文件数
                    if (state.current == 0) {
                        Log.w(TAG, "state.current 为 0 但命令成功，可能输出格式不匹配，尝试重新统计...")
                        val recountCmd = "find \"$targetPath\" -type f | wc -l"
                        val recountProc = Runtime.getRuntime().exec(arrayOf("sh", "-c", recountCmd))
                        val actualCount = recountProc.inputStream.bufferedReader().readText().trim().toIntOrNull() ?: 0
                        recountProc.waitFor()
                        Log.d(TAG, "重新统计目标目录文件数: $actualCount")

                        // 如果目标目录有文件，则使用实际计数
                        if (actualCount > 0) {
                            state.current = actualCount
                            // 通知进度更新
                            callback?.onProgress(actualCount, totalFiles, "完成")
                        } else {
                            Log.w(TAG, "重新统计后仍为 0，检查目标目录...")
                            // 尝试列出目录内容
                            val lsCmd = "ls -la \"$targetPath\" 2>/dev/null | head -20"
                            val lsProc = Runtime.getRuntime().exec(arrayOf("sh", "-c", lsCmd))
                            val lsOutput = lsProc.inputStream.bufferedReader().readText()
                            lsProc.waitFor()
                            Log.d(TAG, "目标目录内容:\n$lsOutput")
                        }
                    }

                    if (state.current > 0) {
                        if (state.errorCount > 0) {
                            Log.w(TAG, "复制完成但有 ${state.errorCount} 个文件失败 (错误率: ${String.format("%.2f", errorRate * 100)}%)")
                            callback?.onError("复制完成但有 ${state.errorCount} 个错误")
                        }
                        callback?.onCompleted(state.current)
                    } else {
                        Log.w(TAG, "复制命令成功但没有统计到任何文件，可能输出格式问题或目标目录为空")
                        callback?.onCompleted(0)
                    }
                } else {
                    Log.e(TAG, "复制命令失败，退出码: $exitCode")
                    callback?.onError("复制失败，退出码: $exitCode")
                }
            } catch (e: Exception) {
                Log.e(TAG, "批量复制失败", e)
                try {
                    callback?.onError(e.message)
                } catch (ignore: Exception) {
                }
            }
        }.start()
    }

    /**
     * 清理目录（删除指定目录下的所有内容，可指定白名单）
     *
     * 优化策略：
     * 1. 先用 find 统计待删除的实际文件/文件夹总数
     * 2. 逐个删除顶层目录（每个 rm -rf 在后台等待完成）
     * 3. 实时报告当前正在删除的目录
     */

    /**
     * 处理 cp 命令输出行
     */
    private fun processLine(
        text: String,
        state: CopyState,
        totalFiles: Int,
        callback: com.example.tfgwj.ICopyCallback?,
    ) {
        // cp -v 输出格式可能是: 'source' -> 'target' 或 "removed 'source'" 或其他格式
        // 我们尝试从输出中提取文件名
        val fileName =
            when {
                // 格式: '/path/to/source.txt' -> '/path/to/target.txt'
                text.contains(" -> ") -> {
                    text.substringBefore(" ->").trim('\'').substringAfterLast("/")
                }
                // 格式: "'/path/to/file.xxx'" - 匹配常见文件扩展名
                text.contains("'") && (text.contains(".") || text.contains("/")) -> {
                    val trimmed = text.trim('\'')
                    // 检查是否包含路径分隔符
                    if (trimmed.contains("/")) {
                        trimmed.substringAfterLast("/")
                    } else {
                        trimmed
                    }
                }
                // 格式: "/path/to/file" -> "/path/to/target"
                text.startsWith("/") && text.contains("/") -> {
                    text.substringAfterLast("/")
                }
                // 其他格式：任何包含点号或斜杠的文本
                text.contains(".") || text.contains("/") -> {
                    val cleaned = text.trim()
                    if (cleaned.contains("/")) {
                        cleaned.substringAfterLast("/")
                    } else {
                        cleaned
                    }
                }
                else -> null
            }

        // 检查是否是错误信息
        if (text.contains(
                "cp:",
            ) ||
            text.contains(
                "error",
            ) ||
            text.contains(
                "denied",
            ) ||
            text.contains(
                "failed",
            ) || text.contains("No such file") || text.contains("cannot stat") || text.contains("cannot overwrite")
        ) {
            // 捕获错误输出，记录详细信息
            Log.e(TAG, "Copy Error: $text")
            // 提取失败的文件名并回报
            val errorFile = fileName ?: extractFileNameFromError(text) ?: "未知文件"
            state.errorCount++
            try {
                // 使用负数 current 表示错误，文件名带 [失败] 前缀
                callback?.onProgress(-state.errorCount, totalFiles, "[失败] $errorFile")
            } catch (e: Exception) {
                // 忽略
            }
        } else {
            // 成功复制文件（即使文件名为空也增加计数，确保进度正确）
            state.current++
            val displayFileName = fileName ?: "文件"
            val now = System.currentTimeMillis()
            // 优化：限制回调频率 (每 50ms 或每 20 个文件更新一次)
            if (state.current - state.lastReportedCount >= 20 || now - state.lastReportTime >= 50) {
                try {
                    callback?.onProgress(state.current, totalFiles, displayFileName)
                    state.lastReportedCount = state.current
                    state.lastReportTime = now
                } catch (e: Exception) {
                    // 忽略回调错误
                }
            }
        }
    }

    /**
     * 从错误信息中提取文件名
     */
    private fun extractFileNameFromError(text: String): String? {
        // 尝试从错误信息中提取文件名
        // 格式可能是: cp: cannot stat 'file.txt': No such file
        val quotedParts = text.split("'")
        return if (quotedParts.size >= 2) {
            quotedParts[1].substringAfterLast("/")
        } else {
            // 尝试从路径中提取
            val pathParts = text.split("/")
            pathParts.lastOrNull { it.isNotEmpty() }
        }
    }

    override fun cleanDirectoryWithProgress(
        basePath: String,
        whiteList: Array<out String>?,
        callback: com.example.tfgwj.IDeleteCallback?,
    ) {
        Thread {
            try {
                Log.d(TAG, "开始清理目录: $basePath, 白名单: ${whiteList?.toList()}")
                callback?.onProgress(0, 0, "🔍 正在扫描目录...")

                // 1. 列出目录下的顶层项
                val lsCmd = "ls -1 \"$basePath\" 2>/dev/null"
                val lsProcess = Runtime.getRuntime().exec(arrayOf("sh", "-c", lsCmd))
                val items =
                    lsProcess.inputStream.bufferedReader().readText()
                        .split("\n")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                lsProcess.waitFor()

                if (items.isEmpty()) {
                    Log.d(TAG, "目录为空，无需清理")
                    callback?.onCompleted(0)
                    return@Thread
                }

                Log.d(TAG, "扫描到 ${items.size} 个项目")
                callback?.onProgress(0, items.size, "🔍 扫描到 ${items.size} 个项目，准备删除...")

                // 2. 过滤白名单
                val whiteSet = whiteList?.map { it.lowercase() }?.toSet() ?: emptySet()
                val toDelete = items.filter { !whiteSet.contains(it.lowercase()) }

                if (toDelete.isEmpty()) {
                    Log.d(TAG, "过滤后无需删除")
                    callback?.onCompleted(0)
                    return@Thread
                }

                Log.d(TAG, "待删除顶层项: ${toDelete.size} 个")
                callback?.onProgress(0, toDelete.size, "🚀 开始并发删除 ${toDelete.size} 个项目...")

                // 3. 高并发删除顶层目录（提高并发数到 8）
                val maxConcurrency = 8 // 提高并发数到 8，加快删除速度
                val deletedCount = java.util.concurrent.atomic.AtomicInteger(0)
                val errorCount = java.util.concurrent.atomic.AtomicInteger(0)

                // 使用线程池并发删除
                val executor =
                    java.util.concurrent.ThreadPoolExecutor(
                        maxConcurrency,
                        maxConcurrency,
                        0L,
                        java.util.concurrent.TimeUnit.MILLISECONDS,
                        java.util.concurrent.LinkedBlockingQueue(),
                    )

                // 用于同步的计数器
                val latch = java.util.concurrent.CountDownLatch(toDelete.size)

                toDelete.forEach { item ->
                    executor.submit {
                        try {
                            val itemPath = "$basePath/$item"

                            // 立即报告正在删除
                            callback?.onProgress(deletedCount.get(), toDelete.size, "🗑️ $item")

                            // 执行删除
                            val deleteCmd = "rm -rf \"$itemPath\" 2>&1"
                            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", deleteCmd))
                            val exitCode = proc.waitFor()

                            if (exitCode == 0) {
                                deletedCount.incrementAndGet()
                                Log.d(TAG, "删除成功: $item")
                                callback?.onProgress(deletedCount.get(), toDelete.size, "✅ $item")
                            } else {
                                errorCount.incrementAndGet()
                                Log.e(TAG, "删除失败: $item")
                                callback?.onProgress(deletedCount.get(), toDelete.size, "❌ 失败: $item")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "删除异常: $item", e)
                            errorCount.incrementAndGet()
                        } finally {
                            latch.countDown()
                        }
                    }
                }

                // 等待所有删除任务完成
                latch.await()
                executor.shutdown()

                Log.d(TAG, "并发清理完成: 成功删除 ${deletedCount.get()} 项, 失败 ${errorCount.get()} 个目录")

                if (deletedCount.get() > 0) {
                    callback?.onCompleted(deletedCount.get())
                } else if (errorCount.get() > 0) {
                    callback?.onError("删除失败，请检查权限")
                } else {
                    callback?.onCompleted(0)
                }
            } catch (e: Exception) {
                Log.e(TAG, "清理目录失败", e)
                try {
                    callback?.onError(e.message)
                } catch (ignore: Exception) {
                }
            }
        }.start()
    }
}
