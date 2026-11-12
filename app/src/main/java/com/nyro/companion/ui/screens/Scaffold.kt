package com.nyro.companion.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nyro.companion.ui.theme.Nyro

@Composable
fun ScreenFrame(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Nyro.TextHi) }
            else Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Nyro.TextHi, modifier = Modifier.weight(1f))
            actions()
        }
        val body = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)
        if (scroll) {
            Column(body.verticalScroll(rememberScrollState()).navigationBarsPadding()) { content(); Spacer(Modifier.padding(16.dp)) }
        } else {
            Column(body.navigationBarsPadding()) { content() }
        }
    }
}

@Composable
fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, color = Nyro.TextMid, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.45f))
        Text(v, color = Nyro.TextHi, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.55f))
    }
}

@Composable
fun Gap(h: Int = 12) = Box(Modifier.padding(top = h.dp))
