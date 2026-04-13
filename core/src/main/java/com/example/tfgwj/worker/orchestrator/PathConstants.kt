package com.example.tfgwj.worker.orchestrator

/**
 * 路径常量管理对象
 * 集中管理所有硬编码路径，消除字符串散落
 *
 * 设计原则：
 * - 单一事实来源：所有路径模板在此定义
 * - 易于测试：可通过扩展函数覆盖（测试环境）
 * - 类型安全：提供路径构建方法避免拼接错误
 *
 * @version V8.0.0 - Architecture Evolution
 */
object PathConstants {
    // 基础路径模板
    const val STORAGE_EMULATED_0 = "/storage/emulated/0"
    const val ANDROID_DIR = "Android"
    const val DATA_DIR = "data"
    const val OBB_DIR = "obb"

    // 目标路径模板
    private val TARGET_BASE_TEMPLATE = "$STORAGE_EMULATED_0/$ANDROID_DIR/$DATA_DIR/%s"
    private val TARGET_OBB_TEMPLATE = "$STORAGE_EMULATED_0/$ANDROID_DIR/$OBB_DIR/%s"
    private val PACKAGE_NAME_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

    /**
     * 校验包名是否合法
     */
    fun isValidPackageName(packageName: String): Boolean {
        return PACKAGE_NAME_PATTERN.matches(packageName)
    }

    /**
     * 构建目标应用数据目录路径
     * @param packageName 应用包名
     * @return 完整路径如 /storage/emulated/0/Android/data/com.example.app
     */
    fun buildTargetDataPath(packageName: String): String {
        require(isValidPackageName(packageName)) { "无效包名: $packageName" }
        return TARGET_BASE_TEMPLATE.format(packageName)
    }

    /**
     * 构建目标应用 OBB 目录路径
     * @param packageName 应用包名
     * @return 完整路径如 /storage/emulated/0/Android/obb/com.example.app
     */
    fun buildTargetObbPath(packageName: String): String {
        require(isValidPackageName(packageName)) { "无效包名: $packageName" }
        return TARGET_OBB_TEMPLATE.format(packageName)
    }

    /**
     * 构建完整的目标文件路径
     * @param packageName 应用包名
     * @param subPath 子路径（相对于 data/ 或 obb/）
     * @param isObb 是否在 OBB 目录
     * @return 完整目标路径
     */
    fun buildTargetFilePath(
        packageName: String,
        subPath: String,
        isObb: Boolean = false,
    ): String {
        val base = if (isObb) buildTargetObbPath(packageName) else buildTargetDataPath(packageName)
        val safeSubPath = normalizeSubPath(subPath)
        return if (safeSubPath.isEmpty()) {
            base
        } else {
            "$base/$safeSubPath"
        }
    }

    /**
     * 从源文件路径提取 Android 类型（data 或 obb）
     * @param filePath 源文件绝对路径
     * @return "data" 或 "obb"
     */
    fun extractAndroidType(filePath: String): String {
        return when {
            filePath.contains("/$DATA_DIR/") -> DATA_DIR
            filePath.contains("/$OBB_DIR/") -> OBB_DIR
            else -> DATA_DIR // 默认返回 data
        }
    }

    /**
     * 计算相对路径（相对于 Android/data/ 或 Android/obb/）
     * @param androidDir Android 根目录
     * @param filePath 文件绝对路径
     * @return 相对路径，如 "com.example.app/files/..."
     */
    fun calculateRelativePath(
        androidDir: java.io.File,
        filePath: String,
    ): String {
        return try {
            val rootPath = androidDir.canonicalPath.replace('\\', '/').trimEnd('/')
            val fullPath = java.io.File(filePath).canonicalPath.replace('\\', '/')
            val prefix = "$rootPath/"

            if (!fullPath.startsWith(prefix)) {
                ""
            } else {
                fullPath.removePrefix(prefix)
            }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 验证路径是否为有效 Android 数据目录
     */
    fun isValidAndroidDir(dir: java.io.File): Boolean {
        val hasData = java.io.File(dir, DATA_DIR).exists()
        val hasObb = java.io.File(dir, OBB_DIR).exists()
        return hasData || hasObb
    }

    /**
     * 归一化子路径并阻断路径穿越
     */
    private fun normalizeSubPath(subPath: String): String {
        val normalized = subPath.replace('\\', '/').trim().trimStart('/')
        if (normalized.isEmpty()) return ""

        require(normalized.split('/').none { it == "." || it == ".." }) { "非法子路径: $subPath" }
        return normalized
    }
}
