package com.example.tfgwj.worker

import android.content.Context
import android.util.Log
import com.example.tfgwj.domain.model.TaskPhase
import com.example.tfgwj.utils.IoRateCalculator
import com.example.tfgwj.utils.PauseControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.File

/**
 * NormalCopyStrategy - V7.0.0 Architecture Evolution
 *
 * Native mode file copy implementation.
 * Uses coroutine parallelism for multi-file copy.
 * Zero-copy support via IoOptimizer.
 */
class NormalCopyStrategy(
    context: Context,
    targetPackage: String,
) : CopyStrategy(context, targetPackage) {
    companion object {
        private const val TAG = "NormalCopyStrategy"
    }

    private val taskController = TaskControllerProvider.get()

    override val strategyName: String = "NORMAL"

    override suspend fun executeBatchCopy(
        sourceDir: File,
        incrementalUpdate: Boolean,
        callback: CopyStrategy.ProgressCallback?,
    ): CopyStrategy.CopyResult =
        withContext(Dispatchers.IO) {
            val targetBase = "/storage/emulated/0/Android/data/$targetPackage"
            val processedCount = java.util.concurrent.atomic.AtomicInteger(0)

            Log.d(TAG, "========== 普通模式并发复制 ==========")
            Log.d(TAG, "源路径: ${sourceDir.absolutePath}")

            // Count files
            val totalFiles = countFilesRoot(sourceDir)

            // Prepare target
            File(targetBase).mkdirs()

            // Get file stream
            val filesToCopy = sourceDir.walkTopDown().filter { it.isFile }

            if (totalFiles == 0) {
                return@withContext CopyStrategy.CopyResult.success(
                    processedCount = 0,
                    totalCount = 0,
                    mode = strategyName,
                )
            }

            // Calculate dynamic concurrency
            val cpuCores = Runtime.getRuntime().availableProcessors()
            val dynamicPermits = (cpuCores * 2).coerceAtLeast(4).coerceAtMost(32)
            Log.d(TAG, "普通模式并发度: $dynamicPermits")

            val semaphore = Semaphore(dynamicPermits)
            val ioRateCalculator = IoRateCalculator()
            val totalBytesProcessed = java.util.concurrent.atomic.AtomicLong(0)

            coroutineScope {
                filesToCopy.chunked(32).forEach { batch ->
                    batch.map { file ->
                        launch {
                            try {
                                PauseControl.waitIfPaused()
                                semaphore.acquire()

                                // Path mapping
                                val fullPath = file.absolutePath
                                val androidType =
                                    when {
                                        fullPath.contains("/data/") -> "data"
                                        fullPath.contains("/obb/") -> "obb"
                                        else -> "data"
                                    }

                                val subPath = fullPath.substringAfter("/$androidType/").substringAfter("/", "")
                                if (subPath.isNotEmpty()) {
                                    val realTargetBase = "/storage/emulated/0/Android/$androidType/$targetPackage"
                                    val targetFile = File(realTargetBase, subPath)

                                    // Ensure parent directory
                                    if (targetFile.parentFile?.exists() == false) {
                                        synchronized(this@NormalCopyStrategy) {
                                            targetFile.parentFile?.mkdirs()
                                        }
                                    }

                                    // Execute Zero-Copy
                                    val success = com.example.tfgwj.performance.IoEngine.fastCopy(file, targetFile) > 0L
                                    val bytes = if (success) file.length() else 0L

                                    val currentBytes = totalBytesProcessed.addAndGet(bytes)
                                    val currentProcessed = processedCount.incrementAndGet()

                                    // Calculate speed and progress
                                    val speed = ioRateCalculator.update(currentBytes)

                                    if (currentProcessed % 10 == 0 || currentProcessed == totalFiles) {
                                        val p = ((currentProcessed.toFloat() / totalFiles) * 100).toInt().coerceIn(0, 100)
                                        callback?.onProgress(
                                            progress = p,
                                            processed = currentProcessed,
                                            total = totalFiles,
                                            currentFile = file.name,
                                            speed = speed,
                                            phase = TaskPhase.REPLACING,
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Copy Error: ${file.name}", e)
                            } finally {
                                semaphore.release()
                            }
                        }
                    }.joinAll()
                }
            }

            Log.d(TAG, "普通模式完成")
            taskController.finish()

            CopyStrategy.CopyResult.success(
                processedCount = processedCount.get(),
                totalCount = totalFiles,
                mode = strategyName,
            )
        }

    /**
     * Count files in directory
     */
    private fun countFilesRoot(dir: File): Int {
        var count = 0
        dir.walkTopDown().forEach { if (it.isFile) count++ }
        return count
    }
}
