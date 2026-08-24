@file:Suppress("MatchingDeclarationName")

package com.example.tfgwj.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * V14: 统一形状系统
 */
object TfgwjShapes {
    val card = RoundedCornerShape(16.dp)
    val button = RoundedCornerShape(12.dp)
    val dialog = RoundedCornerShape(20.dp)
    val chip = RoundedCornerShape(8.dp)
    val bottomSheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val input = RoundedCornerShape(8.dp)
    val badge = RoundedCornerShape(4.dp)
}
