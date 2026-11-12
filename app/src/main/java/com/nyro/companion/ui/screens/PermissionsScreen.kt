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
import androidx.compose.foundation.layout.padding
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
        val steps = listOf(
            Triple("Display over other apps", p.overlay) { open(NyroApp.graph.mode.overlaySettingsIntent()) },
            Triple("Microphone", p.mic) { runtime.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) },
            Triple("Phone control (Accessibility)", p.accessibility) { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            Triple("Notifications", p.postNotif) { runtime.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) },
            Triple("Unrestricted battery", p.batteryUnrestricted) { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
        )
        val next = steps.withIndex().firstOrNull { !it.value.second }
        GlassCard(Modifier.fillMaxWidth(), accent = Nyro.Cyan.copy(alpha = 0.4f), padding = 14) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (next == null) {
                    Text("Setup complete", color = Nyro.Ok, style = MaterialTheme.typography.titleSmall)
                    Text("All required permissions are granted.", color = Nyro.TextMid, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Step ${next.index + 1} of ${steps.size}: ${next.value.first}", color = Nyro.TextHi, style = MaterialTheme.typography.titleSmall)
                    FilledTonalButton(onClick = { next.value.third() }) { Text("Do this step") }
                }
            }
        }
        Gap(12)
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
        if (!p.accessibility || !p.notifListener) RestrictedSettingsGuide(restrictedState(ctx), onOpenAppInfo = { open(appDetails) })
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

/**
 * Android 13+ "restricted settings" state for this app, read from the app-op the Settings app
 * itself checks. true = still blocked, false = allowed, null = can't tell on this build.
 */
fun restrictedState(ctx: Context): Boolean? = runCatching {
    val ops = ctx.getSystemService(android.app.AppOpsManager::class.java)
    val mode = ops.unsafeCheckOpNoThrow("android:access_restricted_settings", android.os.Process.myUid(), ctx.packageName)
    when (mode) {
        android.app.AppOpsManager.MODE_ALLOWED -> false
        android.app.AppOpsManager.MODE_ERRORED, android.app.AppOpsManager.MODE_IGNORED -> true
        else -> null
    }
}.getOrNull()

@Composable
private fun RestrictedSettingsGuide(blocked: Boolean?, onOpenAppInfo: () -> Unit) {
    val accent = if (blocked == false) Nyro.Ok else Nyro.Amber
    GlassCard(Modifier.fillMaxWidth(), accent = accent.copy(alpha = 0.5f), padding = 14) {
        Column {
            Text(
                when (blocked) {
                    true -> "Restricted settings are blocking NYRO"
                    false -> "Restricted settings look allowed"
                    null -> "Seeing “Restricted setting”?"
                },
                color = accent, style = MaterialTheme.typography.titleSmall,
            )
            Text(
                if (blocked == false) "Enable NYRO Phone Control and Notification access with the Grant buttons. If Android still shows “Restricted setting”, follow the steps from App info → ⋮ → Allow restricted settings."
                else "Android blocks Accessibility and Notification access for apps installed from an APK file. Only you can unlock it, once:",
                color = Nyro.TextMid, style = MaterialTheme.typography.bodySmall,
            )
            if (blocked != false) {
                Spacer(Modifier.size(6.dp))
                listOf(
                    "Tap Grant on “NYRO Phone Control” once and dismiss the “Restricted setting” dialog (Android only shows the unlock option after this attempt).",
                    "Tap “Open App info” below.",
                    "Tap ⋮ (three dots, top-right) → “Allow restricted settings”, then confirm with your PIN/fingerprint.",
                    "Come back here and tap Grant again. The switch now works for Phone Control and Notification access.",
                ).forEachIndexed { i, step ->
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text("${i + 1}.", color = accent, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(18.dp))
                        Text(step, color = Nyro.TextHi, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.size(8.dp))
                FilledTonalButton(onClick = onOpenAppInfo) { Text("Open App info") }
                Spacer(Modifier.size(6.dp))
                Text(
                    "No ⋮ menu? On HiOS open Settings → Apps → App management → NYRO, then ⋮. If your phone has no such option, install NYRO from a computer with “adb install nyro.apk”; apps installed that way are not restricted.",
                    color = Nyro.TextLow, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    Spacer(Modifier.size(8.dp))
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
