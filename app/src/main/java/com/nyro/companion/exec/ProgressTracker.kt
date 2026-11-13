package com.nyro.companion.exec

import com.nyro.companion.perf.Clock

/** Identity of an action independent of the element ids of any one snapshot. */
data class ActionKey(val name: String, val target: String)

enum class ActionKind { TAP, BACK, SCROLL, TYPE, OPEN_APP, OTHER }

sealed interface Verdict {
    data object Proceed : Verdict

    /** The action is not executed. [code] is stable for tests and logs. */
    data class Block(val code: String, val reason: String, val hint: String) : Verdict {
        fun toModelText(): String = "BLOCKED by NYRO: $reason $hint"
    }

    /** Too many blocked attempts in one task: stop and report instead of continuing. */
    data class Abort(val reason: String) : Verdict
}

/**
 * Remembers what was done on which screen during one task and refuses actions that cannot make progress.
 *
 * Screens are identified by [Obs.layoutSignature] (stable under scrolling and ticking counters), not by element ids.
 * An action reporting success does not count as progress: only a changed screen does.
 */
class ProgressTracker(private val clock: Clock, private val maxBlocks: Int = 3) {
    data class Record(
        val key: ActionKey,
        val kind: ActionKind,
        val before: Long,
        val after: Long,
        val change: ScreenChange,
        val ok: Boolean,
        val at: Long,
    )

    private val log = ArrayList<Record>()
    var blocks: Int = 0
        private set

    fun reset() { log.clear(); blocks = 0 }

    fun records(): List<Record> = log.toList()

    fun check(key: ActionKey, kind: ActionKind, layoutSig: Long): Verdict {
        val same = log.filter { it.key == key && it.before == layoutSig }
        val label = key.target.ifBlank { key.name }

        // 1. Tried here before and nothing changed (or it failed).
        same.lastOrNull { it.change == ScreenChange.NONE || !it.ok }?.let {
            return blocked("no_effect", "You already tried \"$label\" on this screen and ${if (it.ok) "the screen did not change" else "it failed"}.",
                "Try a different element, scroll, or explain to the user what is blocking you.")
        }

        // 2. Tap X on screen A, it opened another screen, you went back to A, and now you are tapping X again.
        if (kind == ActionKind.TAP || kind == ActionKind.OPEN_APP) {
            val opened = log.indexOfFirst { it.key == key && it.before == layoutSig && it.ok && it.change == ScreenChange.LAYOUT }
            if (opened >= 0 && log.drop(opened + 1).any { it.kind == ActionKind.BACK && it.after == layoutSig }) {
                return blocked("bounce", "You already opened \"$label\" from this screen and came back.",
                    "Opening it again will lead to the same place. Use what that screen showed (for example its Start or Continue control), or choose a different target.")
            }
        }

        // 3. Safety net for any other loop: the same action on the same screen three times.
        if (same.size >= 2 && kind != ActionKind.SCROLL && kind != ActionKind.TYPE) {
            return blocked("repeat", "You have done \"$label\" on this screen ${same.size} times already.",
                "Change approach or ask the user.")
        }
        return Verdict.Proceed
    }

    private fun blocked(code: String, reason: String, hint: String): Verdict {
        blocks++
        return if (blocks >= maxBlocks) Verdict.Abort("NYRO stopped after $blocks blocked repeat attempts. Last: $reason")
        else Verdict.Block(code, reason, hint)
    }

    fun record(key: ActionKey, kind: ActionKind, beforeSig: Long, afterSig: Long, change: ScreenChange, ok: Boolean) {
        log += Record(key, kind, beforeSig, afterSig, change, ok, clock.now())
        if (log.size > MAX_RECORDS) log.removeAt(0)
    }

    /** Last N actions for the task log. */
    fun summary(n: Int = 8): String = log.takeLast(n).joinToString("; ") { r ->
        "${r.key.name}${if (r.key.target.isNotBlank()) "(${r.key.target.take(24)})" else ""}${if (!r.ok) "✗" else when (r.change) { ScreenChange.NONE -> "=" ; ScreenChange.CONTENT -> "~" ; ScreenChange.LAYOUT -> ">" }}"
    }

    companion object { const val MAX_RECORDS = 60 }
}
