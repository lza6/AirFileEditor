package com.example.tfgwj.worker.orchestrator

import android.content.Context
import android.util.Log
import com.example.tfgwj.domain.model.TaskPhase
import com.example.tfgwj.performance.IoEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private val permitMutex = Mutex()
    private val progressSpeedMutex = Mutex()
    @Volatile
    private var dynamicPermits: Int = 1
    @Volatile
    private var runningTasksCount: Int = 0

    private lateinit var fileStatistics: FileStatistics
    private lateinit var progressTracker: ProgressTracker
    private lateinit var verificationManager: VerificationManager
    private var scheduler: com.example.tfgwj.performance.scheduler.AdaptivePermitScheduler? = null

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
            this@NormalCopyOrchestrator.verificationManager = VerificationManager(context)

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

                // V10: 初始化自适应调度器
                dynamicPermits = config.nativeConcurrentPermits.coerceAtLeast(1)
                scheduler = com.example.tfgwj.performance.scheduler.AdaptivePermitScheduler(
                    context = context,
                    basePermits = config.nativeConcurrentPermits,
                ).apply {
                    start { newPermits ->
                        dynamicPermits = newPermits.coerceAtLeast(1)
                        Log.d(TAG, "Scheduler: permits updated to $dynamicPermits")
                    }
                }

                // 3. 准备目标环境
                val targetBase = PathConstants.buildTargetDataPath(targetPackage)
                File(targetBase).mkdirs()

                // 4. 并发执行复制
                progressCallback(5, 0, totalFiles, "开始复制...", 0f)
                val successCount = executeConcurrentCopy(androidDir, targetBase)

                // 5. 复制完成后验证实际目标树，避免把部分复制误报为成功。
                progressCallback(config.progressPhaseVerifyingStart, successCount, totalFiles, "正在验证...", 0f)
                val verifiedCount = verificationManager.verify(
                    androidDir,
                    targetPackage,
                    totalFiles,
                    VerificationMode.NATIVE,
                )
                if (verifiedCount != totalFiles) {
                    return@withContext OrchestratorResult.Failure("Native 复制验证失败: $verifiedCount/$totalFiles")
                }

                progressTracker.markComplete()

                val duration = System.currentTimeMillis() - startTime
                Log.i(TAG, "✅ Native 模式完成: 成功 $successCount/$totalFiles, 耗时 ${duration}ms")

                OrchestratorResult.Success(
                    processedCount = successCount,
                    totalFiles = totalFiles,
                    verifiedCount = verifiedCount,
                    metadata =
                        mapOf(
                            "mode" to "NATIVE",
                            "duration" to duration.toString(),
                            "totalBytes" to totalBytesProcessed.toString(),
                        ),
                )
            } catch (e: CancellationException) {
                throw e
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
            scheduler?.stop()
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

            // V10: 针对小文件进行聚合处理
            // 1. 将文件流分为 "大文件" 和 "小文件队列"
            val (smallFiles, largeFiles) = fileSequence.partition { it.length() < 1024 } // < 1KB 视为极小文件

            Log.d(TAG, "并发复制: 总文件数=$totalFiles, 小文件=${smallFiles.size}, 大文件=${largeFiles.size}")

            val successCount = java.util.concurrent.atomic.AtomicInteger(0)

            // 2. 处理大文件批次
            coroutineScope {
                val largeBatches = fileStatistics.batchFiles(largeFiles.asSequence(), config.fileBatchSize)
                largeBatches.map { batch ->
                    async { processFileBatch(batch, androidDir, successCount) }
                }.awaitAll()

                // 3. 处理小文件批次（聚合写入）
                val smallBatches = fileStatistics.batchFiles(smallFiles.asSequence(), 100)
                smallBatches.map { batch ->
                    async { processFileBatch(batch, androidDir, successCount) }
                }.awaitAll()
            }

            successCount.get()
        }

    /**
     * 处理单个文件批次
     */
    private suspend fun processFileBatch(
        batch: List<File>,
        androidDir: File,
        successCount: java.util.concurrent.atomic.AtomicInteger,
    ) {
        batch.forEach { sourceFile ->
            try {
                processWithAdaptiveLimit {
                    // 仅保留源包目录内的相对内容，始终写入当前选定的目标包。
                    val targetFile = PathConstants.resolveTargetFile(androidDir, sourceFile, targetPackage)

                    // 确保父目录存在
                    val parent = targetFile.parentFile
                    if (parent != null && !parent.exists()) {
                        synchronized(this@NormalCopyOrchestrator) {
                            if (!parent.exists()) {
                                parent.mkdirs()
                            }
                        }
                    }

                    // 增量检测 + Zero-Copy (V14: IoEngine 统一入口)
                    if (IoEngine.needsUpdate(sourceFile, targetFile)) {
                        // IoEngine.fastCopy 返回 Long(复制字节数)，>0 表示成功
                        val copied = IoEngine.fastCopy(sourceFile, targetFile)
                        if (copied > 0L) {
                            val bytes = sourceFile.length()
                            totalBytesProcessed.addAndGet(bytes)
                            progressSpeedMutex.withLock {
                                ioRateCalculator.update(totalBytesProcessed.get())
                            }

                            successCount.incrementAndGet()
                            val processed = progressCounter.incrementAndGet()

                            // 更新进度（每10个文件或关键节点）
                            if (processed % 10 == 0 || processed >= totalFiles) {
                                progressTracker.updateProgress(
                                    processed = processed,
                                    message = sourceFile.name,
                                    phase = TaskPhase.REPLACING,
                                )
                            }
                        } else {
                            throw IllegalStateException("复制失败: ${sourceFile.absolutePath}")
                        }
                    } else {
                        // 已经一致的文件也是本次完整结果的一部分。
                        successCount.incrementAndGet()
                        val processed = progressCounter.incrementAndGet()
                        if (processed % 10 == 0 || processed >= totalFiles) {
                            progressTracker.updateProgress(
                                processed = processed,
                                message = sourceFile.name,
                                phase = TaskPhase.REPLACING,
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "复制失败: ${sourceFile.absolutePath}", e)
                throw e
            }
        }
    }

    /**
     * 根据 scheduler 的 permits 做软并发限制，避免动态替换 Semaphore 引发竞态
     */
    private suspend fun processWithAdaptiveLimit(action: suspend () -> Unit) {
        while (true) {
            val permits = dynamicPermits.coerceAtLeast(1)
            val acquired = withTimeoutOrNull(1000) {
                permitMutex.withLock {
                    if (runningTasksCount < permits) {
                        runningTasksCount++
                        true
                    } else {
                        false
                    }
                }
            } ?: false

            if (acquired) break
            delay(50) // 等待许可释放
        }

        try {
            action()
        } finally {
            permitMutex.withLock {
                runningTasksCount = (runningTasksCount - 1).coerceAtLeast(0)
            }
        }
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
