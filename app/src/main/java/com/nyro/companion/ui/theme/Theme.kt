package com.nyro.companion.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Nyro {
    val Void = Color(0xFF04060C)
    val Night = Color(0xFF070B16)
    val Navy = Color(0xFF0B1630)
    val NavyLight = Color(0xFF13234A)
    val Blue = Color(0xFF2F7BFF)
    val Cyan = Color(0xFF38E8FF)
    val Ice = Color(0xFFE8FDFF)
    val Violet = Color(0xFF7A5CFF)
    val Amber = Color(0xFFFFB547)
    val Danger = Color(0xFFFF4D6D)
    val Ok = Color(0xFF3DDC97)
    val TextHi = Color(0xFFEAF2FF)
    val TextMid = Color(0xFF9DB0D3)
    val TextLow = Color(0xFF5E7097)
    val Glass = Color(0x1AFFFFFF)
    val GlassEdge = Color(0x2E9FD8FF)

    val background = Brush.verticalGradient(listOf(Color(0xFF060A18), Void, Color(0xFF030409)))
}

private val scheme = darkColorScheme(
    primary = Nyro.Cyan, onPrimary = Nyro.Void,
    secondary = Nyro.Blue, onSecondary = Color.White,
    tertiary = Nyro.Violet,
    background = Nyro.Void, onBackground = Nyro.TextHi,
    surface = Nyro.Night, onSurface = Nyro.TextHi,
    surfaceVariant = Nyro.Navy, onSurfaceVariant = Nyro.TextMid,
    surfaceContainer = Nyro.Navy, surfaceContainerHigh = Nyro.NavyLight,
    outline = Nyro.GlassEdge, error = Nyro.Danger,
)

private val type = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Light, fontSize = 34.sp, letterSpacing = 6.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 22.sp, letterSpacing = 0.5.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = 0.3.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.4.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.4.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.8.sp),
)

@Composable
fun NyroTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, typography = type, content = content)

/** Frosted-glass panel used throughout the UI. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    accent: Color = Nyro.GlassEdge,
    padding: Int = 16,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0x2A1B3366), Color(0x1A0B1630))))
            .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.05f)))), shape)
            .padding(padding.dp),
        content = content,
    )
}
