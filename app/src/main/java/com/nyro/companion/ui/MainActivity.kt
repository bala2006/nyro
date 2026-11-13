package com.nyro.companion.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.Color as AndroidColor
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nyro.companion.NyroApp
import com.nyro.companion.ui.components.FloatingNav
import com.nyro.companion.ui.components.FloatingNavBottomGap
import com.nyro.companion.ui.components.FloatingNavClearance
import com.nyro.companion.ui.components.NavItem
import com.nyro.companion.ui.screens.ActivityScreen
import com.nyro.companion.ui.screens.CapabilitiesScreen
import com.nyro.companion.ui.screens.ChatScreen
import com.nyro.companion.ui.screens.DashboardScreen
import com.nyro.companion.ui.screens.DeviceScreen
import com.nyro.companion.ui.screens.HistoryScreen
import com.nyro.companion.ui.screens.LocalBottomInset
import com.nyro.companion.ui.screens.MemoryScreen
import com.nyro.companion.ui.screens.PermissionsScreen
import com.nyro.companion.ui.screens.ProviderScreen
import com.nyro.companion.ui.screens.SettingsScreen
import com.nyro.companion.ui.screens.TasksScreen
import com.nyro.companion.ui.theme.Nyro
import com.nyro.companion.ui.theme.NyroTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        applySystemBars(NyroApp.graph.settings.current.darkTheme)
        super.onCreate(savedInstanceState)
        NyroApp.graph.mode.restoreIfNeeded()
        setContent {
            val s by NyroApp.graph.settings.settings.collectAsStateWithLifecycle()
            LaunchedEffect(s.darkTheme) { applySystemBars(s.darkTheme) }
            NyroTheme(dark = s.darkTheme) {
                Box(Modifier.fillMaxSize().drawBehind { val h = size.height * 0.4f; drawRect(Nyro.topGlow(h), size = Size(size.width, h)) }) { NyroNav() }
            }
        }
    }

    /**
     * Status and navigation bar icons follow NYRO's theme, not the phone's. Otherwise a light NYRO page under a dark
     * system theme gets white clock and battery icons that disappear.
     */
    private fun applySystemBars(dark: Boolean) {
        val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        // The window fill is the page color, so Compose does not paint a second full-screen background over it.
        window.setBackgroundDrawable(ColorDrawable(Nyro.page(dark).toArgb()))
    }
}

object Routes {
    const val HOME = "home"; const val CHAT = "chat"; const val ACTIVITY = "activity"; const val SETTINGS = "settings"
    const val TASKS = "tasks"; const val DEVICE = "device"; const val HISTORY = "history"; const val MEMORY = "memory"
    const val PROVIDER = "provider"; const val PERMISSIONS = "permissions"; const val CAPABILITIES = "capabilities"
}

/** The four top-level destinations. Everything else opens on top of them with a back arrow. */
private val tabs = listOf(
    NavItem(Routes.HOME, "Home", Icons.Outlined.Home),
    NavItem(Routes.CHAT, "Chat", Icons.AutoMirrored.Outlined.Chat),
    NavItem(Routes.ACTIVITY, "Activity", Icons.Outlined.Insights),
    NavItem(Routes.SETTINGS, "Settings", Icons.Outlined.Tune),
)

/**
 * Screens fill the whole window. On tabs the floating nav is layered over them. The strip around the pill is a
 * translucent scrim, so content scrolling underneath stays partly visible. Tab screens read [LocalBottomInset] to keep
 * their last items above the pill.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NyroNav() {
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val go: (String) -> Unit = { nav.navigate(it) { launchSingleTop = true } }
    val back: () -> Unit = { nav.popBackStack() }
    // Hidden while the keyboard is open, so a text field is never covered by the pill.
    val pillShown = tabs.any { it.route == route } && !WindowInsets.isImeVisible

    CompositionLocalProvider(LocalBottomInset provides if (pillShown) FloatingNavClearance else 0.dp) {
        Box(Modifier.fillMaxSize()) {
            NavHost(
                nav, startDestination = Routes.HOME,
                modifier = Modifier.fillMaxSize(),
                enterTransition = { fadeIn() }, exitTransition = { fadeOut() },
                popEnterTransition = { fadeIn() }, popExitTransition = { fadeOut() },
            ) {
                composable(Routes.HOME) { DashboardScreen(go) }
                composable(Routes.CHAT) { ChatScreen(go) }
                composable(Routes.ACTIVITY) { ActivityScreen(go) }
                composable(Routes.SETTINGS) { SettingsScreen(go) }
                composable(Routes.TASKS) { TasksScreen(back) }
                composable(Routes.DEVICE) { DeviceScreen(back) }
                composable(Routes.HISTORY) { HistoryScreen(back) { NyroApp.graph.agent.openConversation(it); go(Routes.CHAT) } }
                composable(Routes.MEMORY) { MemoryScreen(back) }
                composable(Routes.PROVIDER) { ProviderScreen(back) }
                composable(Routes.PERMISSIONS) { PermissionsScreen(back) }
                composable(Routes.CAPABILITIES) { CapabilitiesScreen(back, go) }
            }
            if (pillShown) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Nyro.Void.copy(alpha = 0f), Nyro.Void.copy(alpha = 0.7f))))
                        .navigationBarsPadding()
                        .padding(bottom = FloatingNavBottomGap),
                    contentAlignment = Alignment.Center,
                ) {
                    FloatingNav(
                        items = tabs,
                        selectedRoute = route,
                        onSelect = { target ->
                            nav.navigate(target) {
                                popUpTo(Routes.HOME) { saveState = false }
                                launchSingleTop = true
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Increments every time the activity resumes, so permission states re-read after system settings. */
@Composable
fun rememberResumeTick(): Int {
    val owner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}
