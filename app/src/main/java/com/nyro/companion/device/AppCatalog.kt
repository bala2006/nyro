package com.nyro.companion.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * Launchable apps visible through the manifest <queries> launcher filter (no QUERY_ALL_PACKAGES).
 *
 * The list is read once (warmed in the background at startup) and rebuilt when Android reports a package added,
 * removed or changed, instead of every minute. Lookups go through a pre-normalised [AppIndex].
 */
class AppCatalog(private val context: Context) {
    @Volatile private var index: AppIndex? = null
    @Volatile private var builtAt = 0L

    /** Bumped on every rebuild; lets callers notice that "the list of apps" changed. */
    @Volatile var version: Int = 0
        private set

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        runCatching {
            ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) { index = null }
            }, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    /** Builds the index now if it is missing. Safe to call from a background thread. */
    fun warmUp() { index() }

    private fun index(): AppIndex {
        val cur = index
        // Safety net in case a package broadcast was missed.
        if (cur != null && System.currentTimeMillis() - builtAt < SAFETY_REFRESH_MS) return cur
        synchronized(this) {
            index?.takeIf { System.currentTimeMillis() - builtAt < SAFETY_REFRESH_MS }?.let { return it }
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase(Locale.ROOT) }
            return AppIndex(apps).also { index = it; builtAt = System.currentTimeMillis(); version++ }
        }
    }

    fun all(): List<AppInfo> = index().apps

    fun label(pkg: String): String {
        if (pkg.isEmpty()) return ""
        all().firstOrNull { it.pkg == pkg }?.let { return it.label }
        val pm = context.packageManager
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    /** Strict resolution used by the deterministic router. */
    fun resolve(query: String): AppMatch = index().resolve(query, loose = false)

    /** Best match for a spoken/typed app name for the model-facing tools (loose, never throws). */
    fun find(query: String): AppInfo? = index().find(query)

    fun search(query: String): List<AppInfo> = index().search(query)

    fun launch(pkg: String): Boolean {
        val i = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { context.startActivity(i); true }.getOrDefault(false)
    }

    companion object { private const val SAFETY_REFRESH_MS = 10 * 60_000L }
}
