package com.zhuanz.autoleger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val WarmLight = lightColorScheme(
    primary = Color(0xFF9B4F21), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE6D5), onPrimaryContainer = Color(0xFF542B14),
    secondary = Color(0xFF6B7152), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEBEEDC), onSecondaryContainer = Color(0xFF343A26),
    tertiary = Color(0xFF886A58), tertiaryContainer = Color(0xFFF2E4DB),
    background = Color(0xFFFAF6F0), onBackground = Color(0xFF302720),
    surface = Color(0xFFFFFDF9), onSurface = Color(0xFF302720),
    surfaceContainerLow = Color(0xFFFFFDF9), surfaceContainer = Color(0xFFF4EDE5),
    surfaceVariant = Color(0xFFF2E9DF), onSurfaceVariant = Color(0xFF74665A),
    outline = Color(0xFF8A786A), outlineVariant = Color(0xFFE8DCD0),
    error = Color(0xFFBA1A1A),
)
private val WarmDark = darkColorScheme(
    primary = Color(0xFFF4B58C), onPrimary = Color(0xFF542B14),
    primaryContainer = Color(0xFF583924), onPrimaryContainer = Color(0xFFFFE6D5),
    secondary = Color(0xFFCAD2AF), onSecondary = Color(0xFF303821),
    secondaryContainer = Color(0xFF414832), onSecondaryContainer = Color(0xFFEBEEDC),
    tertiary = Color(0xFFD8BCA8), tertiaryContainer = Color(0xFF4D3B30),
    background = Color(0xFF191510), onBackground = Color(0xFFF0E6DC),
    surface = Color(0xFF231D17), onSurface = Color(0xFFF0E6DC),
    surfaceContainerLow = Color(0xFF231D17), surfaceContainer = Color(0xFF2D251E),
    surfaceVariant = Color(0xFF352B22), onSurfaceVariant = Color(0xFFCABBAC),
    outline = Color(0xFFA18F7D), outlineVariant = Color(0xFF493B2E),
)
@Composable
fun AutoLedgerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) WarmDark else WarmLight,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp)),
        content = content,
    )
}
