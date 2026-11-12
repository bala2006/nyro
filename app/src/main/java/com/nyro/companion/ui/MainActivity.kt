package com.nyro.companion.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nyro.companion.NyroApp
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
    const val HOME = "home"; const val CHAT = "chat"; const val TASKS = "tasks"; const val DEVICE = "device"
    const val HISTORY = "history"; const val MEMORY = "memory"; const val SETTINGS = "settings"
    const val PROVIDER = "provider"; const val PERMISSIONS = "permissions"
}

@Composable
private fun NyroNav() {
    val nav = rememberNavController()
    val go: (String) -> Unit = { nav.navigate(it) { launchSingleTop = true } }
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(
        nav, startDestination = Routes.HOME,
        enterTransition = { fadeIn() }, exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() }, popExitTransition = { fadeOut() },
    ) {
        composable(Routes.HOME) { DashboardScreen(go) }
        composable(Routes.CHAT) { ChatScreen(back, go) }
        composable(Routes.TASKS) { TasksScreen(back) }
        composable(Routes.DEVICE) { DeviceScreen(back) }
        composable(Routes.HISTORY) { HistoryScreen(back) { NyroApp.graph.agent.openConversation(it); go(Routes.CHAT) } }
        composable(Routes.MEMORY) { MemoryScreen(back) }
        composable(Routes.SETTINGS) { SettingsScreen(back, go) }
        composable(Routes.PROVIDER) { ProviderScreen(back) }
        composable(Routes.PERMISSIONS) { PermissionsScreen(back) }
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
