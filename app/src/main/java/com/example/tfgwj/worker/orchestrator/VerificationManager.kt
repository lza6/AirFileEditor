package com.example.tfgwj.worker.orchestrator

import android.util.Log
import com.example.tfgwj.shizuku.ShizukuManager
import com.example.tfgwj.utils.RootChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 验证管理器
 * 统一管理三种模式的替换后验证逻辑
 *
 * 验证策略：
 * 1. Root 模式：使用 stat 命令批量检查文件大小（高性能）
 * 2. Shizuku 模式：同 Root，使用 Shizuku 执行
 * 3. Native 模式：使用 Java API 遍历目标目录
 *
 * 设计原则：
 * - 批量化：减少命令执行次数
 * - 并行化：利用多核 CPU 加速
 * - 容错性：部分失败不影响整体结果
 *
 * @version V8.0.0 - Architecture Evolution
 */
class VerificationManager(
    private val context: android.content.Context,
    private val shizukuManager: ShizukuManager? = null,
) {
    companion object {
        private const val TAG = "VerificationManager"

        // 批量大小
        private const val VERIFY_BATCH_SIZE = 500

        // stat 命令模板：输出 "大小 路径"
        private const val CMD_STAT_FILES = "stat -c \"%%s %%n\" %s"
    }

    /**
     * 验证替换结果
     * @param androidDir 源 Android 目录
     * @param targetPackage 目标包名
     * @param totalFiles 预期文件总数
     * @param mode 验证模式
     * @return 验证通过的文件数
     */
    suspend fun verify(
        androidDir: File,
        targetPackage: String,
        totalFiles: Int,
        mode: VerificationMode,
    ): Int =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "开始验证 (${mode.name}): 源=${androidDir.absolutePath}, 包名=$targetPackage")

            return@withContext when (mode) {
                VerificationMode.ROOT -> verifyViaRoot(androidDir, targetPackage, totalFiles)
                VerificationMode.SHIZUKU -> verifyViaShizuku(androidDir, targetPackage, totalFiles)
                VerificationMode.NATIVE -> verifyViaNative(androidDir, targetPackage, totalFiles)
            }
        }

    /**
     * Root 模式验证（使用 stat 命令）
     */
    private suspend fun verifyViaRoot(
        androidDir: File,
        targetPackage: String,
        totalFiles: Int,
    ): Int {
        val targetBase = PathConstants.buildTargetDataPath(targetPackage)
        val sourceFiles = getSourceFilesChunked(androidDir, VERIFY_BATCH_SIZE)
        val verifiedTotal = java.util.concurrent.atomic.AtomicInteger(0)

        Log.d(TAG, "Root 验证: 共 ${sourceFiles.size} 个批次")

        sourceFiles.forEach { batch ->
            // 构建目标路径列表
            val targetPaths =
                batch.mapNotNull { srcFile ->
                    buildTargetPath(srcFile, androidDir, targetBase, targetPackage)
                }

            if (targetPaths.isNotEmpty()) {
                // 批量 stat 命令
                val cmd = CMD_STAT_FILES.format(targetPaths.joinToString(" "))

                try {
                    val output = RootChecker.executeRootCommand(cmd)
                    val resultMap = parseStatOutput(output)

                    // 校验
                    targetPaths.forEach { (targetPath, expectedSize) ->
                        val actualSize = resultMap[targetPath]
                        if (actualSize != null && actualSize == expectedSize) {
                            verifiedTotal.incrementAndGet()
                        } else {
                            Log.w(TAG, "校验失败: $targetPath (期望: $expectedSize, 实际: $actualSize)")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "批量校验失败", e)
                }
            }
        }

        val verified = verifiedTotal.get()
        Log.d(TAG, "Root 验证完成: $verified / $totalFiles")
        return verified
    }

    /**
     * Shizuku 模式验证（同 Root，使用 Shizuku 执行）
     */
    private suspend fun verifyViaShizuku(
        androidDir: File,
        targetPackage: String,
        totalFiles: Int,
    ): Int {
        val targetBase = PathConstants.buildTargetDataPath(targetPackage)
        val sourceFiles = getSourceFilesChunked(androidDir, VERIFY_BATCH_SIZE)
        val verifiedTotal = java.util.concurrent.atomic.AtomicInteger(0)

        Log.d(TAG, "Shizuku 验证: 共 ${sourceFiles.size} 个批次")

        sourceFiles.forEach { batch ->
            val targetPaths =
                batch.mapNotNull { srcFile ->
                    buildTargetPath(srcFile, androidDir, targetBase, targetPackage)
                }

            if (targetPaths.isNotEmpty()) {
                val cmd = CMD_STAT_FILES.format(targetPaths.joinToString(" "))

                try {
                    val output = shizukuManager?.executeCommandWithOutput(cmd)
                    val resultMap = parseStatOutput(output)

                    targetPaths.forEach { (targetPath, expectedSize) ->
                        val actualSize = resultMap[targetPath]
                        if (actualSize != null && actualSize == expectedSize) {
                            verifiedTotal.incrementAndGet()
                        } else {
                            Log.w(TAG, "校验失败: $targetPath")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Shizuku 批量校验失败", e)
                }
            }
        }

        val verified = verifiedTotal.get()
        Log.d(TAG, "Shizuku 验证完成: $verified / $totalFiles")
        return verified
    }

    /**
     * Native 模式验证（使用 Java API）
     */
    private suspend fun verifyViaNative(
        androidDir: File,
        targetPackage: String,
        totalFiles: Int,
    ): Int =
        withContext(Dispatchers.IO) {
            val targetBase = PathConstants.buildTargetDataPath(targetPackage)
            val targetDir = File(targetBase)

            if (!targetDir.exists()) {
                Log.w(TAG, "目标目录不存在: $targetBase")
                return@withContext 0
            }

            // 使用协程并发统计
            val sourceFiles = getSourceFilesChunked(androidDir, VERIFY_BATCH_SIZE)
            val verifiedTotal = java.util.concurrent.atomic.AtomicInteger(0)
            val semaphore = Semaphore(Runtime.getRuntime().availableProcessors() * 2)

            coroutineScope {
                sourceFiles.forEach { batch ->
                    val jobs =
                        batch.map { srcFile ->
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    val targetInfo = buildTargetPath(srcFile, androidDir, targetBase, targetPackage)
                                    if (targetInfo != null) {
                                        val targetFile = File(targetInfo.first)
                                        if (targetFile.exists() && targetFile.length() == srcFile.length()) {
                                            verifiedTotal.incrementAndGet()
                                        }
                                    }
                                }
                            }
                        }
                    jobs.awaitAll()
                }
            }

            val verified = verifiedTotal.get()
            Log.d(TAG, "Native 验证完成: $verified / $totalFiles")
            verified
        }

    /**
     * 获取源文件列表并分批
     */
    private fun getSourceFilesChunked(
        androidDir: File,
        batchSize: Int,
    ): List<List<File>> {
        return androidDir.walkTopDown()
            .filter { it.isFile }
            .chunked(batchSize)
            .toList()
    }

    /**
     * 构建目标路径并返回（期望大小, 路径）对
     */
    private fun buildTargetPath(
        srcFile: File,
        androidDir: File,
        targetBase: String,
        targetPackage: String,
    ): Pair<String, Long>? {
        val relativePath = PathConstants.calculateRelativePath(androidDir, srcFile.absolutePath)
        if (relativePath.isEmpty()) return null

        val targetPath =
            PathConstants.buildTargetFilePath(
                packageName = targetPackage,
                subPath = relativePath,
                isObb = relativePath.startsWith("obb/"),
            )

        return Pair(targetPath, srcFile.length())
    }

    /**
     * 解析 stat 命令输出
     * 输入格式：每行 "大小 路径"
     */
    private fun parseStatOutput(output: String?): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        if (output.isNullOrBlank()) return result

        output.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                val parts = trimmed.split(" ", limit = 2)
                if (parts.size == 2) {
                    val size = parts[0].toLongOrNull()
                    val path = parts[1]
                    if (size != null) {
                        result[path] = size
                    }
                }
            }
        }

        return result
    }
}

/**
 * 验证模式枚举
 */
enum class VerificationMode {
    ROOT,
    SHIZUKU,
    NATIVE,
}
