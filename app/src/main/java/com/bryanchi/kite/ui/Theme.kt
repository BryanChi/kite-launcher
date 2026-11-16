package com.bryanchi.kite.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFFFB454)
val Bg = Color(0xFF0E0F12)
val Dim = Color(0xFF9AA0A6)
val Ink = Color(0xFFECEDEE)

@Composable
fun KiteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color(0xFF1A1200),
            background = Bg,
            onBackground = Ink,
            surface = Color(0xFF16181D),
            onSurface = Ink,
            surfaceVariant = Color(0xFF22252B),
            onSurfaceVariant = Dim,
        ),
    ) {
        CompositionLocalProvider(LocalContentColor provides Ink, content = content)
    }
}
