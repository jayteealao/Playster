package com.github.jayteealao.playster.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Cyan500,
    onPrimary = White,
    primaryContainer = Gray100,
    onPrimaryContainer = Gray900,
    secondary = Purple500,
    onSecondary = White,
    background = White,
    onBackground = Gray900,
    surface = Gray50,
    onSurface = Gray900,
    surfaceVariant = Gray100,
    onSurfaceVariant = Gray600,
    outline = Gray200,
    error = Error,
    onError = White
)

private val DarkColorScheme = darkColorScheme(
    primary = Cyan500,
    onPrimary = Gray900,
    primaryContainer = Cyan600,
    onPrimaryContainer = White,
    secondary = Purple500,
    onSecondary = White,
    background = Gray900,
    onBackground = White,
    surface = Color(0xFF1B2838),
    onSurface = White,
    surfaceVariant = Color(0xFF253447),
    onSurfaceVariant = Gray400,
    outline = Color(0xFF253447),
    error = Error,
    onError = White
)

@Composable
fun PlaysterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
