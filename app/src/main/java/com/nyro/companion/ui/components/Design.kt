package com.nyro.companion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nyro.companion.ui.theme.Nyro

/** The color that stands for what NYRO is doing. The status pill, hero glow and Chat header all use it. */
fun mascotColor(s: MascotState): Color = when (s) {
    MascotState.IDLE, MascotState.LISTENING, MascotState.EXECUTING -> Nyro.Blue
    MascotState.THINKING -> Nyro.Violet
    MascotState.SPEAKING -> Nyro.Teal
    MascotState.ERROR -> Nyro.Danger
    MascotState.ATTENTION -> Nyro.Amber
    MascotState.HAPPY -> Nyro.Ok
    MascotState.CONFUSED -> Nyro.Terracotta
}

/** An icon on a soft tinted square, used at the start of rows and on tiles. */
@Composable
fun IconBubble(icon: ImageVector, tint: Color = Nyro.Blue, size: Dp = 40.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(tint.copy(alpha = if (Nyro.isDark) 0.16f else 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** A small rounded label with a status dot. */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Nyro.TextHi, maxLines = 1)
    }
}

/**
 * One row inside a card: optional icon, a title with an optional subtitle, and a trailing control. A clickable row
 * without its own trailing control shows a chevron.
 */
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    tint: Color = Nyro.Blue,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconBubble(icon, tint)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Nyro.TextHi)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Nyro.TextMid)
        }
        when {
            trailing != null -> { Spacer(Modifier.width(12.dp)); trailing() }
            onClick != null -> Icon(Icons.Outlined.ChevronRight, null, tint = Nyro.TextLow)
        }
    }
}

/** Hairline between rows of one card, inset to line up with the row text. */
@Composable
fun RowDivider(inset: Dp = 16.dp) = HorizontalDivider(Modifier.padding(start = inset), thickness = 1.dp, color = Nyro.GlassEdge)
