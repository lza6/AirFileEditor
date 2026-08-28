package com.example.tfgwj.worker

import android.content.Context
import android.util.Log
import com.example.tfgwj.domain.model.TaskPhase
import com.example.tfgwj.utils.RootChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/**
 * RootCopyStrategy - V7.0.0 Architecture Evolution
 *
 * Root mode file copy implementation.
 * Uses cp -R command for high-speed recursive copy.
 * Includes watchdog for progress tracking.
 */
class RootCopyStrategy(
    context: Context,
    targetPackage: String,
) : CopyStrategy(context, targetPackage) {
    companion object {
        private const val TAG = "RootCopyStrategy"
    }

    private val taskController = TaskControllerProvider.get()

    override val strategyName: String = "ROOT_BATCH"

    /**
     * Copy task data class
     */
    private data class CopyTask(
        val sourceDir: File,
        val targetDir: String,
        val isDirectory: Boolean = false,
        var estimatedFiles: Int = 0,
    )

    override suspend fun executeBatchCopy(
        sourceDir: File,
        incrementalUpdate: Boolean,
        callback: CopyStrategy.ProgressCallback?,
    ): CopyStrategy.CopyResult =
        coroutineScope {
            val targetBase = "/storage/emulated/0/Android/data/$targetPackage"
            Log.d(TAG, "========== Root 模式批量复制 (极速模式) ==========")
            Log.d(TAG, "源路径: ${sourceDir.absolutePath}")

            // Reset progress manager
            taskController.reset()
            taskController.startMeasure()

            // Scan source files
            callback?.onProgress(0, 0, 0, "正在扫描源文件...", phase = TaskPhase.PREPARING)
            val scanStart = System.currentTimeMillis()
            val totalFiles = countFilesRoot(sourceDir)
            Log.d(TAG, "扫描耗时: ${System.currentTimeMillis() - scanStart}ms, 扫描到 $totalFiles 个文件")

            if (totalFiles == 0) {
                return@coroutineScope CopyStrategy.CopyResult.failure("源目录为空")
            }

            // Prepare target environment
            RootChecker.executeRootCommand("mkdir -p \"$targetBase\"")

            // Execute recursive copy with watchdog
            executeRootRecursiveCopy(sourceDir, targetPackage, totalFiles, callback)

            // Verify results
            callback?.onProgress(90, totalFiles, totalFiles, "验证替换进度...", phase = TaskPhase.VERIFYING)
            val verifiedCount = verifyFilesParallel(sourceDir, targetPackage, totalFiles)

            // Mark complete
            taskController.finish()
            Log.d(TAG, "所有任务完成")

            CopyStrategy.CopyResult.success(
                processedCount = verifiedCount,
                totalCount = totalFiles,
                verifiedCount = verifiedCount,
                mode = strategyName,
            )
        }

    /**
     * Execute recursive copy with watchdog monitoring
     */
    private suspend fun executeRootRecursiveCopy(
        sourceRoot: File,
        targetPackage: String,
        totalFiles: Int,
        callback: CopyStrategy.ProgressCallback?,
    ) = coroutineScope {
        val dirTasks = mutableListOf<CopyTask>()
        collectDirectoryTasks(sourceRoot, targetPackage, dirTasks)

        val progress = java.util.concurrent.atomic.AtomicInteger(0)
        val watchdogActive = java.util.concurrent.atomic.AtomicBoolean(true)

        // Start watchdog coroutine
        val watchdogJob =
            launch(Dispatchers.IO) {
                val targetBase = "/storage/emulated/0/Android/data/$targetPackage"
                Log.d(TAG, "看门狗已启动: 监控 $targetBase")

                while (watchdogActive.get() && isActive) {
                    delay(300)
                    if (!watchdogActive.get()) break

                    try {
                        val currentCount = progress.get()
                        val p = if (totalFiles > 0) (currentCount.toFloat() / totalFiles * 95).toInt().coerceIn(0, 95) else 0

                        callback?.onProgress(
                            progress = p,
                            processed = currentCount,
                            total = totalFiles,
                            currentFile = if (currentCount == 0) "等待输出流..." else "正在处理第 $currentCount 个文件",
                            phase = TaskPhase.REPLACING,
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "看门狗更新跳过: ${e.message}")
                    }
                }
                Log.d(TAG, "看门狗已停止")
            }

        try {
            // Execute copy tasks in parallel
            val semaphore = Semaphore(2)
            dirTasks.map { task ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        runCpCommand(task, progress, totalFiles, callback)
                    }
                }
            }.toList().awaitAll()
        } finally {
            watchdogActive.set(false)
            watchdogJob.cancel()
        }
    }

    /**
     * Execute single cp command with output monitoring
     */
    private suspend fun runCpCommand(
        task: CopyTask,
        progress: java.util.concurrent.atomic.AtomicInteger,
        totalFiles: Int,
        callback: CopyStrategy.ProgressCallback?,
    ) {
        val cmd =
            if (task.isDirectory) {
                "mkdir -p \"${task.targetDir}\" && cp -p -v -R \"${task.sourceDir.absolutePath}/.\" \"${task.targetDir}/\""
            } else {
                "mkdir -p \"${File(task.targetDir).parent}\" && cp -p -v \"${task.sourceDir.absolutePath}\" \"${task.targetDir}\""
            }

        Log.d(TAG, "执行 CP: [${task.sourceDir.name}] -> [${task.targetDir}]")

        try {
            val process =
                ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true)
                    .start()

            val reader = process.inputStream.bufferedReader()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrEmpty()) continue

                val current = progress.incrementAndGet()

                // Parse output for filename
                val fileName = parseCpOutput(line!!)

                val p = if (totalFiles > 0) (current.toFloat() / totalFiles * 95).toInt().coerceIn(0, 95) else 0

                callback?.onProgress(p, current, totalFiles, fileName, phase = TaskPhase.REPLACING)
            }

            process.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "CP 执行失败: ${task.sourceDir.name}", e)
        }
    }

    /**
     * Parse cp -v output to extract filename
     */
    private fun parseCpOutput(line: String): String {
        return when {
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
                    .substringBefore(" ")
            }
        }.ifEmpty { "正在处理..." }
    }

    /**
     * Verify files in parallel
     */
    private suspend fun verifyFilesParallel(
        androidDir: File,
        targetPackage: String,
        totalFiles: Int,
    ): Int =
        coroutineScope {
            val verifiedTotal = java.util.concurrent.atomic.AtomicInteger(0)
            val sourceFilesSequence = androidDir.walkTopDown().filter { it.isFile }

            val semaphore = Semaphore(Runtime.getRuntime().availableProcessors() * 2)
            val statBatchSize = 500
            val targetBase = "/storage/emulated/0/Android"

            sourceFilesSequence.chunked(statBatchSize).map { batch ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val batchPaths =
                            batch.mapNotNull { srcFile ->
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
                            val sb = StringBuilder("stat -c \"%s %n\" ")
                            batchPaths.forEach { (path, _) ->
                                sb.append("\"$path\" ")
                            }

                            try {
                                val output = RootChecker.executeRootCommand(sb.toString())

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

                                batchPaths.forEach { (targetPath, srcSize) ->
                                    val targetSize = resultMap[targetPath]
                                    if (targetSize != null && targetSize == srcSize) {
                                        verifiedTotal.incrementAndGet()
                                    } else {
                                        Log.w(TAG, "校验未通过: $targetPath")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "批量校验失败", e)
                            }
                        }

                        val current = verifiedTotal.get()
                        if (current % 100 == 0 || current >= totalFiles) {
                            val p = 90 + (current.toFloat() / totalFiles * 10).toInt().coerceIn(0, 10)
                            callback?.onProgress(p, totalFiles, totalFiles, "正在校验: $current/$totalFiles", phase = TaskPhase.VERIFYING)
                        }
                    }
                }
            }.toList().awaitAll()

            verifiedTotal.get()
        }

    // Progress callback holder for verification
    private var callback: CopyStrategy.ProgressCallback? = null

    /**
     * Set progress callback
     */
    fun setCallback(cb: CopyStrategy.ProgressCallback?) {
        callback = cb
    }

    /**
     * Collect directory-level copy tasks
     */
    private fun collectDirectoryTasks(
        sourceRoot: File,
        targetPackage: String,
        tasks: MutableList<CopyTask>,
    ) {
        val rootDataDir = File(sourceRoot, "data")
        val rootObbDir = File(sourceRoot, "obb")

        var hasStandardStructure = false

        if (rootDataDir.exists() && rootDataDir.isDirectory) {
            hasStandardStructure = true
            rootDataDir.listFiles()?.forEach { pkgDir ->
                if (pkgDir.isDirectory) {
                    val targetBase = "/storage/emulated/0/Android/data/$targetPackage"
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

        if (!hasStandardStructure) {
            val targetBase = "/storage/emulated/0/Android/data/$targetPackage"
            collectTasksRecursive(sourceRoot, targetBase, tasks, depth = 0, maxDepth = 2)
        }
    }

    /**
     * Recursively collect tasks
     */
    private fun collectTasksRecursive(
        source: File,
        target: String,
        tasks: MutableList<CopyTask>,
        depth: Int,
        maxDepth: Int,
    ) {
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

        if (depth >= maxDepth || children.size > 100) {
            tasks.add(CopyTask(source, target, isDirectory = true))
            return
        }

        children.forEach { child ->
            collectTasksRecursive(child, "$target/${child.name}", tasks, depth + 1, maxDepth)
        }
    }

    /**
     * Count files using Root command for performance
     */
    private fun countFilesRoot(dir: File): Int {
        val path = dir.absolutePath
        val cmd = "find \"$path\" -type f 2>/dev/null | wc -l"

        return try {
            val result = RootChecker.executeRootCommand(cmd)
            result?.trim()?.toIntOrNull() ?: countFiles(dir)
        } catch (e: Exception) {
            countFiles(dir)
        }
    }
}
