package com.nyro.companion.perf

import android.content.Context
import android.os.SystemClock
import java.util.Locale

/** Monotonic millisecond clock. Injected so timing logic can be tested with virtual time. */
fun interface Clock {
    fun now(): Long
}

/** Wall-clock independent: never jumps when the user or network changes the date. */
object RealClock : Clock {
    override fun now(): Long = SystemClock.elapsedRealtime()
}

/** Metric names. Latency metrics are in milliseconds; counters are plain counts. */
object Metric {
    const val ROUTE = "route"
    const val PREPARE = "request_prep"
    const val HTTP_HEADERS = "http_headers"
    const val TTFT = "ttft"
    const val GENERATION = "generation"
    const val MODEL_TOTAL = "model_total"
    const val SNAPSHOT = "a11y_snapshot"
    const val LOOKUP = "element_lookup"
    const val GESTURE = "gesture"
    const val UI_WAIT = "ui_wait"
    const val TOOL = "tool"
    const val VERIFY = "verify"
    const val SHOT_CAPTURE = "shot_capture"
    const val SHOT_ENCODE = "shot_encode"
    const val TASK = "task_total"
    const val LOCAL_TASK = "local_task"

    // Counters
    const val AZURE_CALLS = "azure_calls"
    const val TOKENS_IN = "tokens_in"
    const val TOKENS_CACHED = "tokens_cached"
    const val TOKENS_NEW = "tokens_uncached"
    const val TOKENS_OUT = "tokens_out"
    const val REQUEST_BYTES = "request_bytes"
    const val IMAGE_BYTES = "image_bytes"
    const val SCREENSHOTS_SENT = "screenshots_sent"

    val LATENCY = listOf(ROUTE, PREPARE, HTTP_HEADERS, TTFT, GENERATION, MODEL_TOTAL, SNAPSHOT, LOOKUP, GESTURE, UI_WAIT, TOOL, VERIFY, SHOT_CAPTURE, SHOT_ENCODE, TASK, LOCAL_TASK)
}

data class Summary(val n: Int, val p50: Long, val p95: Long, val p99: Long, val max: Long, val mean: Long)

/** Nearest-rank percentile of an already sorted array (p in 1..100). */
fun percentile(sorted: LongArray, p: Int): Long {
    if (sorted.isEmpty()) return 0
    val rank = kotlin.math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}

fun summarize(values: LongArray): Summary {
    if (values.isEmpty()) return Summary(0, 0, 0, 0, 0, 0)
    val s = values.sortedArray()
    return Summary(s.size, percentile(s, 50), percentile(s, 95), percentile(s, 99), s.last(), s.sum() / s.size)
}

/** Bounded per-metric sample store; the newest [capacity] samples are kept. */
class LatencyStats(private val capacity: Int = 500) {
    private val data = HashMap<String, ArrayDeque<Long>>()

    @Synchronized fun record(metric: String, value: Long) {
        val q = data.getOrPut(metric) { ArrayDeque() }
        q.addLast(value)
        while (q.size > capacity) q.removeFirst()
    }

    @Synchronized fun summary(metric: String): Summary = summarize(data[metric]?.toLongArray() ?: LongArray(0))

    @Synchronized fun metrics(): List<String> = data.keys.sorted()

    @Synchronized fun clear() = data.clear()

    @Synchronized fun encode(): String = data.entries.joinToString("\n") { (k, v) -> k + "=" + v.joinToString(",") }

    @Synchronized fun decode(text: String) {
        data.clear()
        text.lineSequence().forEach { line ->
            val i = line.indexOf('=')
            if (i <= 0) return@forEach
            val vals = line.substring(i + 1).split(',').mapNotNull { it.toLongOrNull() }
            if (vals.isNotEmpty()) data[line.substring(0, i)] = ArrayDeque(vals.takeLast(capacity))
        }
    }
}

/** Aggregates for one task: count, total and max per metric, plus plain counters. */
class TaskTrace(val name: String, private val clock: Clock) {
    private val startedAt = clock.now()
    private class Agg(var n: Int = 0, var sum: Long = 0, var max: Long = 0)
    private val lat = LinkedHashMap<String, Agg>()
    private val counters = LinkedHashMap<String, Long>()

    @Synchronized fun add(metric: String, ms: Long) {
        val a = lat.getOrPut(metric) { Agg() }
        a.n++; a.sum += ms; if (ms > a.max) a.max = ms
    }

    @Synchronized fun count(metric: String, delta: Long) { counters[metric] = (counters[metric] ?: 0L) + delta }

    @Synchronized fun counter(metric: String): Long = counters[metric] ?: 0L
    @Synchronized fun total(metric: String): Long = lat[metric]?.sum ?: 0L
    @Synchronized fun calls(metric: String): Int = lat[metric]?.n ?: 0

    fun elapsed(): Long = clock.now() - startedAt

    /** Compact, human-readable block stored in the task log (task history shows it). */
    @Synchronized fun summaryText(): String {
        val sb = StringBuilder("perf: total ${fmt(elapsed())}")
        val calls = counters[Metric.AZURE_CALLS] ?: 0L
        sb.append(" · azure calls $calls")
        if (calls > 0) {
            sb.append(" · tokens in ${counters[Metric.TOKENS_IN] ?: 0} (cached ${counters[Metric.TOKENS_CACHED] ?: 0}, new ${counters[Metric.TOKENS_NEW] ?: 0}) out ${counters[Metric.TOKENS_OUT] ?: 0}")
            (counters[Metric.SCREENSHOTS_SENT] ?: 0L).takeIf { it > 0 }?.let { sb.append(" · screenshots $it") }
        }
        sb.append('\n')
        lat.entries.filter { it.key != Metric.TASK && it.key != Metric.LOCAL_TASK }.forEach { (k, a) ->
            sb.append("  ").append(k).append(": ").append(a.n).append("× total ").append(fmt(a.sum))
                .append(" avg ").append(fmt(a.sum / a.n)).append(" max ").append(fmt(a.max)).append('\n')
        }
        return sb.toString().trimEnd()
    }

    private fun fmt(ms: Long) = if (ms >= 1000) String.format(Locale.US, "%.1fs", ms / 1000.0) else "${ms}ms"
}

/**
 * Process-wide recorder. [record] feeds both the rolling percentile store and the active task trace.
 * Timings use a monotonic clock (SystemClock.elapsedRealtime), never wall time.
 */
object Perf {
    val stats = LatencyStats()
    @Volatile var clock: Clock = RealClock
    @Volatile private var trace: TaskTrace? = null
    @Volatile private var prefs: android.content.SharedPreferences? = null

    fun attach(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences("nyro_perf", Context.MODE_PRIVATE).also { p ->
            p.getString("samples", null)?.let { runCatching { stats.decode(it) } }
        }
    }

    fun begin(name: String): TaskTrace = TaskTrace(name, clock).also { trace = it }

    /**
     * Ends [t]: clears it as the active trace (only if it still is, a newer task may have started), records its total time
     * and persists samples. A task that was cancelled should call [discard] instead so it does not skew the percentiles.
     */
    fun end(t: TaskTrace, totalMetric: String = Metric.TASK): TaskTrace {
        if (trace === t) trace = null
        record(totalMetric, t.elapsed(), toTrace = false)
        flush()
        return t
    }

    fun discard(t: TaskTrace) { if (trace === t) trace = null }

    fun current(): TaskTrace? = trace

    fun record(metric: String, ms: Long, toTrace: Boolean = true) {
        if (ms < 0) return
        stats.record(metric, ms)
        if (toTrace) trace?.add(metric, ms)
    }

    fun count(metric: String, delta: Long) { trace?.count(metric, delta) }

    inline fun <T> time(metric: String, block: () -> T): T {
        val t0 = clock.now()
        try { return block() } finally { record(metric, clock.now() - t0) }
    }

    fun flush() {
        prefs?.edit()?.putString("samples", stats.encode())?.apply()
    }

    fun reset() { stats.clear(); flush() }

    /** Benchmark scenarios recorded as "bench:<name>". */
    fun benchTable(): List<Pair<String, Summary>> =
        stats.metrics().filter { it.startsWith(BENCH_PREFIX) }.map { it.removePrefix(BENCH_PREFIX) to stats.summary(it) }

    const val BENCH_PREFIX = "bench:"

    /** Rows for the diagnostics screen: metric, summary. Only metrics that have samples. */
    fun table(): List<Pair<String, Summary>> =
        Metric.LATENCY.map { it to stats.summary(it) }.filter { it.second.n > 0 }
}
