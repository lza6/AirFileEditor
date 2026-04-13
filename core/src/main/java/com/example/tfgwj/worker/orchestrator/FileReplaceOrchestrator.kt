package com.example.tfgwj.worker.orchestrator

/**
 * 文件替换编排器接口
 * 定义统一的替换流程契约，将不同模式的替换逻辑抽象为独立编排器
 *
 * Design Principles:
 * - Single Responsibility: 每个 Orchestrator 只负责一种访问模式的完整流程
 * - Open/Closed: 通过接口扩展，无需修改核心 Worker
 * - Dependency Inversion: 依赖抽象（CopyStrategy）而非具体实现
 *
 * @version V8.0.0 - Architecture Evolution
 */
interface FileReplaceOrchestrator {
    /**
     * 执行文件替换主流程
     * @param androidDir 源 Android 目录（包含 data/obb 结构）
     * @param targetPackage 目标应用包名
     * @param incrementalUpdate 是否启用增量更新
     * @param progressCallback 进度回调（0-100）
     * @return 替换结果数据
     */
    suspend fun execute(
        androidDir: java.io.File,
        targetPackage: String,
        incrementalUpdate: Boolean,
        progressCallback: (progress: Int, processed: Int, total: Int, message: String, speed: Float) -> Unit,
    ): OrchestratorResult

    /**
     * 验证替换结果
     * @param totalFiles 总文件数
     * @return 验证通过的文件数
     */
    suspend fun verify(totalFiles: Int): Int

    /**
     * 获取使用的策略类型
     */
    fun getStrategyType(): StrategyType

    /**
     * 清理资源（Worker 取消时调用）
     */
    fun cleanup()
}

/**
 * 编排器执行结果
 */
sealed class OrchestratorResult {
    data class Success(
        val processedCount: Int,
        val totalFiles: Int,
        val verifiedCount: Int,
        val metadata: Map<String, String> = emptyMap(),
    ) : OrchestratorResult()

    data class Failure(
        val message: String,
        val cause: Throwable? = null,
    ) : OrchestratorResult()
}

/**
 * 策略类型枚举
 */
enum class StrategyType {
    ROOT,
    SHIZUKU,
    NATIVE,
}
