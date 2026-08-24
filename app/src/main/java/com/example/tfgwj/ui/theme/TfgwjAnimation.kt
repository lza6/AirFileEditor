@file:Suppress("MatchingDeclarationName")

package com.example.tfgwj.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * V14: 统一动画系统
 *
 * 曲线控制点、阻尼系数等为动画物理参数，不具语义的"数值常量"，
 * 故此处抑制 MagicNumber 检查。
 */
@Suppress("MagicNumber")
object TfgwjAnimation {
    // 持续时间
    const val FAST_MS = 150
    const val NORMAL_MS = 300
    const val SLOW_MS = 500

    // 缓动函数（必须在 tween/spring 之前声明，解决 inline 前向引用问题）
    val MATERIAL_EASING = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val EASE_OUT_EXPO = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    val EASE_IN_OUT_QUINT = CubicBezierEasing(0.83f, 0f, 0.17f, 1f)

    val fast = tween<Float>(FAST_MS, easing = MATERIAL_EASING)
    val normal = tween<Float>(NORMAL_MS, easing = MATERIAL_EASING)
    val slow = tween<Float>(SLOW_MS, easing = MATERIAL_EASING)

    // Spring 动画
    val spring = spring<Float>(dampingRatio = 0.6f, stiffness = 300f)
    val bouncySpring = spring<Float>(dampingRatio = 0.4f, stiffness = 200f)

    // 入场动画
    val fadeIn = tween<Float>(NORMAL_MS, easing = EASE_OUT_EXPO)
    val slideUp = tween<Int>(NORMAL_MS, easing = EASE_OUT_EXPO)
}
