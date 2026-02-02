package com.example.tfgwj.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.tfgwj.shizuku.ShizukuManager
import com.example.tfgwj.utils.RootChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.isActive
import org.json.JSONArray
import java.io.*
import java.nio.channels.FileChannel
import com.example.tfgwj.utils.IoRateCalculator
import com.example.tfgwj.utils.PauseControl
import rikka.shizuku.Shizuku

/**
 * 文件替换 Worker
 * 支持三种模式的批量复制 + 验证（混合方案）
 * 支持增量更新（只复制变化的文件）
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileReplaceWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "FileReplaceWorker"
        
        // 输入参数键
        const val KEY_SOURCE_PATH = "source_path"
        const val KEY_TARGET_PACKAGE = "target_package"
        const val KEY_INCREMENTAL_UPDATE = "incremental_update"  // 是否增量更新
        
        // 进度键
        const val KEY_PROGRESS = "progress"
        const val KEY_CURRENT_FILE = "current_file"
        const val KEY_PROCESSED = "processed"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_FAILED_FILES = "failed_files"
        const val KEY_VERIFIED_FILES = "verified_files"
        const val KEY_MODE = "mode"  // 当前使用的模式
        
        /**
         * 创建工作请求
         */
        fun createWorkRequest(
            sourcePath: String, 
            targetPackage: String,
            incrementalUpdate: Boolean = false
        ): OneTimeWorkRequest {
            val inputData = Data.Builder()
                .putString(KEY_SOURCE_PATH, sourcePath)
                .putString(KEY_TARGET_PACKAGE, targetPackage)
                .putBoolean(KEY_INCREMENTAL_UPDATE, incrementalUpdate)
                .build()
            
            return OneTimeWorkRequestBuilder<FileReplaceWorker>()
                .setInputData(inputData)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        }
    }
    
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "🔥 [Perf] Worker doWork 启动 (Delay: ${System.currentTimeMillis() - startTime}ms)")
        
        val sourcePath = inputData.getString(KEY_SOURCE_PATH) ?: return@withContext Result.failure()
        val targetPackage = inputData.getString(KEY_TARGET_PACKAGE) ?: return@withContext Result.failure()
        val incrementalUpdate = inputData.getBoolean(KEY_INCREMENTAL_UPDATE, false)
        
        Log.d(TAG, "========== 开始文件替换 ==========")
        Log.d(TAG, "源路径: $sourcePath")
        Log.d(TAG, "目标包名: $targetPackage")
        Log.d(TAG, "增量更新: $incrementalUpdate")
        
        // 检查取消状态
        if (isStopped) {
            Log.d(TAG, "⚠️ 任务已被取消")
            return@withContext Result.failure(workDataOf(KEY_ERROR_MESSAGE to "任务已取消"))
        }
        
        // 查找 Android 目录
        val sourceDir = File(sourcePath)
        val androidDir = File(sourceDir, "Android")
        if (!androidDir.exists()) {
            Log.e(TAG, "❌ Android 目录不存在: $sourcePath/Android")
            return@withContext Result.failure(
                workDataOf(KEY_ERROR_MESSAGE to "源文件夹中没有 Android 目录")
            )
        }
        // 检测权限模式
        val hasRoot = RootChecker.isRooted()
        val shizukuManager = ShizukuManager.getInstance(applicationContext)
        val shizukuAvailable = shizukuManager.isAuthorized.value && shizukuManager.isServiceConnected.value
        
        val mode = when {
            hasRoot -> "ROOT_BATCH"
            shizukuAvailable -> "SHIZUKU_BATCH"
            else -> "NORMAL"
        }
        
        Log.d(TAG, "📦 选择模式: $mode")
        Log.d(TAG, "   Root: $hasRoot (${RootChecker.getRootManagerType()})")
        Log.d(TAG, "   Shizuku: $shizukuAvailable")
        
        // 执行替换
        val result = when (mode) {
            "ROOT_BATCH" -> executeRootBatchCopy(androidDir, targetPackage, incrementalUpdate, startTime)
            "SHIZUKU_BATCH" -> executeShizukuBatchCopy(androidDir, targetPackage, incrementalUpdate)
            "NORMAL" -> executeNormalCopy(androidDir, targetPackage, incrementalUpdate)
            else -> Result.failure(workDataOf(KEY_ERROR_MESSAGE to "不支持的模式: $mode"))
        }
        
        Log.d(TAG, "========== 文件替换完成 ==========")
        result
    }
    
    /**
     * Root 模式批量复制（极速模式入口）
     */
    private suspend fun executeRootBatchCopy(
        androidDir: File,
        targetPackage: String,
        incrementalUpdate: Boolean,
        startTime: Long
    ): Result {
        return coroutineScope {
        val targetBase = "/storage/emulated/0/Android/data/$targetPackage"
        Log.d(TAG, "========== Root 模式批量复制 (极速模式) ==========")
        Log.d(TAG, "源路径: ${androidDir.absolutePath}")
        
        // 重置进度管理器
        com.example.tfgwj.manager.ReplaceProgressManager.reset()
        com.example.tfgwj.manager.ReplaceProgressManager.startMeasure()
        
        // 1. 扫描源文件 (为了统计总进度) - 优化版本：使用 Shell 命令统计以防 OOM
        updateProgressState(0, 0, 0, "正在扫描源文件...", "ROOT_BATCH", 0f)
        val scanStart = System.currentTimeMillis()
        val totalFiles = countFilesRoot(androidDir)
        Log.d(TAG, "📊 扫描耗时: ${System.currentTimeMillis() - scanStart}ms, 扫描到 $totalFiles 个文件")
        
        if (totalFiles == 0) {
            return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to "源目录为空"))
        }

        // 3. 准备目标环境
        RootChecker.executeRootCommand("mkdir -p \"$targetBase\"")

        // 4. 执行递归极速复制 (带看门狗监控)
        Log.d(TAG, "🚀 启用 Root 从原生递归复制 (cp -R) + 进度监控看门狗")
        executeRootRecursiveCopy(androidDir, targetPackage, totalFiles)
        
        // 5. 验证结果 - 优化版本：使用流式序列处理避免全量 List 的 OOM
        updateProgressState(90, totalFiles, totalFiles, "🔍 验证替换进度...", "ROOT_BATCH", phase = "VERIFYING")
        val verifiedCount = verifyFilesParallel(androidDir, targetPackage, totalFiles)
        
        // 标记完成
        com.example.tfgwj.manager.ReplaceProgressManager.finish()
        Log.d(TAG, "✅ 所有任务完成，耗时: ${System.currentTimeMillis() - startTime}ms")
        
        return@coroutineScope Result.success(
            workDataOf(
                KEY_PROCESSED to verifiedCount,
                KEY_TOTAL to totalFiles,
                KEY_VERIFIED_FILES to verifiedCount.toString(),
                KEY_MODE to "ROOT_BATCH"
            )
        )
    }
    }

    /**
     * 策略 A: 递归极速复制 (适用于全量覆盖)
     * 原理: cp -v -R source/ (target/)
     */
    private suspend fun executeRootRecursiveCopy(
        sourceRoot: File,
        targetPackage: String,
        totalFiles: Int
    ) {
        coroutineScope {
            // 收集目录级任务
            val dirTasks = mutableListOf<CopyTask>()
            collectDirectoryTasks(sourceRoot, targetPackage, dirTasks)
            
            val progress = java.util.concurrent.atomic.AtomicInteger(0)
            val watchdogActive = java.util.concurrent.atomic.AtomicBoolean(true)
            
            // 启动看门狗协程：定期扫描目标目录获取真实的物理进度
            val watchdogJob = launch(Dispatchers.IO) {
                val targetBase = "/storage/emulated/0/Android/data/$targetPackage"
                Log.d(TAG, "🕵️ 看门狗已启动: 监控 $targetBase")
                
                while (watchdogActive.get() && isActive) {
                    delay(300) // 缩短延迟到 0.3 秒，让 UI 更顺滑
                    if (!watchdogActive.get()) break
                    
                    try {
                        val currentCount = progress.get()
                        val p = if (totalFiles > 0) (currentCount.toFloat() / totalFiles * 95).toInt().coerceIn(0, 95) else 0
                        
                        updateProgressState(
                            progress = p,
                            processed = currentCount,
                            total = totalFiles,
                            message = if (currentCount == 0) "⏳ 等待输出流..." else "正在处理第 $currentCount 个文件",
                            mode = "ROOT_BATCH",
                            phase = "REPLACING"
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "看门狗更新跳过: ${e.message}")
                    }
                }
                Log.d(TAG, "🕵️ 看门狗已停止")
            }

            try {
                // 并行执行多个目录的 cp 任务
                val semaphore = Semaphore(2) // 减少并发避免 shell 输出竞争太激烈
                dirTasks.map { task ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            runCpCommand(task, progress, totalFiles)
                        }
                    }
                }.toList().awaitAll()
            } finally {
                // 确保任务完成后停止看门狗
                watchdogActive.set(false)
                watchdogJob.cancel()
            }
        }
    }

    /**
     * 执行单个 CP 命令，实时监听输出
     */
    private suspend fun runCpCommand(task: CopyTask, progress: java.util.concurrent.atomic.AtomicInteger, totalFiles: Int) {
        val cmd = if (task.isDirectory) {
            "mkdir -p \"${task.targetDir}\" && cp -p -v -R \"${task.sourceDir.absolutePath}/.\" \"${task.targetDir}/\""
        } else {
            "mkdir -p \"${File(task.targetDir).parent}\" && cp -p -v \"${task.sourceDir.absolutePath}\" \"${task.targetDir}\""
        }
        
        Log.d(TAG, "执行 CP: [${task.sourceDir.name}] -> [${task.targetDir}]")
        
        try {
            val process = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            
            val reader = process.inputStream.bufferedReader()
            var line: String?
            
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrEmpty()) continue
                
                // cp -v 输出可能是: 'src' -> 'dst' 或 src -> dst
                // cp -v 输出可能是: 'src' -> 'dst' 或 src -> dst
                val current = progress.incrementAndGet()
                
                // 更加健壮的解析策略：
                // 1. 如果包含 ' -> '，提取目标文件名
                // 2. 如果包含 'cp ''，提取源文件名
                // 3. 否则，如果看起来像路径，提取最后一段
                val fileName = when {
                    line.contains(" -> ") -> {
                        line.substringAfterLast(" -> ")
                            .trim()
                            .trim('\'', '"')
                            .substringAfterLast("/")
                    }
                    line.contains("cp '") -> {
                        line.substringAfter("cp '")
                            .substringBefore("'")
                            .substringAfterLast("/")
                    }
                    else -> {
                        line.trim()
                            .trim('\'', '"')
                            .substringAfterLast("/")
                            .substringBefore(" ") // 避免 cp: ... 这种错误信息
                    }
                }.ifEmpty { "正在处理..." }
                
                // 高频更新 UI (直接通过管理器)
                val p = if (totalFiles > 0) (current.toFloat() / totalFiles * 95).toInt().coerceIn(0, 95) else 0
                com.example.tfgwj.manager.ReplaceProgressManager.updateState(
                    processed = current,
                    total = totalFiles,
                    currentFile = fileName,
                    progress = p,
                    phase = "REPLACING"
                )
                
                // 即使不满足 WorkManager 节流，我们也尽量实时推送（见 updateProgressState 内部逻辑）
                updateProgressState(p, current, totalFiles, fileName, "ROOT_BATCH", phase = "REPLACING")
            }
            
            process.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "CP 执行失败: ${task.sourceDir.name}", e)
        }
    }
    
    /**
     * 并行校验所有文件 (优化版：直接校验源文件对应的目标路径)
     */
    private suspend fun verifyFilesParallel(
        androidDir: File,
        targetPackage: String,
        totalFiles: Int
    ): Int {
        return coroutineScope {
        val verifiedTotal = java.util.concurrent.atomic.AtomicInteger(0)
        
        // 1. 使用流式序列获取所有文件，避免全量 List OOM
        val sourceFilesSequence = androidDir.walkTopDown().filter { it.isFile }
        
        updateProgressState(90, totalFiles, totalFiles, "🔍 正在验证文件...", "ROOT_BATCH", phase = "VERIFYING")
        
        val semaphore = Semaphore(Runtime.getRuntime().availableProcessors() * 2)
        val statBatchSize = 500 // 每次 stat 命令检查 500 个文件
        val targetBase = "/storage/emulated/0/Android"
        
        // 分批处理验证任务
        sourceFilesSequence.chunked(statBatchSize).map { batch ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val batchPaths = batch.mapNotNull { srcFile ->
                        val relativePath = srcFile.absolutePath.removePrefix(androidDir.absolutePath)
                        val androidType = if (srcFile.absolutePath.contains("/obb/")) "obb" else "data"
                        val subPath = relativePath.substringAfter("/$androidType/").substringAfter("/", "")
                        
                        if (subPath.isNotEmpty()) {
                             val targetPath = "$targetBase/$androidType/$targetPackage/$subPath"
                             Pair(targetPath, srcFile.length())
                        } else {
                            null
                        }
                    }
                    
                    if (batchPaths.isNotEmpty()) {
                        // 构造 stat 命令
                        // stat -c "%s %n" path1 path2 ...
                        val sb = StringBuilder("stat -c \"%s %n\" ")
                        batchPaths.forEach { (path, _) ->
                            sb.append("\"$path\" ")
                        }
                        
                        try {
                            // 执行 stat
                            val output = RootChecker.executeRootCommand(sb.toString())
                            
                            // 解析结果
                            val resultMap = mutableMapOf<String, Long>()
                            output?.lineSequence()?.forEach { line ->
                                 val trimmed = line.trim()
                                 if (trimmed.isNotEmpty()) {
                                     val parts = trimmed.split(" ", limit = 2)
                                     if (parts.size == 2) {
                                         val size = parts[0].toLongOrNull()
                                         val path = parts[1]
                                         if (size != null) resultMap[path] = size
                                     }
                                 }
                            }
                            
                            // 校验
                            batchPaths.forEach { (targetPath, srcSize) ->
                                val targetSize = resultMap[targetPath]
                                if (targetSize != null && targetSize == srcSize) {
                                    verifiedTotal.incrementAndGet()
                                } else {
                                    Log.w(TAG, "校验未通过: $targetPath (Exp: $srcSize, Act: $targetSize)")
                                }
                            }
                            
                        } catch (e: Exception) {
                            Log.e(TAG, "批量校验失败", e)
                        }
                    }
                    
                        val current = verifiedTotal.get()
                        if (current % 100 == 0 || current >= totalFiles) {
                             // 验证进度在 90% - 100% 之间
                             val p = 90 + (current.toFloat() / totalFiles * 10).toInt().coerceIn(0, 10)
                             updateProgressState(p, totalFiles, totalFiles, "🔍 正在校验: $current/$totalFiles", "ROOT_BATCH", phase = "VERIFYING")
                        }
                }
            }
        }.toList().awaitAll()
        
        verifiedTotal.get()
        }
    }

    
    /**
     * 复制任务数据类
     */
    private data class CopyTask(
        val sourceDir: File,
        val targetDir: String,
        val isDirectory: Boolean = false, // 标记是否为目录任务
        var estimatedFiles: Int = 0
    )

    /**
     * 收集目录级任务
     */
    private fun collectDirectoryTasks(sourceRoot: File, targetPackage: String, tasks: MutableList<CopyTask>) {
        val rootDataDir = File(sourceRoot, "data")
        val rootObbDir = File(sourceRoot, "obb")
        
        var hasStandardStructure = false
        
        if (rootDataDir.exists() && rootDataDir.isDirectory) {
            hasStandardStructure = true
            rootDataDir.listFiles()?.forEach { pkgDir ->
                if (pkgDir.isDirectory) {
                    val targetBase = "/storage/emulated/0/Android/data/$targetPackage"
                    // 深入 3 层以增加任务粒度，防止大文件夹导致输出缓冲
                    collectTasksRecursive(pkgDir, targetBase, tasks, depth = 0, maxDepth = 2)
                }
            }
        }
        
        if (rootObbDir.exists() && rootObbDir.isDirectory) {
            hasStandardStructure = true
            rootObbDir.listFiles()?.forEach { pkgDir ->
                if (pkgDir.isDirectory) {
                    val targetBase = "/storage/emulated/0/Android/obb/$targetPackage"
                    collectTasksRecursive(pkgDir, targetBase, tasks, depth = 0, maxDepth = 2)
                }
            }
        }
        
        // 如果没有 data/obb 结构，假设源目录就是 files 等内容的直属上级
        if (!hasStandardStructure) {
            val targetBase = "/storage/emulated/0/Android/data/$targetPackage"
            collectTasksRecursive(sourceRoot, targetBase, tasks, depth = 0, maxDepth = 2)
        }
    }

    /**
     * 递归收集任务，直到达到最大深度或遇到文件
     */
    private fun collectTasksRecursive(source: File, target: String, tasks: MutableList<CopyTask>, depth: Int, maxDepth: Int) {
        if (!source.exists()) return

        if (source.isFile) {
            tasks.add(CopyTask(source, target, isDirectory = false))
            return
        }

        val children = source.listFiles()
        if (children.isNullOrEmpty()) {
            tasks.add(CopyTask(source, target, isDirectory = true))
            return
        }

        // 如果达到最大深度，或者子项太多，则不再递归，直接 cp -R
        if (depth >= maxDepth || children.size > 100) {
            tasks.add(CopyTask(source, target, isDirectory = true))
            return
        }

        // 否则继续递归
        children.forEach { child ->
            collectTasksRecursive(child, "$target/${child.name}", tasks, depth + 1, maxDepth)
        }
    }

    /**
     * 统计目录中的文件数量
     */
    private fun countFilesInDir(dir: File): Int {
        var count = 0
        dir.walkTopDown().forEach { file ->
            if (file.isFile) count++
        }
        return count
    }
    
    /**
     * Shizuku 模式批量复制
     */
    private suspend fun executeShizukuBatchCopy(
        androidDir: File,
        targetPackage: String,
        incrementalUpdate: Boolean
    ): Result {
        return coroutineScope {
        val targetBase = "/storage/emulated/0/Android/data/$targetPackage"
        val shizukuManager = ShizukuManager.getInstance(applicationContext)
        
        Log.d(TAG, "========== Shizuku 模式批量复制 (极速模式) ==========")
        Log.d(TAG, "源路径: ${androidDir.absolutePath}")
        
        // 等待 Shizuku 服务连接
        if (!shizukuManager.isServiceConnected.value) {
            Log.d(TAG, "等待 Shizuku 服务连接...")
            kotlinx.coroutines.withTimeout(3000) {
                while (!shizukuManager.isServiceConnected.value) {
                    kotlinx.coroutines.delay(100)
                }
            }
        }
        
        // 重置进度
        com.example.tfgwj.manager.ReplaceProgressManager.reset()
        com.example.tfgwj.manager.ReplaceProgressManager.startMeasure()
        
        // 1. 扫描文件 - 使用 Sequence 避免 OOM
        val totalFiles = countFilesRoot(androidDir)
        
        if (totalFiles == 0) {
            return@coroutineScope Result.failure(workDataOf(KEY_ERROR_MESSAGE to "源目录为空"))
        }

        // 2. 准备目标环境
        shizukuManager.createDirectory(targetBase)
        
        // 3. 执行递归极速复制
        Log.d(TAG, "🚀 启用 Shizuku 递归极速复制 (cp -R)")
        executeShizukuRecursiveCopy(androidDir, targetPackage, totalFiles)
        
        com.example.tfgwj.manager.ReplaceProgressManager.finish()
        Log.d(TAG, "✅ Shizuku 任务完成")
        
        return@coroutineScope Result.success(
            workDataOf(
                KEY_PROCESSED to totalFiles,
                KEY_TOTAL to totalFiles,
                KEY_MODE to "SHIZUKU_BATCH"
            )
        )
    }
    }

    /**
     * Shizuku 递归复制
     */
    private suspend fun executeShizukuRecursiveCopy(
        sourceRoot: File, 
        targetPackage: String, 
        totalFiles: Int
    ) {
        coroutineScope {
            val dirTasks = mutableListOf<CopyTask>()
            collectDirectoryTasks(sourceRoot, targetPackage, dirTasks)
            
            val progress = java.util.concurrent.atomic.AtomicInteger(0)
            val watchdogActive = java.util.concurrent.atomic.AtomicBoolean(true)

            // 启动 Shizuku 模式下的看门狗
            val watchdogJob = launch(Dispatchers.IO) {
                val targetBase = "/storage/emulated/0/Android/data/$targetPackage"
                val shizukuManager = ShizukuManager.getInstance(applicationContext)
                
                while (watchdogActive.get() && isActive) {
                    delay(500) // 0.5s 更新频率
                    if (!watchdogActive.get()) break
                    
                    try {
                        // 优化：不再使用 Shizuku find 扫描全量，直接上报进度驱动平滑 UI
                        val currentCount = progress.get()
                        val p = (currentCount.toFloat() / totalFiles * 95).toInt().coerceIn(0, 95)
                        
                        updateProgressState(
                            progress = p,
                            processed = currentCount,
                            total = totalFiles,
                            message = "进行中... ($currentCount/$totalFiles)",
                            mode = "SHIZUKU_BATCH"
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Shizuku 状态更新跳过: ${e.message}")
                    }
                }
            }

            try {
                val semaphore = Semaphore(2)
                dirTasks.map { task ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            runShizukuCpCommand(task, progress, totalFiles)
                        }
                    }
                }.toList().awaitAll()
            } finally {
                watchdogActive.set(false)
                watchdogJob.cancel()
            }
        }
    }

    /**
     * 执行单个 Shizuku CP 命令，实时监听输出
     */
    private suspend fun runShizukuCpCommand(
        task: CopyTask, 
        progress: java.util.concurrent.atomic.AtomicInteger, 
        totalFiles: Int
    ) {
        val cmd = if (task.isDirectory) {
            "mkdir -p \"${task.targetDir}\" && cp -p -v -R \"${task.sourceDir.absolutePath}/.\" \"${task.targetDir}/\""
        } else {
            "mkdir -p \"${File(task.targetDir).parent}\" && cp -p -v \"${task.sourceDir.absolutePath}\" \"${task.targetDir}\""
        }
        
        try {
            @Suppress("DEPRECATION")
            val process = rikka.shizuku.Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val reader = process.inputStream.bufferedReader()
            var line: String?
            
            while (reader.readLine().also { line = it } != null) {
            if (line.isNullOrBlank()) continue
            
            val current = progress.incrementAndGet()
            
            // 同样使用健壮的解析策略
            val fileName = when {
                line!!.contains(" -> ") -> {
                    line!!.substringAfterLast(" -> ")
                        .trim()
                        .trim('\'', '"')
                        .substringAfterLast("/")
                }
                line!!.contains("cp '") -> {
                    line!!.substringAfter("cp '")
                        .substringBefore("'")
                        .substringAfterLast("/")
                }
                else -> {
                    line!!.trim()
                        .trim('\'', '"')
                        .substringAfterLast("/")
                        .substringBefore(" ")
                }
            }.ifEmpty { "正在处理..." }
            
            val p = if (totalFiles > 0) (current.toFloat() / totalFiles * 95).toInt().coerceIn(0, 95) else 0
            com.example.tfgwj.manager.ReplaceProgressManager.updateState(
                processed = current,
                total = totalFiles,
                currentFile = fileName,
                progress = p,
                phase = "REPLACING"
            )
            
            // 实时同步
            updateProgressState(p, current, totalFiles, fileName, "SHIZUKU_BATCH", phase = "REPLACING")
            
            if (!line!!.contains(" -> ") && !line!!.contains("cp '")) {
                Log.v(TAG, "Shizuku CP Output: $line")
            }
        }
        process.waitFor()
    } catch (e: Exception) {
        Log.e(TAG, "Shizuku CP 失败: ${task.sourceDir.name}", e)
    }
}

    /**
     * 普通模式复制（无需 Shizuku 且无 Root）
     * 采用协程并发复制方案，提升多文件复制速度
     */
    private suspend fun executeNormalCopy(
        androidDir: File,
        targetPackage: String,
        incrementalUpdate: Boolean
    ): Result = withContext(Dispatchers.IO) {
        val targetBase = "/storage/emulated/0/Android/data/$targetPackage"
        var processedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val failedFiles = java.util.Collections.synchronizedList(mutableListOf<String>())
        
        Log.d(TAG, "========== 普通模式并发复制 (极速模式) ==========")
        Log.d(TAG, "源路径: ${androidDir.absolutePath}")
        
        // 1. 统计
        val totalFiles = countFilesRoot(androidDir)
        
        // 2. 环境
        File(targetBase).mkdirs()
        
        // 3. 全量处理流式序列
        val filesToCopy = androidDir.walkTopDown().filter { it.isFile }
        
        if (totalFiles == 0) {
             return@withContext Result.success(workDataOf(KEY_PROCESSED to 0, KEY_MODE to "NORMAL"))
        }

        // 4. 高并发 IO
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val dynamicPermits = (cpuCores * 2).coerceAtLeast(4).coerceAtMost(32)
        Log.d(TAG, "🚀 普通模式并发度: $dynamicPermits")
        
        val semaphore = Semaphore(dynamicPermits)
        val ioRateCalculator = IoRateCalculator()
        val totalBytesProcessed = java.util.concurrent.atomic.AtomicLong(0)
        
        coroutineScope {
            filesToCopy.forEach { file ->
                launch {
                    try {
                        PauseControl.waitIfPaused()
                        semaphore.acquire()
                        if (isStopped) { semaphore.release(); return@launch }
                        
                        // 路径映射
                        val fullPath = file.absolutePath
                        val androidType = when {
                            fullPath.contains("/data/") -> "data"
                            fullPath.contains("/obb/") -> "obb"
                            else -> "data"
                        }
                        
                        val subPath = fullPath.substringAfter("/$androidType/").substringAfter("/", "")
                        if (subPath.isNotEmpty()) {
                             val realTargetBase = "/storage/emulated/0/Android/$androidType/$targetPackage"
                             val targetFile = File(realTargetBase, subPath)
                             
                             // 确保父目录
                             if (targetFile.parentFile?.exists() == false) {
                                 synchronized(this@FileReplaceWorker) {
                                     targetFile.parentFile?.mkdirs()
                                 }
                             }
                             
                             // 执行 Zero-Copy
                             val bytes = copyFileZeroCopy(file, targetFile)
                             targetFile.setLastModified(file.lastModified())
                             
                             val currentBytes = totalBytesProcessed.addAndGet(bytes)
                             val currentProcessed = processedCount.incrementAndGet()
                             
                             // 速率与进度
                             val speed = ioRateCalculator.update(currentBytes)
                             
                             if (currentProcessed % 10 == 0 || currentProcessed == totalFiles) {
                                   val p = ((currentProcessed.toFloat() / totalFiles) * 100).toInt()
                                  updateProgressState(
                                      progress = p,
                                      processed = currentProcessed,
                                      total = totalFiles,
                                      message = file.name,
                                      mode = "NORMAL",
                                      speed = speed
                                  )
                             }
                        }
                        semaphore.release()
                    } catch (e: Exception) {
                        Log.e(TAG, "Copy Error: ${file.name}", e)
                        failedFiles.add(file.name)
                        semaphore.release()
                    }
                }
            }
        }
        
        Log.d(TAG, "✅ 普通模式完成")
        com.example.tfgwj.manager.ReplaceProgressManager.finish()
        Result.success(workDataOf(KEY_PROCESSED to processedCount.get(), KEY_MODE to "NORMAL"))
    }
    
    /**
     * 高性能统计文件数量（支持百万级、防 OOM）
     */
    private fun countFilesRoot(dir: File): Int {
        val path = dir.absolutePath
        val cmd = "find \"$path\" -type f 2>/dev/null | wc -l"
        
        return try {
            // 1. 优先尝试 Root
            var result = com.example.tfgwj.utils.RootChecker.executeRootCommand(cmd)
            
            // 2. 如果 Root 失败，尝试 Shizuku
            if (result.isNullOrBlank()) {
                val shizukuManager = com.example.tfgwj.shizuku.ShizukuManager.getInstance(applicationContext)
                if (shizukuManager.isAuthorized.value && shizukuManager.isServiceConnected.value) {
                    result = shizukuManager.executeCommandWithOutput(cmd)
                }
            }
            
            result?.trim()?.toIntOrNull() ?: countFilesNative(dir)
        } catch (e: Exception) {
            countFilesNative(dir)
        }
    }

    private fun countFilesNative(dir: File): Int {
        var count = 0
        dir.walkTopDown().forEach { if (it.isFile) count++ }
        return count
    }
    
    /**
     * 递归列出所有文件 (已弃用，见 countFilesRoot)
     */
    private fun listAllFiles(dir: File): List<File> {
        return dir.walkTopDown().filter { it.isFile }.toList()
    }
    
    private var lastWmUpdateTime = 0L

    /**
     * 设置进度（带 WorkManager 节流）
     */
    private suspend fun updateProgressState(progress: Int, processed: Int, total: Int, message: String, mode: String, speed: Float = 0f, phase: String = "REPLACING") {
        val currentTime = System.currentTimeMillis()
        
        // 1. 同步到 WorkManager (带节流：缩短为 500ms 提高平滑度，或达到完成状态)
        if (currentTime - lastWmUpdateTime >= 500 || processed >= total) {
            lastWmUpdateTime = currentTime
            setProgressAsync(
                workDataOf(
                    KEY_PROGRESS to progress,
                    KEY_PROCESSED to processed,
                    KEY_TOTAL to total,
                    KEY_CURRENT_FILE to message,
                    KEY_MODE to mode,
                    "speed" to speed,
                    "phase" to phase
                )
            )
        }
        
        // 2. 同步到实时管理器 (不节流，用于极速 UI 响应)
        com.example.tfgwj.manager.ReplaceProgressManager.updateState(
            processed = processed,
            total = total,
            currentFile = message,
            progress = progress,
            speed = speed,
            phase = phase
        )
    }
    
    /**
     * 零拷贝文件复制 (性能优化)
     * @return 复制的字节数
     */
    private fun copyFileZeroCopy(source: File, target: File): Long {
        if (target.exists()) {
            target.delete()
        }
        target.parentFile?.mkdirs()
        
        return FileInputStream(source).channel.use { sourceChannel ->
            FileOutputStream(target).channel.use { destChannel ->
                sourceChannel.transferTo(0, sourceChannel.size(), destChannel)
            }
        }
    }
}
