package com.example.tfgwj.ui.components.atoms

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * V14: 骨架屏加载组件
 * 数据加载时显示 shimmer 动画，内容就绪后替换
 */
@Composable
fun SkeletonLoader(
    modifier: Modifier = Modifier,
    width: Dp = 200.dp,
    height: Dp = 20.dp,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
) {
    val infiniteTransition = rememberInfiniteTransition(label = "skeleton")
    val shimmerTranslate by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "shimmer",
    )

    // 200f: shimmer 渐变宽度，纯视觉参数
    @Suppress("MagicNumber")
    val shimmer =
        Brush.linearGradient(
            colors =
                listOf(
                    Color.LightGray.copy(alpha = 0.3f),
                    Color.LightGray.copy(alpha = 0.6f),
                    Color.LightGray.copy(alpha = 0.3f),
                ),
            start = Offset(shimmerTranslate - 200f, 0f),
            end = Offset(shimmerTranslate, 0f),
        )

    Box(
        modifier =
            modifier
                .width(width)
                .height(height)
                .clip(shape)
                .background(shimmer),
    )
}

/**
 * 卡片骨架屏
 */
@Composable
fun CardSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkeletonLoader(
                width = 40.dp,
                height = 40.dp,
                shape = CircleShape,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                SkeletonLoader(width = 120.dp, height = 16.dp)
                Spacer(modifier = Modifier.height(8.dp))
                SkeletonLoader(width = 180.dp, height = 12.dp)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        SkeletonLoader(width = 300.dp, height = 14.dp)
        Spacer(modifier = Modifier.height(8.dp))
        SkeletonLoader(width = 250.dp, height = 14.dp)
        Spacer(modifier = Modifier.height(8.dp))
        SkeletonLoader(width = 280.dp, height = 14.dp)
    }
}
