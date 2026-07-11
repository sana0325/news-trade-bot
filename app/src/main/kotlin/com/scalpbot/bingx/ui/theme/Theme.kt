package com.scalpbot.bingx.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val ScalpBotDarkColorScheme = darkColorScheme(
    primary = AccentGreen,
    onPrimary = BackgroundDark,
    secondary = AccentGreenDim,
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceMutedDark,
    error = LossRed,
)

/**
 * Додаток завжди у темній темі — це свідоме рішення продукту (скальп-бот
 * читається на екрані вночі так само, як і вдень), тому системну світлу тему
 * ігноруємо навмисно.
 */
@Composable
fun ScalpBotTheme(content: @Composable () -> Unit) {
    val colorScheme = ScalpBotDarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ScalpBotTypography,
        content = content,
    )
}
