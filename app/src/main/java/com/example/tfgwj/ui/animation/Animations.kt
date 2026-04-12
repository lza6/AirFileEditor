package com.example.tfgwj.ui.animation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

/**
 * 动画工具集
 *
 * 提供常用的 Compose 动画预设，简化动画使用
 *
 * @version V11.0.0 - Animation Enhancement
 */

/**
 * 淡入淡出动画
 */
@Composable
fun FadeTransition(
    visible: Boolean,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(300)),
        exit = fadeOut(animationSpec = tween(300)),
        content = content,
    )
}

/**
 * 滑动动画（从底部滑入）
 */
@Composable
fun SlideUpTransition(
    visible: Boolean,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter =
            slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(300, easing = EaseOutCubic),
            ) + fadeIn(animationSpec = tween(300)),
        exit =
            slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(300, easing = EaseInCubic),
            ) + fadeOut(animationSpec = tween(300)),
        content = content,
    )
}

/**
 * 缩放动画
 */
@Composable
fun ScaleTransition(
    visible: Boolean,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter =
            scaleIn(
                initialScale = 0.8f,
                animationSpec = tween(300, easing = EaseOutCubic),
            ) + fadeIn(animationSpec = tween(300)),
        exit =
            scaleOut(
                targetScale = 0.8f,
                animationSpec = tween(300, easing = EaseInCubic),
            ) + fadeOut(animationSpec = tween(300)),
        content = content,
    )
}

/**
 * 交叉淡入动画（用于内容切换）
 */
@Composable
fun CrossfadeTransition(
    targetState: Any?,
    content: @Composable (Any?) -> Unit,
) {
    Crossfade(
        targetState = targetState,
        animationSpec = tween(300),
        content = content,
    )
}

/**
 * 脉冲动画（用于加载状态）
 */
@Composable
fun pulseAnimation(): Float {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1000, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "pulseAlpha",
    )
    return alpha
}

/**
 * 旋转动画（用于加载指示器）
 */
@Composable
fun rotationAnimation(): Float {
    val infiniteTransition = rememberInfiniteTransition(label = "rotation")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "rotation",
    )
    return angle
}

/**
 * 弹跳动画（用于按钮点击反馈）
 */
@Composable
fun bounceAnimation(trigger: Boolean): Float {
    val scale by animateFloatAsState(
        targetValue = if (trigger) 0.9f else 1.0f,
        animationSpec =
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        label = "bounce",
    )
    return scale
}

/**
 * 进度条动画
 */
@Composable
fun animateProgressAsState(targetProgress: Float): Float {
    val progress by animateFloatAsState(
        targetValue = targetProgress.coerceIn(0f, 1f),
        animationSpec = tween(500, easing = EaseOutCubic),
        label = "progress",
    )
    return progress
}

/**
 * 抖动动画（用于错误提示）
 */
@Composable
fun shakeAnimation(trigger: Boolean): Float {
    val offset by animateFloatAsState(
        targetValue = if (trigger) 1f else 0f,
        animationSpec =
            keyframes {
                durationMillis = 500
                0f at 0
                -10f at 50
                10f at 100
                -10f at 150
                10f at 200
                -5f at 250
                5f at 300
                -2f at 350
                2f at 400
                0f at 500
            },
        label = "shake",
    )
    return offset
}
