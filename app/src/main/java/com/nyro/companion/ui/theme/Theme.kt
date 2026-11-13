package com.nyro.companion.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyro.companion.NyroApp
import com.nyro.companion.R

/**
 * NYRO palette. The blues come from the liquid-drop mascot so the app and the character read as one thing.
 * [raised] is the lighter top of a card, [primarySoft] a tonal fill behind icons and selected chips, and [sheen] the
 * light catching a card's top edge.
 */
class NyroPalette(
    val dark: Boolean,
    val primary: Color,
    val primarySoft: Color,
    val background: Color,
    val backgroundTop: Color,
    val surface: Color,
    val raised: Color,
    val text: Color,
    val muted: Color,
    val border: Color,
    val sheen: Color,
    val success: Color,
    val warning: Color,
    val destructive: Color,
    val terracotta: Color,
    val violet: Color,
    val olive: Color,
    val teal: Color,
    val ice: Color,
    val onPrimary: Color,
)

// Contrast steps, dark: background < surface (cards) < raised (fields, chips, controls), each border clearly visible.
private val DarkPalette = NyroPalette(
    dark = true,
    primary = Color(0xFF3B86F7), primarySoft = Color(0xFF203A6E),
    background = Color(0xFF080C17), backgroundTop = Color(0xFF0E1830),
    surface = Color(0xFF161F35), raised = Color(0xFF212C48),
    text = Color(0xFFF1F4FB), muted = Color(0xFFAAB5CD), border = Color(0xFF37466A), sheen = Color(0x33FFFFFF),
    success = Color(0xFF6FD6A6), warning = Color(0xFFF2C46B), destructive = Color(0xFFFF8A7A),
    terracotta = Color(0xFFFFA77F), violet = Color(0xFFA9A6FF), olive = Color(0xFF9DBA6A), teal = Color(0xFF7FE3D0),
    ice = Color(0xFFBFE3FF), onPrimary = Color.White,
)

// Contrast steps, light: a tinted gray-blue page, white cards, and borders dark enough to see on both.
private val LightPalette = NyroPalette(
    dark = false,
    primary = Color(0xFF2160E6), primarySoft = Color(0xFFD9E5FF),
    background = Color(0xFFE9EEF7), backgroundTop = Color(0xFFDDE6F6),
    surface = Color(0xFFFFFFFF), raised = Color(0xFFEFF3FA),
    text = Color(0xFF0B1328), muted = Color(0xFF4A5671), border = Color(0xFFC3CDDF), sheen = Color(0xFFFFFFFF),
    success = Color(0xFF15926A), warning = Color(0xFFA86B12), destructive = Color(0xFFD23F3F),
    terracotta = Color(0xFFC2603E), violet = Color(0xFF6A5AE0), olive = Color(0xFF5E7A2E), teal = Color(0xFF0E8C80),
    ice = Color(0xFF2B6CB0), onPrimary = Color.White,
)

/**
 * Named colors used across the app. Values follow the active palette, which [NyroTheme] selects. The older names
 * (Void, Navy, Cyan, Glass) are kept so every screen maps onto the same tokens.
 */
object Nyro {
    // Snapshot state: a composable that reads a color recomposes when the palette changes, with no keyed subtree.
    private val palette = mutableStateOf(DarkPalette)
    private val p: NyroPalette get() = palette.value

    internal fun use(dark: Boolean) {
        val next = if (dark) DarkPalette else LightPalette
        if (palette.value !== next) palette.value = next
    }

    val isDark: Boolean get() = p.dark
    val Void: Color get() = p.background
    val Night: Color get() = p.surface
    val Navy: Color get() = p.raised
    val NavyLight: Color get() = p.raised
    val Raised: Color get() = p.raised
    val Blue: Color get() = p.primary
    val Cyan: Color get() = p.primary
    val PrimarySoft: Color get() = p.primarySoft
    val Ice: Color get() = p.ice
    val Violet: Color get() = p.violet
    val Amber: Color get() = p.warning
    val Danger: Color get() = p.destructive
    val Ok: Color get() = p.success
    val TextHi: Color get() = p.text
    val TextMid: Color get() = p.muted
    val TextLow: Color get() = p.muted
    val Glass: Color get() = p.surface
    val GlassEdge: Color get() = p.border
    val Sheen: Color get() = p.sheen
    val Terracotta: Color get() = p.terracotta
    val Steel: Color get() = p.violet
    val Olive: Color get() = p.olive
    val Teal: Color get() = p.teal
    val OnPrimary: Color get() = p.onPrimary
    /** Tint for the top of the page. The window itself is filled with [Void], so only this band is drawn on top. */
    fun topGlow(height: Float): Brush = Brush.verticalGradient(listOf(p.backgroundTop, p.background), startY = 0f, endY = height)

    /** Page color for a theme, for the window background before any palette is active. */
    fun page(dark: Boolean): Color = (if (dark) DarkPalette else LightPalette).background
}

// Inter (UI) and JetBrains Mono (code, IDs, metrics). Static cuts of the families, licensed under the OFL.
private val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
)

/** Monospace face for code, IDs and metrics. */
val MonoFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
)

private val type = Typography(
    displaySmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontFamily = InterFamily, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.6.sp),
)

/** Cards, rows and the hero share one radius; small controls use the smaller steps. */
val CardShape = RoundedCornerShape(20.dp)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = CardShape,
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Applies the palette. [dark] defaults to the user's setting (dark unless they switched).
 * Switching themes recomposes only what reads a color, and the content tree is not recreated. That keeps the nav
 * controller, the back stack and each screen's state on the current route.
 * Every color role is filled, so default Material switches, sliders, chips and buttons already match the palette.
 */
@Composable
fun NyroTheme(
    dark: Boolean = NyroApp.graph.settings.current.darkTheme,
    content: @Composable () -> Unit,
) {
    Nyro.use(dark)
    val p = if (dark) DarkPalette else LightPalette
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = p.primary, onPrimary = p.onPrimary,
        primaryContainer = p.primarySoft, onPrimaryContainer = p.text,
        secondary = p.violet, onSecondary = p.onPrimary,
        secondaryContainer = p.primarySoft, onSecondaryContainer = p.text,
        tertiary = p.teal, tertiaryContainer = p.primarySoft, onTertiaryContainer = p.text,
        background = p.background, onBackground = p.text,
        surface = p.surface, onSurface = p.text, surfaceVariant = p.raised, onSurfaceVariant = p.muted,
        surfaceContainerLowest = p.background, surfaceContainerLow = p.surface, surfaceContainer = p.surface,
        surfaceContainerHigh = p.raised, surfaceContainerHighest = p.raised, surfaceBright = p.raised, surfaceDim = p.background,
        inverseSurface = p.text, inverseOnSurface = p.background, inversePrimary = p.ice,
        outline = p.border, outlineVariant = p.border,
        error = p.destructive, onError = Color.White, errorContainer = p.destructive.copy(alpha = 0.16f), onErrorContainer = p.text,
        scrim = Color.Black,
    )
    MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes, content = content)
}

/**
 * Card or panel: a solid surface with a visible 1dp border. Solid fills keep cards distinct from the page and are the
 * cheapest thing for the GPU to draw. Pass [accent] to color the border for a state (connected, warning, active).
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardShape,
    accent: Color = Nyro.GlassEdge,
    padding: Int = 16,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .clip(shape)
            .background(Nyro.Glass)
            .border(1.dp, accent, shape)
            .padding(padding.dp),
        content = content,
    )
}
