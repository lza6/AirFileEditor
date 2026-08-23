package com.example.tfgwj.worker

import android.content.Context
import android.util.Log
import com.example.tfgwj.domain.model.TaskPhase
import com.example.tfgwj.manager.ReplaceProgressManager
import com.example.tfgwj.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import java.io.File

/**
 * ShizukuCopyStrategy - V7.0.0 Architecture Evolution
 *
 * Shizuku mode file copy implementation.
 * Uses Shizuku IPC for cross-process file access.
 */
class ShizukuCopyStrategy(
    context: Context,
    targetPackage: String,
) : CopyStrategy(context, targetPackage) {
    companion object {
        private const val TAG = "ShizukuCopyStrategy"
    }

    override val strategyName: String = "SHIZUKU_BATCH"

    /**
     * Copy task data class
     */
    private data class CopyTask(
        val sourceDir: File,
        val targetDir: String,
        val isDirectory: Boolean = false,
    )

    override suspend fun executeBatchCopy(
        sourceDir: File,
        incrementalUpdate: Boolean,
        callback: CopyStrategy.ProgressCallback?,
    ): CopyStrategy.CopyResult =
        coroutineScope {
            val targetBase = "/storage/emulated/0/Android/data/$targetPackage"
            val shizukuManager = ShizukuManager.getInstance(context)

            Log.d(TAG, "========== Shizuku 模式批量复制 ==========")
            Log.d(TAG, "源路径: ${sourceDir.absolutePath}")

            // Wait for Shizuku connection if needed
            if (shizukuManager.isAvailable.value && shizukuManager.isAuthorized.value && !shizukuManager.isServiceConnected.value) {
                Log.d(TAG, "检测到 Shizuku 已授权但未连接，尝试等待...")
                try {
                    withTimeout(2000) {
                        while (!shizukuManager.isServiceConnected.value && isActive) {
                            delay(100)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "等待 Shizuku 连接超时", e)
                }
            }

            // Reset progress
            ReplaceProgressManager.reset()
            ReplaceProgressManager.startMeasure()

            // Scan files
            val totalFiles = countFilesRoot(sourceDir)

            if (totalFiles == 0) {
                return@coroutineScope CopyStrategy.CopyResult.failure("源目录为空")
            }

            // Prepare target
            shizukuManager.createDirectory(targetBase)

            // Execute recursive copy
            Log.d(TAG, "启用 Shizuku 递归极速复制")
            executeShizukuRecursiveCopy(sourceDir, targetPackage, totalFiles, callback)

            ReplaceProgressManager.finish()
            Log.d(TAG, "Shizuku 任务完成")

            CopyStrategy.CopyResult.success(
                processedCount = totalFiles,
                totalCount = totalFiles,
                mode = strategyName,
            )
        }

    /**
     * Execute Shizuku recursive copy
     */
    private suspend fun executeShizukuRecursiveCopy(
        sourceRoot: File,
        targetPackage: String,
        totalFiles: Int,
        callback: CopyStrategy.ProgressCallback?,
    ) = coroutineScope {
        val dirTasks = mutableListOf<CopyTask>()
        collectDirectoryTasks(sourceRoot, targetPackage, dirTasks)

        val progress = java.util.concurrent.atomic.AtomicInteger(0)
        val watchdogActive = java.util.concurrent.atomic.AtomicBoolean(true)

        // Start watchdog
        val watchdogJob =
            launch(Dispatchers.IO) {
                val targetBase = "/storage/emulated/0/Android/data/$targetPackage"

                while (watchdogActive.get() && isActive) {
                    delay(500)
                    if (!watchdogActive.get()) break

                    try {
                        val currentCount = progress.get()
                        val p = (currentCount.toFloat() / totalFiles * 95).toInt().coerceIn(0, 95)

                        callback?.onProgress(
                            progress = p,
                            processed = currentCount,
                            total = totalFiles,
                            currentFile = "进行中... ($currentCount/$totalFiles)",
                            phase = TaskPhase.REPLACING,
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "状态更新跳过: ${e.message}")
                    }
                }
            }

        try {
            val semaphore = Semaphore(2)
            dirTasks.map { task ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        runShizukuCpCommand(task, progress, totalFiles, callback)
                    }
                }
            }.toList().awaitAll()
        } finally {
            watchdogActive.set(false)
            watchdogJob.cancel()
        }
    }

    /**
     * Execute Shizuku cp command
     */
    private suspend fun runShizukuCpCommand(
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

        try {
            @Suppress("DEPRECATION")
            val process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val reader = process.inputStream.bufferedReader()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) continue

                val current = progress.incrementAndGet()
                val fileName = parseCpOutput(line!!)

                val p = if (totalFiles > 0) (current.toFloat() / totalFiles * 95).toInt().coerceIn(0, 95) else 0

                callback?.onProgress(p, current, totalFiles, fileName, phase = TaskPhase.REPLACING)
            }

            process.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku CP 失败: ${task.sourceDir.name}", e)
        }
    }

    /**
     * Parse cp output
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
     * Collect directory tasks
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
     * Count files using Shizuku
     */
    private fun countFilesRoot(dir: File): Int {
        val path = dir.absolutePath
        val cmd = "find \"$path\" -type f 2>/dev/null | wc -l"

        return try {
            val shizukuManager = ShizukuManager.getInstance(context)
            if (shizukuManager.isAuthorized.value && shizukuManager.isServiceConnected.value) {
                val result = shizukuManager.executeCommandWithOutput(cmd)
                result?.trim()?.toIntOrNull() ?: countFiles(dir)
            } else {
                countFiles(dir)
            }
        } catch (e: Exception) {
            countFiles(dir)
        }
    }
}
