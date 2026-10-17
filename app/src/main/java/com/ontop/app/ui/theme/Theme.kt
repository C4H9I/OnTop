package com.ontop.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Sage,
    onPrimary = Ink,
    primaryContainer = Forest,
    onPrimaryContainer = WarmBackground,
    secondary = Sand,
    onSecondary = Ink,
    tertiary = Terracotta,
    onTertiary = Ink,
    background = Color(0xFF1C251E),
    onBackground = WarmBackground,
    surface = Color(0xFF232E25),
    onSurface = WarmBackground,
    surfaceVariant = Forest,
    onSurfaceVariant = Sand,
    outline = Sage
)

private val LightColorScheme = lightColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    primaryContainer = Sage,
    onPrimaryContainer = Ink,
    secondary = Forest,
    onSecondary = Color.White,
    secondaryContainer = Sand,
    onSecondaryContainer = Ink,
    tertiary = Color(0xFF754936),
    onTertiary = Color.White,
    tertiaryContainer = Terracotta,
    onTertiaryContainer = Ink,
    background = WarmBackground,
    onBackground = Ink,
    surface = WarmBackground,
    onSurface = Ink,
    surfaceVariant = Sand,
    onSurfaceVariant = Forest,
    outline = Color(0xFF707C68)
)

@Composable
fun OnTopTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Keep the OnTop palette independent of the phone wallpaper.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
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
        content = content
    )
}
