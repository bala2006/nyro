package com.nyro.companion.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nyro.companion.ui.theme.Nyro

/** One top-level destination shown in [FloatingNav]. */
data class NavItem(val route: String, val label: String, val icon: ImageVector)

private val ItemSize = 44.dp
private val IconSize = 22.dp

/** Distance from the bottom of the window (above the navigation bar) to the pill. */
val FloatingNavBottomGap = 20.dp

/** Room a tab screen leaves above the navigation-bar inset: the pill, its bottom gap, and 8dp of breathing room. */
val FloatingNavClearance = ItemSize + 12.dp + FloatingNavBottomGap + 8.dp

/**
 * Floating pill navigation. The selected destination is a filled circle inside the pill. Items are icon-only, so each
 * icon carries its label as the content description for TalkBack.
 */
@Composable
fun FloatingNav(items: List<NavItem>, selectedRoute: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .shadow(elevation = 12.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(Nyro.Glass)
            .border(1.dp, Nyro.GlassEdge, CircleShape)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            val selected = item.route == selectedRoute
            val background by animateColorAsState(if (selected) Nyro.Blue else Color.Transparent, label = "navBackground")
            val tint by animateColorAsState(if (selected) Nyro.OnPrimary else Nyro.TextMid, label = "navTint")
            Box(
                Modifier
                    .size(ItemSize)
                    .clip(CircleShape)
                    .background(background)
                    .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(item.route) }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(IconSize))
            }
        }
    }
}
