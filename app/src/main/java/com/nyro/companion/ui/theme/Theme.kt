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
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyro.companion.NyroApp
import com.nyro.companion.R

/**
 * NYRO palette from the design spec. Dark is the default; light is the switchable alternative.
 * Accents (terracotta, steel, olive, teal) are the same in both modes.
 */
class NyroPalette(
    val primary: Color,
    val background: Color,
    val surface: Color,
    val text: Color,
    val muted: Color,
    val border: Color,
    val success: Color,
    val warning: Color,
    val destructive: Color,
    val terracotta: Color,
    val steel: Color,
    val olive: Color,
    val teal: Color,
    val onPrimary: Color,
)

private val DarkPalette = NyroPalette(
    primary = Color(0xFF4A8FE0), background = Color(0xFF181818), surface = Color(0xFF1D1D1D),
    text = Color(0xFFEBEBEB), muted = Color(0xFF9B9B9B), border = Color(0xFF303030),
    success = Color(0xFFA9BF7E), warning = Color(0xFFD9A55B), destructive = Color(0xFFD06F5E),
    terracotta = Color(0xFFD97756), steel = Color(0xFF66839A), olive = Color(0xFF6B8440), teal = Color(0xFFB3CBCA),
    onPrimary = Color.White,
)

private val LightPalette = NyroPalette(
    primary = Color(0xFF3B82D6), background = Color(0xFFFAFAFA), surface = Color(0xFFF2F2F2),
    text = Color(0xFF141413), muted = Color(0xFF5E5D59), border = Color(0xFFDEDEDE),
    success = Color(0xFF6B8440), warning = Color(0xFFB7832E), destructive = Color(0xFFB5503F),
    terracotta = Color(0xFFD97756), steel = Color(0xFF66839A), olive = Color(0xFF6B8440), teal = Color(0xFFB3CBCA),
    onPrimary = Color.White,
)

/**
 * Named colors used across the app. Values follow the active palette, which [NyroTheme] selects.
 * Names are kept from the first version of the UI; each maps onto a token from the spec.
 */
object Nyro {
    private var p: NyroPalette = DarkPalette

    internal fun use(dark: Boolean) { p = if (dark) DarkPalette else LightPalette }

    val Void: Color get() = p.background
    val Night: Color get() = p.surface
    val Navy: Color get() = p.surface
    val NavyLight: Color get() = p.surface
    val Blue: Color get() = p.primary
    val Cyan: Color get() = p.primary
    val Ice: Color get() = p.teal
    val Violet: Color get() = p.steel
    val Amber: Color get() = p.warning
    val Danger: Color get() = p.destructive
    val Ok: Color get() = p.success
    val TextHi: Color get() = p.text
    val TextMid: Color get() = p.muted
    val TextLow: Color get() = p.muted
    val Glass: Color get() = p.surface
    val GlassEdge: Color get() = p.border
    val Terracotta: Color get() = p.terracotta
    val Steel: Color get() = p.steel
    val Olive: Color get() = p.olive
    val Teal: Color get() = p.teal
    val OnPrimary: Color get() = p.onPrimary
    val background: Brush get() = SolidColor(p.background)
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
    displaySmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, letterSpacing = 0.sp),
    headlineSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontFamily = InterFamily, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = InterFamily, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = InterFamily, fontSize = 12.sp, lineHeight = 17.sp),
    labelSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.6.sp),
    labelMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.2.sp),
)

/** Base radius 12dp; cards and panels 14–16dp. */
private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

/**
 * Applies the palette. [dark] defaults to the user's setting (dark unless they switched).
 * The content is keyed on the mode, so switching themes recomposes every screen with new colors.
 */
@Composable
fun NyroTheme(
    dark: Boolean = NyroApp.graph.settings.current.darkTheme,
    content: @Composable () -> Unit,
) {
    Nyro.use(dark)
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.primary, onPrimary = p.onPrimary, secondary = p.steel, tertiary = p.terracotta,
            background = p.background, onBackground = p.text, surface = p.surface, onSurface = p.text,
            surfaceVariant = p.surface, onSurfaceVariant = p.muted, surfaceContainer = p.surface,
            surfaceContainerHigh = p.surface, outline = p.border, outlineVariant = p.border,
            error = p.destructive, onError = Color.White,
        )
    } else {
        lightColorScheme(
            primary = p.primary, onPrimary = p.onPrimary, secondary = p.steel, tertiary = p.terracotta,
            background = p.background, onBackground = p.text, surface = p.surface, onSurface = p.text,
            surfaceVariant = p.surface, onSurfaceVariant = p.muted, surfaceContainer = p.surface,
            surfaceContainerHigh = p.surface, outline = p.border, outlineVariant = p.border,
            error = p.destructive, onError = Color.White,
        )
    }
    key(dark) {
        MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes, content = content)
    }
}

/** Card or panel: flat surface, 1px border, no shadow. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
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
