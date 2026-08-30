package com.zhuanz.autoleger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 设计 token：整套颜色集中在这里，深浅两套自动切换，页面代码只引用 colorScheme 角色

private val LightColors = lightColorScheme(
    primary = Color(0xFF007A6C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC4E8E1),
    onPrimaryContainer = Color(0xFF00382F),
    secondary = Color(0xFF4A635D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E0),
    onSecondaryContainer = Color(0xFF06201B),
    tertiary = Color(0xFF8A6B3F),
    tertiaryContainer = Color(0xFFF2E0C3),
    onTertiaryContainer = Color(0xFF2E2110),
    background = Color(0xFFF6F8F7),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFE3EBE8),
    onSurfaceVariant = Color(0xFF5B6663),
    outlineVariant = Color(0xFFEBEFED),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFF2B3230),
    inverseOnSurface = Color(0xFFEDF2F0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6BD4C6),
    onPrimary = Color(0xFF003730),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = Color(0xFFA5F0E4),
    secondary = Color(0xFFB0CCC4),
    onSecondary = Color(0xFF1B3530),
    secondaryContainer = Color(0xFF324B45),
    onSecondaryContainer = Color(0xFFCCE8E0),
    tertiary = Color(0xFFE6C48D),
    tertiaryContainer = Color(0xFF4C3D22),
    onTertiaryContainer = Color(0xFFF2E0C3),
    background = Color(0xFF0E1312),
    onBackground = Color(0xFFDDE4E1),
    surface = Color(0xFF141817),
    onSurface = Color(0xFFDDE4E1),
    surfaceVariant = Color(0xFF232928),
    onSurfaceVariant = Color(0xFF9BA7A3),
    outlineVariant = Color(0xFF1D2322),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    inverseSurface = Color(0xFFDDE4E1),
    inverseOnSurface = Color(0xFF2B3230),
)

private val WarmLightColors = lightColorScheme(
    primary = Color(0xFFD97A2B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE3CC),
    onPrimaryContainer = Color(0xFF4A2500),
    secondary = Color(0xFF8A5B3A),
    secondaryContainer = Color(0xFFFFDCC4),
    onSecondaryContainer = Color(0xFF331B00),
    tertiary = Color(0xFF5D6B3F),
    background = Color(0xFFFAF7F3),
    onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFF2E8DE),
    onSurfaceVariant = Color(0xFF6B5F53),
    outlineVariant = Color(0xFFEFE9E2),
)

private val NightColors = darkColorScheme(
    primary = Color(0xFFE0B667),
    onPrimary = Color(0xFF1F1806),
    primaryContainer = Color(0xFF2B3348),
    onPrimaryContainer = Color(0xFFE0B667),
    secondary = Color(0xFF8FA3C7),
    secondaryContainer = Color(0xFF232B3D),
    onSecondaryContainer = Color(0xFFC6D4EC),
    tertiary = Color(0xFF7FD4C6),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFD9DEE8),
    surface = Color(0xFF11151D),
    onSurface = Color(0xFFD9DEE8),
    surfaceVariant = Color(0xFF1C2230),
    onSurfaceVariant = Color(0xFF8B94A8),
    outlineVariant = Color(0xFF161B26),
)

private val MonoLightColors = lightColorScheme(
    primary = Color(0xFF111113),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF4F4F5),
    onPrimaryContainer = Color(0xFF111113),
    secondary = Color(0xFF3F3F46),
    secondaryContainer = Color(0xFFF4F4F5),
    onSecondaryContainer = Color(0xFF111113),
    tertiary = Color(0xFF3F3F46),
    tertiaryContainer = Color(0xFFF4F4F5),
    onTertiaryContainer = Color(0xFF111113),
    background = Color(0xFFFCFCFD),
    onBackground = Color(0xFF111113),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111113),
    surfaceVariant = Color(0xFFF4F4F5),
    onSurfaceVariant = Color(0xFF71717A),
    outlineVariant = Color(0xFFE8E8EC),
    error = Color(0xFFBA1A1A),
)

private val MonoDarkColors = darkColorScheme(
    primary = Color(0xFFF4F4F5),
    onPrimary = Color(0xFF111113),
    primaryContainer = Color(0xFF26262A),
    onPrimaryContainer = Color(0xFFF4F4F5),
    secondary = Color(0xFFC8C8CE),
    secondaryContainer = Color(0xFF232327),
    onSecondaryContainer = Color(0xFFE4E4E9),
    tertiary = Color(0xFFC8C8CE),
    tertiaryContainer = Color(0xFF232327),
    onTertiaryContainer = Color(0xFFE4E4E9),
    background = Color(0xFF0C0C0E),
    onBackground = Color(0xFFE4E4E9),
    surface = Color(0xFF141417),
    onSurface = Color(0xFFE4E4E9),
    surfaceVariant = Color(0xFF1F1F23),
    onSurfaceVariant = Color(0xFF9B9BA3),
    outlineVariant = Color(0xFF232327),
    error = Color(0xFFFFB4AB),
)

@Composable
fun AutoLedgerTheme(content: @Composable () -> Unit) {
    val colorScheme = when (UiVariantState.effective) {
        UiVariant.D -> NightColors
        UiVariant.E -> WarmLightColors
        UiVariant.F -> if (isSystemInDarkTheme()) MonoDarkColors else MonoLightColors
        else -> if (isSystemInDarkTheme()) DarkColors else LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
