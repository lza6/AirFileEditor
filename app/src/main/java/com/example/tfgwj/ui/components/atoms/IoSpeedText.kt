package com.example.tfgwj.ui.components.atoms

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * IO 速度文本显示
 * 自动转换单位（B/s, KB/s, MB/s）
 */
@Composable
fun IoSpeedText(
    speedMBps: Float,
    modifier: Modifier = Modifier
) {
    val speedText = when {
        speedMBps <= 0 -> "0 KB/s"
        speedMBps < 0.1f -> String.format(Locale.getDefault(), "%.1f KB/s", speedMBps * 1024)
        speedMBps < 1f -> String.format(Locale.getDefault(), "%.0f KB/s", speedMBps * 1024)
        else -> String.format(Locale.getDefault(), "%.1f MB/s", speedMBps)
    }

    Text(
        text = speedText,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary
        )
    )
}
