package com.nyro.companion.exec

enum class MatchMode { EXACT, STARTS, CONTAINS }

/**
 * Semantic description of a UI element. Plans use selectors, never element ids or coordinates:
 * ids are only valid for one snapshot, so a plan that stored them would act on a stale screen.
 */
data class Selector(
    /** Matches the element's text OR content description. */
    val label: String? = null,
    val text: String? = null,
    val desc: String? = null,
    val viewId: String? = null,
    val role: String? = null,
    val mode: MatchMode = MatchMode.CONTAINS,
    /** 1-based pick among matches in screen order. */
    val index: Int? = null,
    val editable: Boolean? = null,
) {
    val isEmpty: Boolean get() = label.isNullOrBlank() && text.isNullOrBlank() && desc.isNullOrBlank() && viewId.isNullOrBlank()

    fun describe(): String = listOfNotNull(
        label?.let { "\"$it\"" }, text?.let { "text \"$it\"" }, desc?.let { "description \"$it\"" },
        viewId?.let { "id $it" }, role,
    ).joinToString(" ").ifBlank { "element" }
}

sealed interface Match {
    data class Found(val element: ObsElement, val candidates: Int) : Match
    data class Ambiguous(val candidates: List<ObsElement>) : Match
    data object NotFound : Match
}

object ElementMatcher {
    private fun norm(s: String) = s.lowercase().trim().replace(Regex("\\s+"), " ")

    private fun matches(value: String, query: String, mode: MatchMode): Boolean {
        if (value.isBlank()) return false
        val v = norm(value); val q = norm(query)
        return when (mode) {
            MatchMode.EXACT -> v == q
            MatchMode.STARTS -> v.startsWith(q)
            MatchMode.CONTAINS -> v.contains(q)
        }
    }

    private fun accepts(e: ObsElement, s: Selector): Boolean {
        s.label?.takeIf { it.isNotBlank() }?.let { q -> if (!(matches(e.text, q, s.mode) || matches(e.desc, q, s.mode))) return false }
        s.text?.takeIf { it.isNotBlank() }?.let { q -> if (!matches(e.text, q, s.mode)) return false }
        s.desc?.takeIf { it.isNotBlank() }?.let { q -> if (!matches(e.desc, q, s.mode)) return false }
        s.viewId?.takeIf { it.isNotBlank() }?.let { q -> if (!e.viewId.equals(q.substringAfter(":id/"), ignoreCase = true)) return false }
        s.role?.takeIf { it.isNotBlank() }?.let { q -> if (!e.role.equals(q, ignoreCase = true)) return false }
        s.editable?.let { if (e.editable != it) return false }
        return true
    }

    /** Higher is better. Exact label beats prefix beats substring; enabled, tappable, short labels win ties. */
    private fun score(e: ObsElement, s: Selector): Int {
        val q = norm(s.label ?: s.text ?: s.desc ?: "")
        var sc = 0
        val labels = listOf(e.text, e.desc).map(::norm)
        if (q.isNotEmpty()) {
            if (labels.any { it == q }) sc += 100 else if (labels.any { it.startsWith(q) }) sc += 60 else sc += 30
            val longest = labels.maxOf { it.length }
            if (longest > q.length * 3 + 10) sc -= 25
        }
        if (e.interactive) sc += 20
        if (!e.enabled) sc -= 60
        if (e.editable && s.editable != true) sc -= 10
        return sc
    }

    fun find(obs: Obs, s: Selector): Match {
        if (s.isEmpty && s.role.isNullOrBlank()) return Match.NotFound
        val hits = obs.elements.filter { accepts(it, s) }
        if (hits.isEmpty()) return Match.NotFound
        s.index?.let { i -> return hits.getOrNull(i - 1)?.let { Match.Found(it, hits.size) } ?: Match.NotFound }
        if (hits.size == 1) return Match.Found(hits[0], 1)
        val ranked = hits.sortedByDescending { score(it, s) }
        val margin = score(ranked[0], s) - score(ranked[1], s)
        return if (margin >= MARGIN) Match.Found(ranked[0], hits.size) else Match.Ambiguous(ranked.take(5))
    }

    const val MARGIN = 20
}

/** What must hold on screen, before a step (`require`) or after it (`expect`). */
sealed interface Condition {
    /** The foreground app is this package or app name. */
    data class App(val nameOrPackage: String) : Condition
    data class Visible(val selector: Selector) : Condition
    data class Absent(val selector: Selector) : Condition
    /** The layout or content differs from the screen before the step. */
    data object Changed : Condition
    /** The layout (a different screen) differs from the screen before the step. */
    data object Navigated : Condition
    data class AnyOf(val options: List<Condition>) : Condition
    data class AllOf(val parts: List<Condition>) : Condition

    fun describe(): String = when (this) {
        is App -> "app is $nameOrPackage"
        is Visible -> "${selector.describe()} visible"
        is Absent -> "${selector.describe()} not visible"
        Changed -> "screen changed"
        Navigated -> "a different screen"
        is AnyOf -> options.joinToString(" or ") { it.describe() }
        is AllOf -> parts.joinToString(" and ") { it.describe() }
    }
}

/** Maps an app name or package given in a plan to a package name (null if unknown). */
fun interface AppResolver {
    fun packageFor(nameOrPackage: String): String?
}

object ConditionEvaluator {
    fun eval(c: Condition, before: Obs?, now: Obs, apps: AppResolver): Boolean = when (c) {
        is Condition.App -> {
            val pkg = apps.packageFor(c.nameOrPackage) ?: c.nameOrPackage
            now.packageName.equals(pkg, ignoreCase = true)
        }
        is Condition.Visible -> ElementMatcher.find(now, c.selector) !is Match.NotFound
        is Condition.Absent -> ElementMatcher.find(now, c.selector) is Match.NotFound
        Condition.Changed -> now.change(before) != ScreenChange.NONE
        Condition.Navigated -> now.change(before) == ScreenChange.LAYOUT
        is Condition.AnyOf -> c.options.any { eval(it, before, now, apps) }
        is Condition.AllOf -> c.parts.all { eval(it, before, now, apps) }
    }

    fun allMet(list: List<Condition>, before: Obs?, now: Obs, apps: AppResolver): Boolean = list.all { eval(it, before, now, apps) }

    fun firstUnmet(list: List<Condition>, before: Obs?, now: Obs, apps: AppResolver): Condition? =
        list.firstOrNull { !eval(it, before, now, apps) }
}
