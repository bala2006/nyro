package com.nyro.companion.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.Locale

/** Launchable apps visible through the manifest <queries> launcher filter (no QUERY_ALL_PACKAGES). */
class AppCatalog(private val context: Context) {
    data class App(val label: String, val pkg: String)

    @Volatile private var cache: List<App> = emptyList()
    @Volatile private var cachedAt = 0L

    fun all(): List<App> {
        if (cache.isNotEmpty() && System.currentTimeMillis() - cachedAt < 60_000) return cache
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        cache = pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase(Locale.ROOT) }
        cachedAt = System.currentTimeMillis()
        return cache
    }

    fun label(pkg: String): String {
        if (pkg.isEmpty()) return ""
        all().firstOrNull { it.pkg == pkg }?.let { return it.label }
        val pm = context.packageManager
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    /** Best match for a spoken/typed app name, or null. Exact > prefix > word > contains > package. */
    fun find(query: String): App? {
        val q = normalize(query)
        if (q.isEmpty()) return null
        val apps = all()
        apps.firstOrNull { it.pkg.equals(query.trim(), true) }?.let { return it }
        return apps.firstOrNull { normalize(it.label) == q }
            ?: apps.firstOrNull { normalize(it.label).startsWith(q) }
            ?: apps.firstOrNull { normalize(it.label).split(' ').any { w -> w == q } }
            ?: apps.firstOrNull { normalize(it.label).contains(q) }
            ?: apps.firstOrNull { it.pkg.lowercase(Locale.ROOT).contains(q.replace(" ", "")) }
            ?: apps.minByOrNull { levenshtein(normalize(it.label), q) }?.takeIf { levenshtein(normalize(it.label), q) <= maxOf(1, q.length / 4) }
    }

    fun search(query: String): List<App> {
        val q = normalize(query)
        if (q.isEmpty()) return all()
        return all().filter { normalize(it.label).contains(q) || it.pkg.contains(q.replace(" ", ""), true) }
    }

    fun launch(pkg: String): Boolean {
        val i = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { context.startActivity(i); true }.getOrDefault(false)
    }

    private fun normalize(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
