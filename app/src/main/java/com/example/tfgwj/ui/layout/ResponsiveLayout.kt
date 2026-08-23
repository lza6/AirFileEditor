package com.example.tfgwj.ui.layout

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 响应式布局工具
 *
 * 支持手机/平板/折叠屏适配
 *
 * @version V11.0.0 - Responsive Layout
 */
enum class WindowSizeClass {
    COMPACT, // 手机竖屏 (< 600dp)
    MEDIUM, // 手机横屏/小平板 (600dp - 840dp)
    EXPANDED, // 大平板/折叠屏 (> 840dp)
}

/**
 * 获取当前窗口尺寸分类
 */
@Composable
fun rememberWindowSizeClass(): WindowSizeClass {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp

    return remember(screenWidth) {
        when {
            screenWidth < 600 -> WindowSizeClass.COMPACT
            screenWidth < 840 -> WindowSizeClass.MEDIUM
            else -> WindowSizeClass.EXPANDED
        }
    }
}

/**
 * 响应式间距
 */
@Composable
fun responsiveSpacing(): Dp {
    val windowClass = rememberWindowSizeClass()
    return when (windowClass) {
        WindowSizeClass.COMPACT -> 16.dp
        WindowSizeClass.MEDIUM -> 24.dp
        WindowSizeClass.EXPANDED -> 32.dp
    }
}

/**
 * 响应式内容最大宽度
 */
@Composable
fun responsiveMaxWidth(): Dp {
    val windowClass = rememberWindowSizeClass()
    return when (windowClass) {
        WindowSizeClass.COMPACT -> Dp.Infinity
        WindowSizeClass.MEDIUM -> 600.dp
        WindowSizeClass.EXPANDED -> 840.dp
    }
}

/**
 * 响应式列数（用于网格布局）
 */
@Composable
fun responsiveColumns(): Int {
    val windowClass = rememberWindowSizeClass()
    return when (windowClass) {
        WindowSizeClass.COMPACT -> 1
        WindowSizeClass.MEDIUM -> 2
        WindowSizeClass.EXPANDED -> 3
    }
}

/**
 * 响应式容器
 * 根据屏幕尺寸自动调整布局
 */
@Composable
fun ResponsiveContainer(content: @Composable BoxWithConstraintsScope.() -> Unit) {
    BoxWithConstraints(
        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
    ) {
        val spacing = responsiveSpacing()
        content()
    }
}

/**
 * 自适应网格布局
 */
@Composable
fun <T> AdaptiveGrid(
    items: List<T>,
    columns: Int = responsiveColumns(),
    spacing: Dp = responsiveSpacing(),
    itemContent: @Composable (T) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        items.chunked(columns).forEach { rowItems ->
            Row(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                rowItems.forEach { item ->
                    Box(
                        modifier = androidx.compose.ui.Modifier.weight(1f),
                    ) {
                        itemContent(item)
                    }
                }
                // 填充剩余空间
                if (rowItems.size < columns) {
                    repeat(columns - rowItems.size) {
                        Spacer(modifier = androidx.compose.ui.Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * 夜间模式检测
 */
@Composable
fun isDarkMode(): Boolean {
    val configuration = LocalConfiguration.current
    return (configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
}

/**
 * 屏幕方向检测
 */
@Composable
fun isLandscape(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
}

/**
 * 字体缩放因子
 */
@Composable
fun fontScaleFactor(): Float {
    val configuration = LocalConfiguration.current
    return configuration.fontScale
}

/**
 * 密度因子
 */
@Composable
fun densityFactor(): Float {
    val context = LocalContext.current
    return context.resources.displayMetrics.density
}
