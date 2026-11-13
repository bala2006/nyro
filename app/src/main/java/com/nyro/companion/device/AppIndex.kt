package com.nyro.companion.device

data class AppInfo(val label: String, val pkg: String)

/** How an app name was matched. Lower tiers are looser; routers should trust only the strict ones. */
enum class AppTier(val strict: Boolean) {
    PACKAGE(true), EXACT(true), COMPACT(true), PREFIX(true), WORD(true), CONTAINS(false), FUZZY(false),
}

sealed interface AppMatch {
    data class Hit(val app: AppInfo, val tier: AppTier) : AppMatch
    data class Ambiguous(val candidates: List<AppInfo>, val tier: AppTier) : AppMatch
    data object None : AppMatch
}

/**
 * Pre-normalised index over the launchable apps. Normalisation and word splitting happen once when the index is
 * built, so a lookup is a few string comparisons per app (microseconds for a few hundred apps), not a regex per app.
 *
 * Strict tiers, tried in order: package, exact label, compact label (spaces removed, so a speech recogniser's
 * "next step" finds "NextStep"), unique label prefix on a word boundary, unique label word. Each tier must produce
 * exactly one app; two or more is [AppMatch.Ambiguous] and the caller should ask or escalate.
 */
class AppIndex(apps: List<AppInfo>) {
    private class Entry(val app: AppInfo, val norm: String, val compact: String, val words: List<String>)

    private val entries: List<Entry> = apps.map { a ->
        val n = normalize(a.label)
        Entry(a, n, n.replace(" ", ""), n.split(' ').filter { it.isNotEmpty() })
    }
    val apps: List<AppInfo> = apps
    val size: Int get() = entries.size

    fun resolve(query: String, loose: Boolean = false): AppMatch {
        val raw = query.trim()
        if (raw.isEmpty()) return AppMatch.None
        entries.firstOrNull { it.app.pkg.equals(raw, ignoreCase = true) }?.let { return AppMatch.Hit(it.app, AppTier.PACKAGE) }
        val q = normalize(raw)
        if (q.isEmpty()) return AppMatch.None
        val qc = q.replace(" ", "")

        fun tier(t: AppTier, test: (Entry) -> Boolean): AppMatch? {
            val hits = entries.filter(test).map { it.app }.distinctBy { it.pkg }
            return when {
                hits.isEmpty() -> null
                hits.size == 1 -> AppMatch.Hit(hits[0], t)
                else -> AppMatch.Ambiguous(hits.take(5), t)
            }
        }
        tier(AppTier.EXACT) { it.norm == q }?.let { return it }
        tier(AppTier.COMPACT) { it.compact == qc }?.let { return it }
        // A brand word ("google", "samsung") is the start of many unrelated apps: only an exact name should open one.
        if (q !in GENERIC) {
            tier(AppTier.PREFIX) { it.norm.startsWith("$q ") }?.let { return it }
            if (q.length >= 3) tier(AppTier.WORD) { e -> e.words.size > 1 && q in e.words }?.let { return it }
        }
        if (!loose) return AppMatch.None

        if (qc.length >= 4) tier(AppTier.CONTAINS) { it.compact.contains(qc) || it.app.pkg.lowercase().contains(qc) }?.let { return it }
        if (qc.length >= 6) {
            val scored = entries.map { it to levenshtein(it.compact, qc) }.filter { it.second <= 1 }
            if (scored.size == 1) return AppMatch.Hit(scored[0].first.app, AppTier.FUZZY)
            if (scored.size > 1) return AppMatch.Ambiguous(scored.map { it.first.app }.take(5), AppTier.FUZZY)
        }
        return AppMatch.None
    }

    /** Best effort for the model-facing tools: loose matching, first candidate wins on ambiguity (alphabetical). */
    fun find(query: String): AppInfo? = when (val m = resolve(query, loose = true)) {
        is AppMatch.Hit -> m.app
        is AppMatch.Ambiguous -> m.candidates.first()
        AppMatch.None -> {
            val q = normalize(query)
            if (q.isEmpty()) null
            else entries.minByOrNull { levenshtein(it.norm, q) }?.takeIf { levenshtein(it.norm, q) <= maxOf(1, q.length / 4) }?.app
        }
    }

    fun search(query: String): List<AppInfo> {
        val q = normalize(query)
        if (q.isEmpty()) return apps
        val qc = q.replace(" ", "")
        return entries.filter { it.norm.contains(q) || it.compact.contains(qc) || it.app.pkg.contains(qc, ignoreCase = true) }.map { it.app }
    }

    companion object {
        private val GENERIC = setOf("google", "microsoft", "samsung", "amazon", "apple", "meta", "my", "the", "app", "apps", "android", "system", "mobile", "phone", "play", "go", "pro", "tecno", "hios")

        fun normalize(s: String): String {
            val sb = StringBuilder(s.length)
            var lastSpace = true
            for (c in s) {
                if (c.isLetterOrDigit()) { sb.append(c.lowercaseChar()); lastSpace = false }
                else if (!lastSpace) { sb.append(' '); lastSpace = true }
            }
            return sb.toString().trim()
        }

        fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
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
}
