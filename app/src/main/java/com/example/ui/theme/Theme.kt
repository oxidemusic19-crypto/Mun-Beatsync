package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val SyncBeatDarkColorScheme = darkColorScheme(
    primary = ElectricPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2E1065),
    onPrimaryContainer = Color(0xFFEDE9FE),
    secondary = NeonBlue,
    onSecondary = Color(0xFF04131E),
    secondaryContainer = Color(0xFF0C2B42),
    onSecondaryContainer = Color(0xFFE0F2FE),
    tertiary = VividViolet,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF3B0764),
    onTertiaryContainer = Color(0xFFF3E8FF),
    background = ObsidianBlack,
    onBackground = TextPrimary,
    surface = GraphiteDeep,
    onSurface = TextPrimary,
    surfaceVariant = GraphiteSurface,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = ObsidianBlack,
    surfaceContainerLow = GraphiteDeep,
    surfaceContainer = GraphiteSurface,
    surfaceContainerHigh = GraphiteElevated,
    surfaceContainerHighest = Color(0xFF25253D),
    outline = GlassBorder,
    outlineVariant = Color(0x1FFFFFFF),
    error = SyncCoral,
    onError = Color.White
)

val SyncBeatShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun SyncBeatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SyncBeatDarkColorScheme,
        typography = SyncBeatTypography,
        shapes = SyncBeatShapes,
        content = content
    )
}
