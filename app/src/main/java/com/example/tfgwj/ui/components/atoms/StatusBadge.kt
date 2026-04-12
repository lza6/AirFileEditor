package com.example.tfgwj.ui.components.atoms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tfgwj.ui.theme.Green40
import com.example.tfgwj.ui.theme.Orange40
import com.example.tfgwj.ui.theme.Red40

/**
 * 任务状态勋章
 * 显示任务当前的状态（成功/失败/进行中）
 */
enum class TaskStatus {
    IDLE,
    RUNNING,
    SUCCESS,
    FAILED,
    PAUSED
}

@Composable
fun StatusBadge(
    status: TaskStatus,
    modifier: Modifier = Modifier
) {
    val (text, bgColor, textColor) = when (status) {
        TaskStatus.IDLE -> Triple("空闲", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        TaskStatus.RUNNING -> Triple("进行中", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        TaskStatus.SUCCESS -> Triple("成功", Green40.copy(alpha = 0.2f), Green40)
        TaskStatus.FAILED -> Triple("失败", Red40.copy(alpha = 0.2f), Red40)
        TaskStatus.PAUSED -> Triple("已暂停", Orange40.copy(alpha = 0.2f), Orange40)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
