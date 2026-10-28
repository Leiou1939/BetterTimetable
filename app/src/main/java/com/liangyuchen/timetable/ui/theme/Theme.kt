package com.liangyuchen.timetable.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Blue = Color(0xFF2C6BE4)
private val BlueDark = Color(0xFF1B4FBE)
private val BlueContainer = Color(0xFFE3ECFF)
private val Surface = Color(0xFFF7F9FF)
private val Outline = Color(0xFFC9D3E6)

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = BlueContainer,
    onPrimaryContainer = BlueDark,
    secondary = Color(0xFF0E8E7A),
    onSecondary = Color.White,
    background = Surface,
    onBackground = Color(0xFF11151C),
    surface = Color.White,
    onSurface = Color(0xFF11151C),
    surfaceVariant = Color(0xFFEDF1FA),
    onSurfaceVariant = Color(0xFF4A5468),
    outline = Outline,
    error = Color(0xFFB3261E),
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 13.sp),
    bodySmall = TextStyle(fontSize = 11.sp),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun TimetableTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
