package com.nyro.companion

import com.nyro.companion.agent.router.FastRouter
import com.nyro.companion.agent.router.LocalIntent
import com.nyro.companion.agent.router.LocalOutcome
import com.nyro.companion.agent.router.LocalReplies
import com.nyro.companion.agent.router.RouteDecision
import com.nyro.companion.agent.router.calls
import com.nyro.companion.device.AppIndex
import com.nyro.companion.device.AppInfo
import com.nyro.companion.device.AppMatch
import com.nyro.companion.device.AppTier
import com.nyro.companion.perf.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class FastRouterTest {
    private val installed = listOf(
        AppInfo("YouTube", "com.google.android.youtube"), AppInfo("YouTube Music", "com.google.android.apps.youtube.music"),
        AppInfo("Duolingo", "com.duolingo"), AppInfo("NextStep", "com.nextstep.app"), AppInfo("NeuroPath", "com.neuropath"),
        AppInfo("WhatsApp", "com.whatsapp"), AppInfo("WhatsApp Business", "com.whatsapp.w4b"),
        AppInfo("Google Maps", "com.google.android.apps.maps"), AppInfo("Settings", "com.android.settings"),
        AppInfo("Files", "com.google.android.apps.nbu.files"), AppInfo("Files", "com.samsung.files"),
        AppInfo("Chrome", "com.android.chrome"), AppInfo("Home Assistant", "io.homeassistant"),
    )
    private val index = AppIndex(installed)
    private val router = FastRouter({ q -> index.resolve(q) }, Clock { System.nanoTime() / 1_000_000 })

    private fun local(text: String): List<LocalIntent> {
        val d = router.route(text)
        assertTrue("expected local for \"$text\" but got $d", d is RouteDecision.Local)
        return (d as RouteDecision.Local).intents
    }

    private fun escalates(text: String) {
        val d = router.route(text)
        assertTrue("expected escalation for \"$text\" but got $d", d is RouteDecision.Escalate)
    }

    @Test fun opensInstalledApps() {
        assertEquals("YouTube", (local("open YouTube")[0] as LocalIntent.OpenApp).app.label)
        assertEquals("Duolingo", (local("Open Duolingo")[0] as LocalIntent.OpenApp).app.label)
        assertEquals("com.duolingo", (local("please launch the duolingo app for me")[0] as LocalIntent.OpenApp).app.pkg)
        assertEquals("Google Maps", (local("open maps")[0] as LocalIntent.OpenApp).app.label)
    }

    @Test fun speechRecognitionSplitsNames() {
        // "NextStep" often comes back as two words; "YouTube" as "you tube".
        assertEquals("NextStep", (local("open next step")[0] as LocalIntent.OpenApp).app.label)
        assertEquals("YouTube", (local("open you tube")[0] as LocalIntent.OpenApp).app.label)
    }

    @Test fun findAndOpenIsOpen() {
        assertEquals("NextStep", (local("find and open NextStep")[0] as LocalIntent.OpenApp).app.label)
        assertEquals("NextStep", (local("Find and open next step")[0] as LocalIntent.OpenApp).app.label)
    }

    @Test fun findAppAnswersFromTheCatalog() {
        val f = local("find NextStep")[0] as LocalIntent.FindApp
        assertTrue(f.match is AppMatch.Hit)
        val notThere = local("is Spotify installed")[0] as LocalIntent.FindApp
        assertTrue(notThere.match is AppMatch.None)
    }

    @Test fun ambiguousAppsEscalate() {
        escalates("open whatsapp business and whatsapp") // not one app
        escalates("open files")                           // two apps called Files
        escalates("open google")                          // prefix of several words, no exact app
    }

    @Test fun unknownAppsEscalate() {
        escalates("open spotify")
        escalates("open the thing I was using yesterday")
    }

    @Test fun deviceQuestionsAreLocal() {
        assertTrue(local("what's my battery percentage")[0] is LocalIntent.Battery)
        assertTrue(local("how much battery is left")[0] is LocalIntent.Battery)
        assertTrue(local("is my phone charging")[0] is LocalIntent.Battery)
        assertTrue(local("how much storage is left")[0] is LocalIntent.Storage)
        assertTrue(local("how much free RAM do I have")[0] is LocalIntent.Memory)
        assertTrue(local("is wifi on")[0] is LocalIntent.Network)
        assertTrue(local("what android version is this")[0] is LocalIntent.DeviceInfo)
        assertTrue(local("read my notifications")[0] is LocalIntent.Notifications)
        assertTrue(local("do I have any new notifications")[0] is LocalIntent.Notifications)
        assertTrue(local("what time is it")[0] is LocalIntent.Time)
    }

    @Test fun severalReadOnlyQuestionsInOneRequest() {
        val i = local("battery and storage")
        assertEquals(2, i.size)
        assertTrue(i[0] is LocalIntent.Battery && i[1] is LocalIntent.Storage)
    }

    @Test fun wordsOutsideTheVocabularyEscalate() {
        escalates("how do I improve battery life")
        escalates("battery saver")                      // a settings page request, not a reading
        escalates("why is my battery draining so fast")
        escalates("open youtube and play despacito")
        escalates("open chrome and search for cats")
        escalates("send a message to Pranav saying hi")
        escalates("what is the weather")
    }

    @Test fun mixedActionsEscalate() {
        escalates("turn on the flashlight and open youtube")
        escalates("read my notifications and reply to the first one")
    }

    @Test fun togglesNeedAnExplicitState() {
        assertEquals(true, (local("turn on the flashlight")[0] as LocalIntent.Flashlight).on)
        assertEquals(false, (local("flashlight off")[0] as LocalIntent.Flashlight).on)
        escalates("flashlight")                // on or off? do not guess
        escalates("turn on wifi")              // Android cannot toggle it directly; needs the model to drive the panel
    }

    @Test fun settingsPagesAreValidated() {
        assertEquals("wifi", (local("open wifi settings")[0] as LocalIntent.OpenSettings).page)
        assertEquals("battery_saver", (local("open battery saver settings")[0] as LocalIntent.OpenSettings).page)
        assertEquals("battery", (local("battery settings")[0] as LocalIntent.OpenSettings).page)
        assertEquals("main", (local("open settings")[0] as LocalIntent.OpenSettings).page)
        assertEquals("dnd", (local("open do not disturb settings")[0] as LocalIntent.OpenSettings).page)
        escalates("open wifi and bluetooth settings")
        escalates("open flux capacitor settings")
    }

    @Test fun navigationCommands() {
        assertTrue(local("go home")[0] is LocalIntent.Home)
        assertTrue(local("go back")[0] is LocalIntent.Back)
        assertTrue(local("open recent apps")[0] is LocalIntent.Recents)
        assertTrue(local("open quick settings")[0] is LocalIntent.QuickSettings)
        assertTrue(local("pull down the notification shade")[0] is LocalIntent.NotificationShade)
        assertTrue(local("lock the screen")[0] is LocalIntent.LockScreen)
        escalates("go back to the home screen and open youtube")
    }

    @Test fun timersAndVolumeValidateTheirNumbers() {
        assertEquals(300, (local("set a timer for 5 minutes")[0] as LocalIntent.Timer).seconds)
        assertEquals(5400, (local("set a timer for 1 hour and 30 minutes")[0] as LocalIntent.Timer).seconds)
        assertEquals(60, (local("start a 1 minute timer")[0] as LocalIntent.Timer).seconds)
        escalates("set a timer for 5")                 // no unit
        escalates("set a timer for 100 hours")         // out of range
        val v = local("set media volume to 40 percent")[0] as LocalIntent.Volume
        assertEquals("media", v.stream); assertEquals(40, v.percent)
        escalates("set volume to 400")
        escalates("volume up")
    }

    @Test fun mediaControlNeedsAClearTarget() {
        assertEquals("pause", (local("pause the music")[0] as LocalIntent.Media).action)
        assertEquals("next", (local("next song")[0] as LocalIntent.Media).action)
        assertEquals("pause", (local("pause")[0] as LocalIntent.Media).action)
        escalates("play despacito")
        escalates("next")
    }

    @Test fun nonLatinAndEmptyInputEscalates() {
        escalates("")
        escalates("   ")
        escalates("యూట్యూబ్ తెరవండి")
        escalates("बैटरी कितनी है")
    }

    @Test fun politenessIsIgnored() {
        assertTrue(local("Hey NYRO, can you please tell me the battery level")[0] is LocalIntent.Battery)
        assertEquals("YouTube", (local("could you open youtube please")[0] as LocalIntent.OpenApp).app.label)
    }

    @Test fun intentsMapToValidatedToolCalls() {
        val open = local("open duolingo")[0].calls().single()
        assertEquals("open_app", open.tool)
        assertEquals("com.duolingo", open.args["name"])
        assertEquals(true, open.args["brief"])
        assertEquals("get_battery", LocalIntent.Battery.calls().single().tool)
        assertTrue(local("what time is it")[0].calls().isEmpty())
    }

    @Test fun routingIsFast() {
        val big = AppIndex((1..400).map { AppInfo("App Number $it", "com.example.app$it") } + installed)
        val r = FastRouter({ q -> big.resolve(q) }, Clock { System.nanoTime() / 1_000_000 })
        repeat(200) { r.route("open duolingo"); r.route("what's my battery") } // warm up the JIT
        val phrases = listOf("open duolingo", "what's my battery percentage", "how do I improve battery life", "find and open nextstep", "read my notifications")
        val times = ArrayList<Long>()
        repeat(100) { phrases.forEach { p -> val t0 = System.nanoTime(); r.route(p); times += System.nanoTime() - t0 } }
        val p99 = times.sorted()[(times.size * 0.99).toInt()] / 1_000_000.0
        println("router p99 = %.3f ms over %d calls (target < 10 ms; JVM, not the phone)".format(Locale.US, p99, times.size))
        assertTrue("router p99 $p99 ms", p99 < 10.0)
    }

    @Test fun appLookupIsFastAndTiered() {
        val big = AppIndex((1..400).map { AppInfo("App Number $it", "com.example.app$it") } + installed)
        repeat(500) { big.resolve("duolingo") }
        val t0 = System.nanoTime()
        repeat(1000) { big.resolve("next step") }
        val avgMs = (System.nanoTime() - t0) / 1000 / 1_000_000.0
        println("app lookup avg = %.4f ms".format(Locale.US, avgMs))
        assertTrue(avgMs < 10.0)
        assertEquals(AppTier.EXACT, (big.resolve("duolingo") as AppMatch.Hit).tier)
        assertEquals(AppTier.COMPACT, (big.resolve("next step") as AppMatch.Hit).tier)
        assertEquals(AppTier.PACKAGE, (big.resolve("com.whatsapp") as AppMatch.Hit).tier)
        assertEquals(AppTier.WORD, (big.resolve("maps") as AppMatch.Hit).tier)
        assertTrue(big.resolve("files") is AppMatch.Ambiguous)
        assertTrue(big.resolve("zzzz") is AppMatch.None)
        // Loose matching is for the model-facing tools only.
        assertTrue(big.resolve("nextstp", loose = false) is AppMatch.None)
        assertEquals("NextStep", big.find("nextstp")?.label)
    }

    @Test fun localRepliesUseRealToolResults() {
        val battery = """{"percent":72,"status":"charging","plugged":"USB","temperature_c":31.5}"""
        val reply = LocalReplies.compose(listOf(LocalIntent.Battery), listOf(listOf(LocalOutcome(true, battery))), 0L)
        assertEquals("Battery is at 72% and charging (USB), 31.5°C.", reply)
        val storage = """{"storage":{"total_gb":128.0,"free_gb":45.2,"used_percent":64}}"""
        assertTrue(LocalReplies.compose(listOf(LocalIntent.Storage), listOf(listOf(LocalOutcome(true, storage))), 0L).contains("45.2 GB free of 128.0 GB"))
        val notes = "[WhatsApp] Pranav: hi there (09:15, can reply) key=abc\n[Gmail] Bank: statement (09:00) key=def"
        val n = LocalReplies.compose(listOf(LocalIntent.Notifications), listOf(listOf(LocalOutcome(true, notes))), 0L)
        assertTrue(n, n.startsWith("You have 2 notifications."))
        assertTrue(!n.contains("key="))
        assertEquals("NYRO Phone Control (Accessibility) is not enabled. Please enable it in NYRO → Permissions.",
            LocalReplies.compose(listOf(LocalIntent.Home), listOf(listOf(LocalOutcome(false, "FAILED: NYRO Phone Control (Accessibility) is not enabled. Ask the user to enable it in NYRO → Permissions."))), 0L))
        assertEquals("Okay, I didn't do that.", LocalReplies.compose(listOf(LocalIntent.LockScreen), listOf(listOf(LocalOutcome(false, "DENIED: no", denied = true))), 0L))
        assertEquals("Timer set for 1 hour 30 minutes.", LocalReplies.compose(listOf(LocalIntent.Timer(5400)), listOf(listOf(LocalOutcome(true, "ok"))), 0L))
    }
}
