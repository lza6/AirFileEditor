package com.example.tfgwj.ui.accessibility

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*

/**
 * 无障碍访问工具集
 *
 * 提供无障碍访问相关的修饰符和工具函数
 *
 * @version V11.0.0 - Accessibility
 */
fun Modifier.accessibilityDescription(description: String): Modifier =
    semantics {
        contentDescription = description
    }

/**
 * 添加无障碍角色
 */
fun Modifier.accessibilityRole(role: Role): Modifier =
    semantics {
        this.role = role
    }

/**
 * 标记为按钮
 */
fun Modifier.asButton(label: String): Modifier =
    semantics {
        contentDescription = label
        role = Role.Button
    }

/**
 * 标记为图片
 */
fun Modifier.asImage(description: String): Modifier =
    semantics {
        contentDescription = description
        role = Role.Image
    }

/**
 * 标记为标题
 */
fun Modifier.asHeading(): Modifier =
    semantics {
        heading()
    }

/**
 * 标记为进度条
 */
fun Modifier.asProgressBar(progress: Float): Modifier =
    semantics {
        contentDescription = "进度 ${"%.0f".format(progress * 100)}%"
        progressBarRangeInfo =
            ProgressBarRangeInfo(
                current = progress,
                range = 0f..1f,
            )
    }

/**
 * 无涟漪点击（用于不需要视觉反馈的点击）
 */
@Composable
fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier =
    this.clickable(
        indication = null,
        interactionSource = remember { MutableInteractionSource() },
    ) {
        onClick()
    }

/**
 * 高对比度颜色检测
 */
@Composable
fun isHighContrastEnabled(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return android.provider.Settings.Secure.getInt(
        context.contentResolver,
        "high_text_contrast_enabled",
        0,
    ) == 1
}

/**
 * 屏幕阅读器检测
 */
@Composable
fun isScreenReaderEnabled(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    val accessibilityManager =
        context.getSystemService(
            android.content.Context.ACCESSIBILITY_SERVICE,
        ) as? android.view.accessibility.AccessibilityManager
    return accessibilityManager?.isEnabled == true
}
