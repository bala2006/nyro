package com.nyro.companion.agent

import android.content.Context
import android.os.SystemClock
import com.nyro.companion.ai.Usage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class UsageState(
    /** All tokens used on this phone since install (reported by Azure). */
    val total: Long = 0,
    /** Tokens used by the current/last task. */
    val task: Long = 0,
    /** Output tokens per second of the latest model turn (live estimate while streaming). */
    val tokensPerSec: Float = 0f,
    val streaming: Boolean = false,
)

/**
 * Token accounting for the overlay. Exact numbers come from the usage Azure reports at the end
 * of each turn; while text is streaming, speed is estimated (~4 characters per token) and then
 * replaced with the real value.
 */
class UsageMeter(context: Context) {
    private val prefs = context.getSharedPreferences("nyro_usage", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(UsageState(total = prefs.getLong("total", 0)))
    val state: StateFlow<UsageState> = _state

    private var turnStart = 0L
    private var firstTokenAt = 0L
    private var liveChars = 0

    fun startTask() = _state.update { it.copy(task = 0) }

    fun turnStarted() {
        turnStart = SystemClock.elapsedRealtime(); firstTokenAt = 0L; liveChars = 0
        _state.update { it.copy(streaming = true) }
    }

    fun onDelta(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (firstTokenAt == 0L) firstTokenAt = now
        liveChars += text.length
        val secs = (now - firstTokenAt) / 1000f
        if (secs > 0.3f) _state.update { it.copy(tokensPerSec = liveChars / 4f / secs) }
    }

    fun turnFinished(usage: Usage?) {
        val now = SystemClock.elapsedRealtime()
        _state.update { s ->
            if (usage == null) return@update s.copy(streaming = false)
            // Reasoning models produce no visible text while thinking, so measure the whole turn.
            val secs = ((now - turnStart) / 1000f).coerceAtLeast(0.05f)
            val newTotal = s.total + usage.total
            prefs.edit().putLong("total", newTotal).apply()
            s.copy(
                total = newTotal, task = s.task + usage.total,
                tokensPerSec = if (usage.output > 0) usage.output / secs else s.tokensPerSec, streaming = false,
            )
        }
    }

    fun reset() { prefs.edit().putLong("total", 0).apply(); _state.value = UsageState() }

    companion object {
        fun fmt(n: Long): String = when {
            n >= 1_000_000 -> "%.1fM".format(n / 1_000_000f)
            n >= 10_000 -> "%.0fk".format(n / 1000f)
            n >= 1_000 -> "%.1fk".format(n / 1000f)
            else -> n.toString()
        }
    }
}
