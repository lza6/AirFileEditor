package com.example.tfgwj.performance

/**
 * 内存水位守护 (V18 性能引擎 2.0)
 *
 * 在文件复制/解压前评估设备可用内存，动态调整并发、mmap 策略与缓冲区大小，
 * 避免低内存设备在大文件操作时 OOM。
 *
 * 设计原则：
 * - 纯计算，不持有 Android Context（由读取方注入 memoryInfo，便于单测）
 * - 压力等级单调：LOW(丰裕) / MEDIUM(中等) / HIGH(紧张)
 * - 阈值可配置，默认按行业通用经验值
 */
enum class MemoryPressureLevel {
    LOW,
    MEDIUM,
    HIGH,
}

/**
 * 一次内存快照，供 [MemoryPressureGuard] 判级
 */
data class MemorySnapshot(
    /** 可用内存字节数 */
    val availMem: Long,
    /** 总内存字节数 */
    val totalMem: Long,
) {
    val availRatio: Float
        get() = if (totalMem <= 0L) 0f else availMem.toFloat() / totalMem.toFloat()
}

/**
 * 内存压力判定器
 *
 * 不直接读系统内存（避免测试依赖 Android），由外部调用方构造 [MemorySnapshot]。
 */
object MemoryPressureGuard {

    // 压力阈值：可用内存比例低于 HIGH 即进入高压力
    const val HIGH_PRESSURE_THRESHOLD = 0.15f
    const val MEDIUM_PRESSURE_THRESHOLD = 0.30f

    /**
     * 根据内存快照判定压力等级
     *
     * 无法读取内存（totalMem <= 0）视为未知，按 LOW 处理：
     * 不因读取失败而误判为高压力，避免无谓地降低并发/禁用 mmap。
     */
    fun assess(snapshot: MemorySnapshot): MemoryPressureLevel {
        if (snapshot.totalMem <= 0L) return MemoryPressureLevel.LOW
        return when {
            snapshot.availRatio < HIGH_PRESSURE_THRESHOLD -> MemoryPressureLevel.HIGH
            snapshot.availRatio < MEDIUM_PRESSURE_THRESHOLD -> MemoryPressureLevel.MEDIUM
            else -> MemoryPressureLevel.LOW
        }
    }

    /**
     * 根据压力等级返回建议并发数
     * @param baseConcurrency 无压力时的理想并发
     */
    fun recommendedConcurrency(
        level: MemoryPressureLevel,
        baseConcurrency: Int,
    ): Int {
        return when (level) {
            MemoryPressureLevel.HIGH -> (baseConcurrency / 4).coerceAtLeast(1)
            MemoryPressureLevel.MEDIUM -> (baseConcurrency / 2).coerceAtLeast(1)
            MemoryPressureLevel.LOW -> baseConcurrency
        }
    }

    /**
     * 是否允许启用 mmap 大文件零拷贝
     * @param level 压力等级
     * @return 高压力下禁用 mmap，避免映射过多虚存
     */
    fun shouldUseMmap(level: MemoryPressureLevel): Boolean {
        return level != MemoryPressureLevel.HIGH
    }
}
