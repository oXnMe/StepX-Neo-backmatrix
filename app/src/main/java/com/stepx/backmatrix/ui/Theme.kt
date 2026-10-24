package com.stepx.backmatrix.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 一套偏克制的深色配色：近黑底 + 紫色强调，尽量把注意力留给点阵本身。 */
object Palette {
    val bg = Color(0xFF08080A)
    val surface = Color(0xFF121215)
    val surfaceAlt = Color(0xFF18181D)
    val border = Color(0xFF26262E)
    val borderStrong = Color(0xFF35353F)
    val text = Color(0xFFF3F3F7)
    val muted = Color(0xFF9B9BA7)
    val dim = Color(0xFF6C6C79)
    val accent = Color(0xFF8B6BFF)
    val accentSoft = Color(0xFF241C3D)
    val ok = Color(0xFF57D9A3)
    val warn = Color(0xFFF2B33D)
    val danger = Color(0xFFFF6B7A)
    val dotOn = Color(0xFFF1EEFF)
    val dotOff = Color(0xFF141419)
}

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)
)

@Composable
fun PzTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.accent,
            onPrimary = Color.White,
            background = Palette.bg,
            onBackground = Palette.text,
            surface = Palette.surface,
            onSurface = Palette.text,
            surfaceVariant = Palette.surfaceAlt,
            onSurfaceVariant = Palette.muted,
            outline = Palette.border,
            error = Palette.danger
        ),
        typography = AppTypography,
        content = content
    )
}
