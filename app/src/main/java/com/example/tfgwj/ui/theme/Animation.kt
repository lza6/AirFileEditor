package com.example.tfgwj.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * V14: 统一动画系统
 */
object TfgwjAnimation {
    // 持续时间
    const val fastMs = 150
    const val normalMs = 300
    const val slowMs = 500

    // 缓动函数（必须在 tween/spring 之前声明，解决 inline 前向引用问题）
    val MaterialEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val EaseOutExpo = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    val EaseInOutQuint = CubicBezierEasing(0.83f, 0f, 0.17f, 1f)

    val fast = tween<Float>(fastMs, easing = MaterialEasing)
    val normal = tween<Float>(normalMs, easing = MaterialEasing)
    val slow = tween<Float>(slowMs, easing = MaterialEasing)

    // Spring 动画
    val spring = spring<Float>(dampingRatio = 0.6f, stiffness = 300f)
    val bouncySpring = spring<Float>(dampingRatio = 0.4f, stiffness = 200f)

    // 入场动画
    val fadeIn = tween<Float>(300, easing = EaseOutExpo)
    val slideUp = tween<Int>(300, easing = EaseOutExpo)
}