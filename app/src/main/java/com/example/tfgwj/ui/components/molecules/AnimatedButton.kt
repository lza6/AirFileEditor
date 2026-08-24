package com.example.tfgwj.ui.components.molecules

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * V14: 带动画反馈的按钮
 * 点击时触发按压缩放动画，加载时显示 Spinner
 */
enum class AnimatedButtonStyle {
    FILLED, TONAL, OUTLINED, TEXT
}

@Composable
fun AnimatedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: AnimatedButtonStyle = AnimatedButtonStyle.FILLED,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    icon: ImageVector? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        label = "buttonScale",
    )

    val content = @Composable {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            icon?.let {
                Icon(it, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(text)
        }
    }

    when (style) {
        AnimatedButtonStyle.FILLED -> Button(
            onClick = onClick,
            modifier = modifier.scale(scale),
            enabled = enabled && !isLoading,
            interactionSource = interactionSource,
        ) { content() }

        AnimatedButtonStyle.TONAL -> FilledTonalButton(
            onClick = onClick,
            modifier = modifier.scale(scale),
            enabled = enabled && !isLoading,
            interactionSource = interactionSource,
        ) { content() }

        AnimatedButtonStyle.OUTLINED -> OutlinedButton(
            onClick = onClick,
            modifier = modifier.scale(scale),
            enabled = enabled && !isLoading,
            interactionSource = interactionSource,
        ) { content() }

        AnimatedButtonStyle.TEXT -> TextButton(
            onClick = onClick,
            modifier = modifier.scale(scale),
            enabled = enabled && !isLoading,
            interactionSource = interactionSource,
        ) { content() }
    }
}