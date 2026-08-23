package com.example.tfgwj.ui.components.molecules

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tfgwj.utils.PermissionChecker
import com.example.tfgwj.utils.PermissionManager

/**
 * 权限状态卡片 (V11 Compose 迁移)
 */
@Composable
fun PermissionCard(
    status: PermissionManager.PermissionStatus,
    onRequestPermission: () -> Unit,
    onManualSelectMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = MaterialTheme.shapes.medium,
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "权限状态",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                val (icon, color) =
                    when (status.bestMode) {
                        PermissionChecker.AccessMode.ROOT,
                        PermissionChecker.AccessMode.NATIVE,
                        PermissionChecker.AccessMode.SHIZUKU,
                        -> Icons.Default.CheckCircle to Color(0xFF4CAF50)
                        else ->
                            if (status.hasManageStorage) {
                                Icons.Default.Info to
                                    Color(
                                        0xFFFFC107,
                                    )
                            } else {
                                Icons.Default.Error to Color(0xFFF44336)
                            }
                    }

                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 状态消息解析 (支持简单的 HTML 颜色标识)
            Text(
                text = status.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 如果是 Shizuku 连接中，额外提示
            if (status.statusMessage.contains("Shizuku 服务连接中")) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "如果一直在连接中请重启 Shizuku 并重新授权。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                )
            }

            // 授权按钮逻辑
            val showRequestButton =
                when {
                    status.hasRoot -> false
                    status.canAccessPrivateDir -> false
                    !status.hasManageStorage -> true
                    status.availableModes.contains(PermissionChecker.AccessMode.SHIZUKU) && !status.hasShizukuPermission -> true
                    else -> false
                }

            if (showRequestButton) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                ) {
                    val btnText =
                        when {
                            !status.hasManageStorage -> "授权存储权限"
                            status.availableModes.contains(
                                PermissionChecker.AccessMode.SHIZUKU,
                            ) && !status.isShizukuAvailable -> "安装 Shizuku"
                            status.availableModes.contains(
                                PermissionChecker.AccessMode.SHIZUKU,
                            ) && !status.hasShizukuPermission -> "授权 Shizuku"
                            else -> "执行授权"
                        }
                    Text(text = btnText)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FilledTonalButton(
                    onClick = onManualSelectMode,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    Icon(imageVector = Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "手动选择模式", fontSize = 14.sp)
                }

                val lastModeText =
                    when (status.lastSelectedMode) {
                        PermissionChecker.AccessMode.ROOT -> "上次使用: Root 模式"
                        PermissionChecker.AccessMode.SHIZUKU -> "上次使用: Shizuku 模式"
                        PermissionChecker.AccessMode.NATIVE -> "上次使用: 普通模式"
                        else -> "推荐使用 Omni-Mode 智能检测"
                    }

                Text(
                    text = lastModeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
