package com.example.tfgwj.performance

/**
 * 性能指标数据模型
 *
 * 设计原则：
 * - 不可变数据类，线程安全
 * - 支持多维度标签（tags）用于灵活查询
 * - 时间戳使用 System.currentTimeMillis() 保证时序
 *
 * @version V10.0.0 - Performance Monitoring
 */
data class PerformanceMetric(
    val timestamp: Long = System.currentTimeMillis(),
    val category: MetricCategory,
    val name: String,
    val value: Double,
    val unit: String,
    val tags: Map<String, String> = emptyMap(),
)

/**
 * 指标分类枚举
 */
enum class MetricCategory {
    IO, // 文件IO性能
    CPU, // CPU使用率
    MEMORY, // 内存使用
    NETWORK, // 网络性能
    BATTERY, // 电量消耗
    THERMAL, // 设备温度
    TASK, // 任务执行
    IPC, // 跨进程通讯
}

/**
 * 预定义指标名称常量
 */
object MetricNames {
    // IO 指标
    const val IO_COPY_SPEED = "io_copy_speed" // 文件复制速度 (MB/s)
    const val IO_BUFFER_EFFICIENCY = "io_buffer_efficiency" // 缓冲区利用率 (%)
    const val IO_MMAP_FALLBACK_RATE = "io_mmap_fallback_rate" // mmap 回退率 (%)
    const val IO_INCREMENTAL_HIT_RATE = "io_incremental_hit_rate" // 增量更新命中率 (%)
    const val IO_CONCURRENT_UTILIZATION = "io_concurrent_util" // 并发利用率 (%)
    const val IO_WRITE_LATENCY = "io_write_latency" // 写入延迟 (ms)

    // IPC 指标 (Shizuku)
    const val IPC_BINDER_LATENCY = "ipc_binder_latency" // Binder 延迟 (ms)
    const val IPC_TRANSFER_SIZE = "ipc_transfer_size" // 传输字节数 (B)

    // 内存指标
    const val MEMORY_USED = "memory_used" // 已用内存 (MB)
    const val MEMORY_MAX = "memory_max" // 最大内存 (MB)
    const val MEMORY_USAGE_PERCENT = "memory_usage_percent" // 内存使用率 (%)

    // 任务指标
    const val TASK_DURATION = "task_duration" // 任务耗时 (ms)
    const val TASK_FILES_PROCESSED = "task_files_processed" // 已处理文件数
    const val TASK_SUCCESS_RATE = "task_success_rate" // 成功率 (%)

    // 网络指标
    const val NETWORK_DOWNLOAD_SPEED = "network_download_speed" // 下载速度 (KB/s)
    const val NETWORK_RETRY_COUNT = "network_retry_count" // 重试次数
}

/**
 * 指标单位常量
 */
object MetricUnits {
    const val MB_PER_SECOND = "MB/s"
    const val KB_PER_SECOND = "KB/s"
    const val MEGABYTES = "MB"
    const val MILLISECONDS = "ms"
    const val PERCENT = "%"
    const val COUNT = "count"
    const val CELSIUS = "°C"
}
