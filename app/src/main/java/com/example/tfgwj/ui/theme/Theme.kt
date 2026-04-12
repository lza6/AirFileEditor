package com.example.tfgwj.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
    darkColorScheme(
        primary = Blue80,
        onPrimary = Blue20,
        primaryContainer = Blue30,
        onPrimaryContainer = Blue90,
        secondary = Orange80,
        onSecondary = Orange20,
        secondaryContainer = Orange30,
        onSecondaryContainer = Orange90,
        tertiary = Green80,
        onTertiary = Green20,
        background = Grey10,
        onBackground = Grey90,
        surface = Grey20,
        onSurface = Grey90,
        surfaceVariant = Grey30,
        onSurfaceVariant = Grey80,
        error = Red80,
        onError = Red20,
        errorContainer = Red30,
        onErrorContainer = Red90,
    )

private val LightColorScheme =
    lightColorScheme(
        primary = Blue40,
        onPrimary = Grey99,
        primaryContainer = Blue90,
        onPrimaryContainer = Blue10,
        secondary = Orange40,
        onSecondary = Grey99,
        secondaryContainer = Orange90,
        onSecondaryContainer = Orange10,
        tertiary = Green40,
        onTertiary = Grey99,
        background = Grey99,
        onBackground = Grey10,
        surface = Grey95,
        onSurface = Grey10,
        surfaceVariant = Grey90,
        onSurfaceVariant = Grey30,
        error = Red40,
        onError = Grey99,
        errorContainer = Red90,
        onErrorContainer = Red10,
    )

@Composable
fun TfgwjTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
