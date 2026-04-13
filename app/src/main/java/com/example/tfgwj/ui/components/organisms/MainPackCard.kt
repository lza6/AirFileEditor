package com.example.tfgwj.ui.components.organisms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tfgwj.ui.mvi.EnvironmentStatus
import com.example.tfgwj.ui.mvi.ReplacingState

/**
 * 主包操作区域 Compose 组件 (V11 迁移)
 * 替代 card_main_pack.xml 的 View-based 实现
 *
 * 包含：
 * - 应用信息头（图标、名称、包名）
 * - 环境验证状态
 * - 当前主包选择
 * - 修改文件时间
 * - 操作按钮
 */
@Composable
fun MainPackCard(
    state: ReplacingState,
    onAppInfoClick: () -> Unit,
    onCheckEnvironment: () -> Unit,
    onSelectMainPack: () -> Unit,
    onRandomizeTime: () -> Unit,
    onStartTimePicker: () -> Unit,
    onLockTime: () -> Unit,
    onApplyLockedTime: () -> Unit,
    onStartReplace: () -> Unit,
    onLaunchGame: () -> Unit,
    onCleanEnvironment: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 应用信息头
            AppInfoHeader(
                appName = state.mainPackAppName ?: "和平精英",
                packageName = state.targetPackage.ifEmpty { "com.tencent.tmgp.pubgmhd" },
                onClick = onAppInfoClick
            )

            HorizontalDivider(thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

            // 环境验证状态
            EnvironmentCheckSection(
                environmentStatus = state.environmentStatus,
                onCheckEnvironment = onCheckEnvironment
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 当前主包
            Text(
                text = "当前主包",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            // 主包选择显示
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onSelectMainPack)
                    .padding(12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = state.selectedMainPackPath?.substringAfterLast("/") ?: "未选择主包",
                    color = if (state.selectedMainPackPath != null)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 主包大小和时间信息
            if (state.selectedMainPackPath != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "主包大小：计算中...",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp
                    )
                    state.currentFileTime?.let { time ->
                        Text(
                            text = "文件时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(time)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(thickness = 1.dp, modifier = Modifier.padding(vertical = 8.dp))

            // 修改文件时间
            Text(
                text = "修改文件时间",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 时间选择框
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onStartTimePicker)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = state.lockedTime?.let { "已锁定：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(it)}" }
                        ?: "点击选择时间",
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp
                )
                // 锁定时间按钮
                IconButton(
                    onClick = onLockTime,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "锁定当前时间",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                // 应用锁定时间按钮
                if (state.lockedTime != null) {
                    IconButton(
                        onClick = onApplyLockedTime,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "应用锁定时间",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 一键随机时间按钮
            OutlinedButton(
                onClick = onRandomizeTime,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("🎲 一键随机时间", fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 主要操作按钮行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 开始替换按钮（主要按钮）
                Button(
                    onClick = onStartReplace,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    enabled = state.selectedMainPackPath != null && !state.isReplacing
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("开始替换")
                }

                // 启动游戏按钮
                FilledTonalButton(
                    onClick = onLaunchGame,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("启动游戏")
                }
            }

            // 清理环境按钮
            TextButton(
                onClick = onCleanEnvironment,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("清理环境", fontSize = 12.sp)
            }
        }
    }
}

/**
 * 应用信息头组件
 */
@Composable
private fun AppInfoHeader(
    appName: String,
    packageName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 应用图标占位
        Surface(
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = appName.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 应用名称和包名
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = appName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 切换箭头
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = "切换应用",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * 环境验证状态组件
 */
@Composable
private fun EnvironmentCheckSection(
    environmentStatus: EnvironmentStatus,
    onCheckEnvironment: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "环境状态",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.weight(1f))

            when (environmentStatus) {
                EnvironmentStatus.Unknown -> {
                    Text(
                        text = "未检测",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                EnvironmentStatus.Checking -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
                EnvironmentStatus.Valid -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "验证通过",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF4CAF50),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                EnvironmentStatus.Invalid -> {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "验证失败",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onCheckEnvironment,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("🔍 验证环境", fontSize = 13.sp)
        }
    }
}
