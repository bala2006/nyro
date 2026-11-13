package com.nyro.companion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nyro.companion.ui.theme.Nyro

/** Horizontal margin shared by every screen, so content lines up across tabs and detail pages. */
val PageMargin = 20.dp

/** Space the floating nav covers at the bottom of a tab screen. Zero on detail screens and while the keyboard is open. */
val LocalBottomInset = compositionLocalOf<Dp> { 0.dp }

/**
 * Screen frame. A top-level tab passes [onBack] = null and gets a large title. A detail page passes a back action and
 * gets a compact title bar.
 */
@Composable
fun ScreenFrame(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        if (onBack != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = PageMargin - 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Nyro.Glass).border(1.dp, Nyro.GlassEdge, CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Nyro.TextHi, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall, color = Nyro.TextHi, modifier = Modifier.weight(1f))
                actions()
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(start = PageMargin, end = 12.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.displaySmall, color = Nyro.TextHi, modifier = Modifier.weight(1f))
                actions()
            }
        }
        val body = Modifier.fillMaxWidth().weight(1f).padding(horizontal = PageMargin)
        if (scroll) {
            Column(body.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
                content()
                Spacer(Modifier.height(16.dp + LocalBottomInset.current))
            }
        } else {
            Column(body.navigationBarsPadding().padding(bottom = LocalBottomInset.current)) { content() }
        }
    }
}

@Composable
fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(k, color = Nyro.TextMid, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(v, color = Nyro.TextHi, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
fun Gap(h: Int = 12) = Box(Modifier.padding(top = h.dp))
