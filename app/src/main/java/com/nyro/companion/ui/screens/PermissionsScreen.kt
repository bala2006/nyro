package com.nyro.companion.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nyro.companion.NyroApp
import com.nyro.companion.access.NyroAccessibilityService
import com.nyro.companion.tools.hasMediaPermission
import com.nyro.companion.ui.components.SectionLabel
import com.nyro.companion.ui.rememberResumeTick
import com.nyro.companion.ui.theme.GlassCard
import com.nyro.companion.ui.theme.Nyro

data class PermStatus(
    val overlay: Boolean, val accessibility: Boolean, val mic: Boolean, val postNotif: Boolean,
    val notifListener: Boolean, val media: Boolean, val batteryUnrestricted: Boolean,
)

fun readPerms(ctx: Context, accessibilityOn: Boolean): PermStatus {
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    return PermStatus(
        overlay = Settings.canDrawOverlays(ctx),
        accessibility = accessibilityOn,
        mic = granted(Manifest.permission.RECORD_AUDIO),
        postNotif = granted(Manifest.permission.POST_NOTIFICATIONS),
        notifListener = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName),
        media = hasMediaPermission(),
        batteryUnrestricted = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName),
    )
}

@Composable
fun rememberPerms(): PermStatus {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    val acc by NyroAccessibilityService.instance.collectAsStateWithLifecycle()
    return remember(tick, acc) { readPerms(ctx, acc != null) }
}

@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val p = rememberPerms()
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        runCatching { com.nyro.companion.overlay.NyroModeService.instance?.refreshForeground() }
    }
    fun open(i: Intent) = runCatching { ctx.startActivity(i) }
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

    ScreenFrame("Permissions", onBack) {
        Text(
            "NYRO only asks for what each capability needs. Everything runs on this phone; only your requests, and screen content when a task needs it, go to your Azure endpoint.",
            color = Nyro.TextMid, style = MaterialTheme.typography.bodyMedium,
        )
        Gap(16)
        SectionLabel("Required for NYRO Mode")
        PermRow("Display over other apps", "Shows the floating NYRO companion above other apps.", p.overlay) {
            open(NyroApp.graph.mode.overlaySettingsIntent())
        }
        PermRow("Microphone", "Voice commands. Speech is recognized on-device when supported.", p.mic) {
            runtime.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
        PermRow("Notifications", "Shows the persistent NYRO Mode notification with Stop controls.", p.postNotif) {
            runtime.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
        Gap(12)
        SectionLabel("Phone control")
        PermRow(
            "NYRO Phone Control (Accessibility)",
            "Reads the screen and performs taps, swipes, scrolling and typing you ask for. Also enables screenshots without extra prompts.",
            p.accessibility,
        ) { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        GlassCard(Modifier.fillMaxWidth(), accent = Nyro.Amber.copy(alpha = 0.4f), padding = 12) {
            Column {
                Text("“Restricted setting” message?", color = Nyro.Amber, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Android 13+ blocks Accessibility and Notification access for apps installed from an APK until you allow it: open App info → ⋮ (top right) → Allow restricted settings, then enable NYRO again.",
                    color = Nyro.TextMid, style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(6.dp))
                FilledTonalButton(onClick = { open(appDetails) }) { Text("Open App info") }
            }
        }
        Gap(12)
        SectionLabel("Optional")
        PermRow("Notification access", "Lets NYRO read and summarize notifications and reply through them.", p.notifListener) {
            open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        PermRow("Photos, videos & audio", "Search, open and share your media files.", p.media) {
            runtime.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO))
        }
        PermRow("Unrestricted battery", "Prevents the system from killing NYRO Mode in the background (recommended on TECNO/HiOS).", p.batteryUnrestricted) {
            open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        Gap(12)
        SectionLabel("Screen capture")
        Text(
            "With Phone Control on, NYRO captures the screen through the accessibility screenshot API. Without it, Android shows its own screen-capture consent dialog the first time a task needs the screen in each NYRO Mode session. Secure screens (banking, DRM video, password fields) cannot be captured.",
            color = Nyro.TextMid, style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun PermRow(title: String, desc: String, granted: Boolean, onGrant: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), padding = 14, accent = if (granted) Nyro.Ok.copy(alpha = 0.35f) else Nyro.GlassEdge) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                if (granted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                tint = if (granted) Nyro.Ok else Nyro.TextLow, modifier = Modifier.size(22.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(title, color = Nyro.TextHi, style = MaterialTheme.typography.titleSmall)
                Text(desc, color = Nyro.TextMid, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(4.dp))
            if (!granted) FilledTonalButton(onClick = onGrant) { Text("Grant") }
        }
    }
    Spacer(Modifier.size(8.dp))
}
