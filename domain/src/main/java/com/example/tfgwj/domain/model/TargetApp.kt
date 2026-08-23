package com.example.tfgwj.domain.model

/**
 * V13 收口：目标应用身份模型
 *
 * 所有 UI、Worker、权限、备份、验证、日志都只使用同一个 TargetApp 实例。
 * 禁止空包名静默兜底，禁止默认包名回退。
 */
data class TargetApp(
    val packageName: String
) {
    init {
        require(isValidPackageName(packageName)) { "非法包名: $packageName" }
    }

    companion object {
        private val PACKAGE_NAME_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

        fun isValidPackageName(packageName: String): Boolean {
            return PACKAGE_NAME_PATTERN.matches(packageName)
        }
    }
}

/**
 * Android 存储类型枚举
 */
enum class AndroidType {
    DATA,
    OBB;

    companion object {
        fun fromPath(path: String): AndroidType {
            return when {
                path.contains("/data/") -> DATA
                path.contains("/obb/") -> OBB
                else -> DATA
            }
        }
    }
}

/**
 * 目标位置模型
 */
data class TargetLocation(
    val sourceRoot: java.io.File,
    val targetPackage: TargetApp,
    val androidType: AndroidType = AndroidType.DATA
)