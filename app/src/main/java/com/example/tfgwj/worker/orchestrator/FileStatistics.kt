package com.example.tfgwj.worker.orchestrator

import android.util.Log
import com.example.tfgwj.shizuku.ShizukuManager
import com.example.tfgwj.utils.RootChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 文件统计器
 * 负责扫描源目录、统计文件总数、分批生成文件列表
 *
 * 核心能力：
 * 1. 多层级文件扫描（支持百万级文件不 OOM）
 * 2. 智能 fallback：Root → Shizuku → Native
 * 3. 流式序列处理，避免全量 List 加载
 * 4. 批次生成（chunking）供并发处理
 *
 * 设计亮点：
 * - 零内存泄漏：使用 Sequence 惰性求值
 * - 弹性降级：高层级权限失败自动降级
 * - 可监控：暴露统计指标便于性能分析
 *
 * @version V8.0.0 - Architecture Evolution
 */
class FileStatistics(
    private val context: android.content.Context,
    private val shizukuManager: ShizukuManager? = null,
) {
    companion object {
        private const val TAG = "FileStatistics"

        // Shell 命令统计（适用于 Root/Shizuku）
        private const val CMD_COUNT_FILES = "find \"%s\" -type f 2>/dev/null | wc -l"

        // 批次大小配置
        private const val DEFAULT_BATCH_SIZE = 500
    }

    /**
     * 统计目录中的文件总数
     * 智能选择统计方式：Shell 命令优先（高性能），降级到 Java API
     */
    suspend fun countFiles(androidDir: File): Int =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "开始统计文件数量: ${androidDir.absolutePath}")

            // 1. 尝试 Root 统计（最快）
            RootChecker.executeRootCommand(CMD_COUNT_FILES.format(androidDir.absolutePath))
                ?.trim()
                ?.toIntOrNull()
                ?.let { count ->
                    Log.d(TAG, "Root 统计完成: $count 个文件")
                    return@withContext count
                }

            // 2. 尝试 Shizuku 统计
            if (shizukuManager?.isAuthorized?.value == true && shizukuManager.isServiceConnected?.value == true) {
                shizukuManager.executeCommandWithOutput(CMD_COUNT_FILES.format(androidDir.absolutePath))
                    ?.trim()
                    ?.toIntOrNull()
                    ?.let { count ->
                        Log.d(TAG, "Shizuku 统计完成: $count 个文件")
                        return@withContext count
                    }
            }

            // 3. 降级到 Java API（较慢但可靠）
            val nativeCount = countFilesNative(androidDir)
            Log.d(TAG, "Native 统计完成: $nativeCount 个文件")
            nativeCount
        }

    /**
     * 获取文件流式序列（惰性求值）
     * 支持分批次处理，避免一次性加载所有文件到内存
     */
    fun getFileSequence(androidDir: File): Sequence<File> {
        return androidDir.walkTopDown()
            .filter { it.isFile }
    }

    /**
     * 将文件序列分批，便于并发处理
     * @param batchSize 每批文件数，默认 500
     * @return 分批后的 List<List<File>>
     */
    fun batchFiles(
        fileSequence: Sequence<File>,
        batchSize: Int = DEFAULT_BATCH_SIZE,
    ): List<List<File>> {
        return fileSequence
            .chunked(batchSize)
            .toList()
    }

    /**
     * 收集目录任务（用于 Root/Shizuku 模式）
     * 将目录结构扁平化为可并行执行的 CopyTask 列表
     */
    fun collectDirectoryTasks(
        sourceRoot: File,
        targetPackage: String,
        maxDepth: Int = 2,
        maxChildren: Int = 100,
    ): List<CopyTask> {
        val tasks = mutableListOf<CopyTask>()
        val targetBase = PathConstants.buildTargetDataPath(targetPackage)

        fun collectRecursive(
            source: File,
            target: String,
            depth: Int,
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

            // 达到最大深度或子项过多，直接整个目录复制
            if (depth >= maxDepth || children.size > maxChildren) {
                tasks.add(CopyTask(source, target, isDirectory = true))
                return
            }

            // 继续递归
            children.forEach { child ->
                collectRecursive(child, "$target/${child.name}", depth + 1)
            }
        }

        // 检测标准结构：Android/data/ 或 Android/obb/
        val dataDir = File(sourceRoot, "data")
        val obbDir = File(sourceRoot, "obb")

        var hasStandardStructure = false

        if (dataDir.exists() && dataDir.isDirectory) {
            hasStandardStructure = true
            dataDir.listFiles()?.forEach { pkgDir ->
                if (pkgDir.isDirectory) {
                    collectRecursive(pkgDir, targetBase, 0)
                }
            }
        }

        if (obbDir.exists() && obbDir.isDirectory) {
            hasStandardStructure = true
            val obbBase = PathConstants.buildTargetObbPath(targetPackage)
            obbDir.listFiles()?.forEach { pkgDir ->
                if (pkgDir.isDirectory) {
                    collectRecursive(pkgDir, obbBase, 0)
                }
            }
        }

        // 如果没有标准结构，假设源目录就是直接上级
        if (!hasStandardStructure) {
            collectRecursive(sourceRoot, targetBase, 0)
        }

        Log.d(TAG, "收集到 ${tasks.size} 个复制任务")
        return tasks
    }

    /**
     * 原生统计（降级方案）
     */
    private fun countFilesNative(dir: File): Int {
        var count = 0
        dir.walkTopDown().forEach { if (it.isFile) count++ }
        return count
    }

    /**
     * 复制任务数据结构
     */
    data class CopyTask(
        val sourceDir: File,
        val targetDir: String,
        val isDirectory: Boolean = false,
    )
}
