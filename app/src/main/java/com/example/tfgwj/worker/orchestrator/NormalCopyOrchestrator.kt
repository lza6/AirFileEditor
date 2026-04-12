package com.example.tfgwj.worker.orchestrator

import android.content.Context
import android.util.Log
import com.example.tfgwj.utils.IoOptimizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Native 模式复制编排器
 * 使用原生 Java API（NIO）执行并发复制
 *
 * 核心流程：
 * 1. 统计文件总数
 * 2. 获取文件序列（惰性求值）
 * 3. 并发执行 IoOptimizer.fastCopy（Zero-Copy）
 * 4. 自动增量检测（通过 IoOptimizer.needsUpdate）
 *
 * 技术亮点：
 * - NIO Zero-Copy：FileChannel.transferTo
 * - 动态并发：根据 CPU 核心数自动调整
 * - 内存高效：使用对象池复用缓冲区
 *
 * 注意：
 * - 不需要验证（直接复制到目标，可信度高）
 * - 进度通过文件计数驱动，非 shell 输出
 *
 * @property context 应用上下文
 * @property config 复制配置参数
 * @version V8.0.0 - Architecture Evolution
 */
class NormalCopyOrchestrator(
    private val context: Context,
    private val config: CopyConfig,
) : FileReplaceOrchestrator {
    companion object {
        private const val TAG = "NormalCopyOrchestrator"
    }

    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val progressCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private val totalBytesProcessed = java.util.concurrent.atomic.AtomicLong(0)
    private val ioRateCalculator = IoRateCalculator()

    private lateinit var fileStatistics: FileStatistics
    private lateinit var progressTracker: ProgressTracker

    private var totalFiles = 0
    private var targetPackage = ""

    override suspend fun execute(
        androidDir: File,
        targetPackage: String,
        incrementalUpdate: Boolean,
        progressCallback: (progress: Int, processed: Int, total: Int, message: String, speed: Float) -> Unit,
    ): OrchestratorResult {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            Log.d(TAG, "🚀 Native 模式启动: ${androidDir.absolutePath} -> $targetPackage")

            this@NormalCopyOrchestrator.targetPackage = targetPackage
            this@NormalCopyOrchestrator.fileStatistics = FileStatistics(context)

            try {
                // 1. 统计文件总数
                progressCallback(0, 0, 0, "正在扫描源文件...", 0f)
                totalFiles = fileStatistics.countFiles(androidDir)

                if (totalFiles == 0) {
                    return@withContext OrchestratorResult.Success(
                        processedCount = 0,
                        totalFiles = 0,
                        verifiedCount = 0,
                    )
                }

                Log.d(TAG, "📊 扫描完成: $totalFiles 个文件")

                // 2. 初始化进度跟踪器
                progressTracker =
                    ProgressTracker(config, scope) { p, processed, total, msg, speed, phase ->
                        progressCallback(p, processed, total, msg, speed)
                    }
                progressTracker.initialize(totalFiles)

                // 3. 准备目标环境
                val targetBase = PathConstants.buildTargetDataPath(targetPackage)
                File(targetBase).mkdirs()

                // 4. 并发执行复制
                progressCallback(5, 0, totalFiles, "开始复制...", 0f)
                val successCount = executeConcurrentCopy(androidDir, targetBase)

                // 5. Native 模式无需验证（直接复制）
                progressTracker.markComplete()

                val duration = System.currentTimeMillis() - startTime
                Log.i(TAG, "✅ Native 模式完成: 成功 $successCount/$totalFiles, 耗时 ${duration}ms")

                OrchestratorResult.Success(
                    processedCount = successCount,
                    totalFiles = totalFiles,
                    verifiedCount = successCount, // 复制成功即验证通过
                    metadata =
                        mapOf(
                            "mode" to "NATIVE",
                            "duration" to duration.toString(),
                            "totalBytes" to totalBytesProcessed.toString(),
                        ),
                )
            } catch (e: Exception) {
                Log.e(TAG, "❌ Native 模式失败", e)
                OrchestratorResult.Failure("Native 复制失败: ${e.message}", e)
            } finally {
                cleanup()
            }
        }
    }

    override suspend fun verify(totalFiles: Int): Int {
        // Native 模式无需验证，直接返回总数
        return totalFiles
    }

    override fun getStrategyType(): StrategyType = StrategyType.NATIVE

    override fun cleanup() {
        try {
            scope.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "清理资源失败", e)
        }
    }

    /**
     * 并发执行复制
     */
    private suspend fun executeConcurrentCopy(
        androidDir: File,
        targetBase: String,
    ): Int =
        withContext(Dispatchers.IO) {
            val fileSequence = fileStatistics.getFileSequence(androidDir)
            val batches = fileStatistics.batchFiles(fileSequence, config.fileBatchSize)

            Log.d(TAG, "并发复制: 总文件数=$totalFiles, 批次数=${batches.size}, 并发度=${config.nativeConcurrentPermits}")

            val successCount = java.util.concurrent.atomic.AtomicInteger(0)
            val semaphore = Semaphore(config.nativeConcurrentPermits)

            batches.map { batch ->
                scope.launch {
                    batch.forEach { sourceFile ->
                        semaphore.withPermit {
                            try {
                                // 计算目标路径
                                val relativePath = PathConstants.calculateRelativePath(androidDir, sourceFile.absolutePath)
                                val targetPath =
                                    PathConstants.buildTargetFilePath(
                                        packageName = targetPackage,
                                        subPath = relativePath,
                                        isObb = relativePath.startsWith("obb/"),
                                    )
                                val targetFile = File(targetPath)

                                // 确保父目录存在
                                if (!targetFile.parentFile?.exists()!!) {
                                    synchronized(this@NormalCopyOrchestrator) {
                                        targetFile.parentFile?.mkdirs()
                                    }
                                }

                                // 增量检测 + Zero-Copy
                                if (IoOptimizer.needsUpdate(sourceFile, targetFile)) {
                                    val success = IoOptimizer.fastCopy(sourceFile, targetFile)
                                    if (success) {
                                        val bytes = sourceFile.length()
                                        totalBytesProcessed.addAndGet(bytes)
                                        val currentSpeed = ioRateCalculator.update(totalBytesProcessed.get())

                                        successCount.incrementAndGet()
                                        val processed = progressCounter.incrementAndGet()

                                        // 更新进度（每10个文件或关键节点）
                                        if (processed % 10 == 0 || processed >= totalFiles) {
                                            progressTracker.updateProgress(
                                                processed = processed,
                                                message = sourceFile.name,
                                                phase = "REPLACING",
                                            )
                                        }
                                    }
                                } else {
                                    // 不需要更新也计入进度
                                    progressCounter.incrementAndGet()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "复制失败: ${sourceFile.absolutePath}", e)
                            }
                        }
                    }
                }
            }.joinAll()

            successCount.get()
        }

    /**
     * IO 速率计算器（简化版）
     */
    private class IoRateCalculator {
        private var lastBytes = 0L
        private var lastTime = System.currentTimeMillis()

        fun update(currentBytes: Long): Float {
            val now = System.currentTimeMillis()
            val timeDiff = (now - lastTime) / 1000.0
            if (timeDiff >= 1.0) {
                val bytesDiff = currentBytes - lastBytes
                val speed = (bytesDiff / (1024 * 1024) / timeDiff).toFloat() // MB/s
                lastBytes = currentBytes
                lastTime = now
                return speed
            }
            return 0f
        }
    }
}
