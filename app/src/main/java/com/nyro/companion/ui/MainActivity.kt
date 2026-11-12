package com.nyro.companion.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.nyro.companion.ui.screens.ActivityScreen
import com.nyro.companion.ui.screens.ChatScreen
import com.nyro.companion.ui.screens.DashboardScreen
import com.nyro.companion.ui.screens.DeviceScreen
import com.nyro.companion.ui.screens.HistoryScreen
import com.nyro.companion.ui.screens.MemoryScreen
import com.nyro.companion.ui.screens.PermissionsScreen
import com.nyro.companion.ui.screens.ProviderScreen
import com.nyro.companion.ui.screens.SettingsScreen
import com.nyro.companion.ui.screens.TasksScreen
import com.nyro.companion.ui.theme.Nyro
import com.nyro.companion.ui.theme.NyroTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        NyroApp.graph.mode.restoreIfNeeded()
        setContent {
            val s by NyroApp.graph.settings.settings.collectAsStateWithLifecycle()
            NyroTheme(dark = s.darkTheme) {
                Box(Modifier.fillMaxSize().background(Nyro.background)) { NyroNav() }
            }
        }
    }
}

object Routes {
    const val HOME = "home"; const val CHAT = "chat"; const val ACTIVITY = "activity"; const val SETTINGS = "settings"
    const val TASKS = "tasks"; const val DEVICE = "device"; const val HISTORY = "history"; const val MEMORY = "memory"
    const val PROVIDER = "provider"; const val PERMISSIONS = "permissions"
}

/** The four top-level destinations. Everything else opens on top of them with a back arrow. */
private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Default.Home),
    Tab(Routes.CHAT, "Chat", Icons.AutoMirrored.Filled.Chat),
    Tab(Routes.ACTIVITY, "Activity", Icons.Default.Insights),
    Tab(Routes.SETTINGS, "Settings", Icons.Default.Tune),
)

@Composable
private fun NyroNav() {
    val nav = rememberNavController()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val go: (String) -> Unit = { nav.navigate(it) { launchSingleTop = true } }
    val back: () -> Unit = { nav.popBackStack() }
    val showTabs = tabs.any { it.route == route }

    Column(Modifier.fillMaxSize()) {
        NavHost(
            nav, startDestination = Routes.HOME,
            modifier = Modifier.fillMaxWidth().weight(1f),
            enterTransition = { fadeIn() }, exitTransition = { fadeOut() },
            popEnterTransition = { fadeIn() }, popExitTransition = { fadeOut() },
        ) {
            composable(Routes.HOME) { DashboardScreen(go) }
            composable(Routes.CHAT) { ChatScreen(back, go) }
            composable(Routes.ACTIVITY) { ActivityScreen(go) }
            composable(Routes.SETTINGS) { SettingsScreen(back, go) }
            composable(Routes.TASKS) { TasksScreen(back) }
            composable(Routes.DEVICE) { DeviceScreen(back) }
            composable(Routes.HISTORY) { HistoryScreen(back) { NyroApp.graph.agent.openConversation(it); go(Routes.CHAT) } }
            composable(Routes.MEMORY) { MemoryScreen(back) }
            composable(Routes.PROVIDER) { ProviderScreen(back) }
            composable(Routes.PERMISSIONS) { PermissionsScreen(back) }
        }
        if (showTabs) {
            NavigationBar(containerColor = Nyro.Glass, tonalElevation = 0.dp) {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = route == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(Routes.HOME) { saveState = false }
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(tab.icon, tab.label) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Nyro.OnPrimary, selectedTextColor = Nyro.TextHi,
                            indicatorColor = Nyro.Cyan, unselectedIconColor = Nyro.TextMid, unselectedTextColor = Nyro.TextMid,
                        ),
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
