package com.docsgpt.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DocsGptPurple = Color(0xFF6A4DFF)

private val LightColors = lightColorScheme(primary = DocsGptPurple)
private val DarkColors = darkColorScheme(primary = DocsGptPurple)

@Composable
fun DocsGptTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (useDarkTheme) DarkColors else LightColors,
        content = content,
    )
}
