package com.nyro.companion.ai

import com.nyro.companion.data.ProviderConfig
import com.nyro.companion.data.ProviderType
import com.nyro.companion.data.WireApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.cancellation.CancellationException

data class ToolCall(val id: String, val name: String, val arguments: String)

data class AiTurn(
    val content: String,
    val toolCalls: List<ToolCall>,
    val finishReason: String?,
    val model: String?,
    /** Which REST API produced this turn. */
    val api: WireKind = WireKind.CHAT,
    /** Responses API only: the raw output items (reasoning, function_call, message) to replay next turn. */
    val rawItems: JsonArray? = null,
    val usage: Usage? = null,
)

/** [cached]: input tokens served from the prompt cache. [cacheWrite]: tokens written to it (GPT-5.6+ bills these). */
data class Usage(val input: Long, val output: Long, val total: Long, val cached: Long = 0, val cacheWrite: Long = 0)

private fun JsonObject.long(k: String): Long = (this[k] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L

/** Reads both shapes: chat (prompt/completion_tokens) and responses (input/output_tokens). */
internal fun usageOf(u: JsonObject?): Usage? {
    u ?: return null
    val input = u.long("prompt_tokens").takeIf { it > 0 } ?: u.long("input_tokens")
    val output = u.long("completion_tokens").takeIf { it > 0 } ?: u.long("output_tokens")
    val total = u.long("total_tokens").takeIf { it > 0 } ?: (input + output)
    val details = (u["prompt_tokens_details"] ?: u["input_tokens_details"]) as? JsonObject
    val cached = details?.long("cached_tokens") ?: 0L
    val write = details?.long("cache_write_tokens")?.takeIf { it > 0 } ?: u.long("cache_write_tokens")
    return if (total <= 0) null else Usage(input, output, total, cached, write)
}

/**
 * GPT-5.6 / GPT-6 family deployments. These get prompt_cache_key and Responses API routing.
 * Matched on the deployment name, so name deployments after the model (e.g. "gpt-6-luna").
 */
internal fun isGpt6Family(deployment: String) = Regex("(?i)gpt-?(5\\.6|6)|luna|astra|\\bsol\\b|terra").containsMatchIn(deployment)

/** Sol and Luna accept reasoning_effort=none. Astra does not, so it keeps its default unless set. */
internal fun acceptsEffortNone(deployment: String) = Regex("(?i)luna|\\bsol\\b").containsMatchIn(deployment)

/**
 * Reasoning effort to send, or null to omit the field. A saved setting wins. In Auto, Luna and Sol
 * use "none" (fastest; the only setting where function tools work on Chat Completions). Other
 * deployments omit the field, so non-reasoning models are never sent an unknown parameter.
 */
internal fun effortFor(cfg: ProviderConfig, deployment: String, forceNone: Boolean = false): String? {
    if (forceNone) return "none"
    cfg.reasoningEffort.takeIf { it.isNotBlank() }?.let { return it }
    return if (acceptsEffortNone(deployment)) "none" else null
}

enum class WireKind { CHAT, RESPONSES }

/** Private key on an assistant message holding Responses output items; never sent to Chat Completions. */
internal const val RAW_ITEMS_KEY = "_nyro_responses_items"

/** Failure categories surfaced to the user with a meaningful explanation. */
enum class AiErrorKind { NOT_CONFIGURED, INVALID_ENDPOINT, AUTH, DEPLOYMENT_NOT_FOUND, API_VERSION, RATE_LIMIT, QUOTA, CAPABILITY, CONTENT_FILTER, NETWORK, TIMEOUT, SERVER, BAD_REQUEST, UNKNOWN }

class AiException(val kind: AiErrorKind, message: String, val httpCode: Int = 0, val retryAfterSec: Int = 0) : Exception(message)

object Msg {
    fun system(text: String) = buildJsonObject { put("role", "system"); put("content", text) }
    fun user(text: String) = buildJsonObject { put("role", "user"); put("content", text) }
    fun assistant(text: String) = buildJsonObject { put("role", "assistant"); put("content", text) }
    fun userWithImage(text: String, jpegBase64: String) = buildJsonObject {
        put("role", "user")
        put("content", buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", text) })
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$jpegBase64"); put("detail", "auto") })
            })
        })
    }
    fun assistantToolCalls(content: String, calls: List<ToolCall>, rawItems: JsonArray? = null) = buildJsonObject {
        rawItems?.let { put(RAW_ITEMS_KEY, it) }
        put("role", "assistant")
        if (content.isNotEmpty()) put("content", content) else put("content", JsonPrimitive(null as String?))
        put("tool_calls", buildJsonArray {
            calls.forEach { c ->
                add(buildJsonObject {
                    put("id", c.id); put("type", "function")
                    put("function", buildJsonObject { put("name", c.name); put("arguments", c.arguments) })
                })
            }
        })
    }
    fun tool(callId: String, content: String) = buildJsonObject {
        put("role", "tool"); put("tool_call_id", callId); put("content", content)
    }
}

/**
 * Direct HTTPS client for the user's own Azure deployment. No proxy, no backend.
 * Supports the classic Azure OpenAI deployment route, the Azure OpenAI v1 route and the
 * Azure AI Foundry model-inference route; all three speak the chat-completions schema
 * but differ in URL shape, versioning and how the model is selected.
 */
class AzureClient(
    private val keyProvider: () -> String?,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ---------- URL / validation ----------

    fun validate(cfg: ProviderConfig): String? {
        val raw = cfg.endpoint.trim()
        if (raw.isEmpty()) return "Endpoint URL is required."
        if (!raw.startsWith("https://", ignoreCase = true)) return "Endpoint must start with https://"
        val url = raw.toHttpUrlOrNull() ?: return "Endpoint is not a valid URL."
        if (url.host.isBlank()) return "Endpoint has no host name."
        if (cfg.deployment.isBlank()) return "Deployment / model name is required."
        if (!cfg.deployment.matches(Regex("[A-Za-z0-9._\\-:/]+"))) return "Deployment name contains unsupported characters."
        if (cfg.type != ProviderType.AZURE_OPENAI_V1 && cfg.apiVersion.isBlank()) return "API version is required for this provider type."
        return null
    }

    fun hostWarning(cfg: ProviderConfig): String? {
        val host = cfg.endpoint.trim().toHttpUrlOrNull()?.host ?: return null
        val known = listOf(".openai.azure.com", ".cognitiveservices.azure.com", ".services.ai.azure.com", ".models.ai.azure.com", ".inference.ai.azure.com", ".azure-api.net")
        return if (known.none { host.endsWith(it) }) "Host $host is not a standard Azure AI domain. That is fine for custom domains or API Management, but double-check it." else null
    }

    private fun baseOf(endpoint: String): String {
        var e = endpoint.trim().trimEnd('/')
        // Accept a pasted full request URL: keep only scheme + host (+ any custom gateway prefix).
        val hostEnd = e.indexOf('/', "https://".length).let { if (it < 0) e.length else it }
        listOf("/openai", "/models").forEach { marker ->
            val i = e.indexOf(marker, startIndex = hostEnd, ignoreCase = true)
            if (i >= hostEnd) e = e.substring(0, i)
        }
        val q = e.indexOf('?'); if (q > 0) e = e.substring(0, q)
        return e.trimEnd('/')
    }

    private fun urlFor(cfg: ProviderConfig, deployment: String): String {
        val base = baseOf(cfg.endpoint)
        return when (cfg.type) {
            ProviderType.AZURE_OPENAI ->
                "$base/openai/deployments/${java.net.URLEncoder.encode(deployment, "UTF-8")}/chat/completions?api-version=${cfg.apiVersion.trim()}"
            ProviderType.AZURE_OPENAI_V1 -> "$base/openai/v1/chat/completions"
            ProviderType.AZURE_AI_FOUNDRY -> "$base/models/chat/completions?api-version=${cfg.apiVersion.trim()}"
        }
    }

    /** The v1 Responses route works for Azure OpenAI and Foundry resources alike. */
    private fun responsesUrlFor(cfg: ProviderConfig): String = "${baseOf(cfg.endpoint)}/openai/v1/responses"

    /** One route per deployment, chosen from settings and the model name. There is no fallback between routes. */
    private fun wantsResponses(cfg: ProviderConfig, deployment: String) = when (cfg.wireApi) {
        WireApi.RESPONSES -> true
        WireApi.CHAT -> false
        WireApi.AUTO -> isGpt6Family(deployment)
    }

    // ---------- Requests ----------

    /**
     * One model turn. Messages and tools are always given in Chat Completions shape; when the
     * deployment needs the Responses API they are translated on the wire. When [onDelta] is set
     * the response is streamed and text deltas are forwarded as they arrive.
     */
    suspend fun chat(
        cfg: ProviderConfig,
        messages: List<JsonObject>,
        tools: JsonArray? = null,
        deployment: String = cfg.deployment,
        onDelta: ((String) -> Unit)? = null,
        maxTokens: Int? = null,
        /** Stable per conversation, so requests sharing a prefix hit the same cache (GPT-5.6+ only). */
        cacheKey: String? = null,
    ): AiTurn {
        validate(cfg)?.let { throw AiException(AiErrorKind.INVALID_ENDPOINT, it) }
        val key = keyProvider() ?: throw AiException(AiErrorKind.NOT_CONFIGURED, "No API key saved. Add it in Settings → AI Provider.")
        // Single route, no retries on another API or model. Errors are reported as they are.
        return if (wantsResponses(cfg, deployment)) {
            send(cfg, key, deployment, onDelta, WireKind.RESPONSES) { stream -> responsesBody(cfg, deployment, messages, tools, stream, maxTokens, cacheKey) }
        } else {
            send(cfg, key, deployment, onDelta, WireKind.CHAT) { stream -> chatBody(cfg, deployment, messages, tools, stream, maxTokens, effortFor(cfg, deployment), cacheKey) }
        }
    }

    private fun stripPrivate(m: JsonObject): JsonObject =
        if (RAW_ITEMS_KEY in m) JsonObject(m - RAW_ITEMS_KEY) else m

    private fun chatBody(cfg: ProviderConfig, deployment: String, messages: List<JsonObject>, tools: JsonArray?, stream: Boolean, maxTokens: Int?, effort: String?, cacheKey: String?) = buildJsonObject {
        if (cfg.type != ProviderType.AZURE_OPENAI) put("model", deployment)
        put("messages", JsonArray(messages.map(::stripPrivate)))
        if (tools != null && tools.isNotEmpty()) { put("tools", tools); put("tool_choice", "auto") }
        effort?.let { put("reasoning_effort", it) }
        if (cacheKey != null && isGpt6Family(deployment)) put("prompt_cache_key", cacheKey)
        if (maxTokens != null) put("max_tokens", maxTokens)
        if (stream) { put("stream", true); put("stream_options", buildJsonObject { put("include_usage", true) }) }
    }

    /** Translates Chat-shaped history into Responses input items (stateless: store=false). */
    private fun responsesBody(cfg: ProviderConfig, deployment: String, messages: List<JsonObject>, tools: JsonArray?, stream: Boolean, maxTokens: Int?, cacheKey: String?) = buildJsonObject {
        put("model", deployment)
        val instructions = messages.filter { it.str("role") == "system" }.mapNotNull { it.str("content") }.joinToString("\n\n")
        if (instructions.isNotBlank()) put("instructions", instructions)
        put("input", buildJsonArray {
            for (m in messages) {
                when (m.str("role")) {
                    "system" -> Unit
                    "tool" -> add(buildJsonObject {
                        put("type", "function_call_output"); put("call_id", m.str("tool_call_id").orEmpty()); put("output", m.str("content").orEmpty())
                    })
                    "assistant" -> {
                        val raw = m[RAW_ITEMS_KEY] as? JsonArray
                        if (raw != null) raw.forEach { add(it) }
                        else {
                            m.str("content")?.takeIf { it.isNotBlank() }?.let { t -> add(buildJsonObject { put("role", "assistant"); put("content", t) }) }
                            (m["tool_calls"] as? JsonArray)?.forEach { tc ->
                                val o = tc.jsonObject; val f = o["function"]?.jsonObject
                                add(buildJsonObject {
                                    put("type", "function_call"); put("call_id", o.str("id").orEmpty())
                                    put("name", f?.str("name").orEmpty()); put("arguments", f?.str("arguments") ?: "{}")
                                })
                            }
                        }
                    }
                    else -> { // user
                        when (val c = m["content"]) {
                            is JsonArray -> add(buildJsonObject {
                                put("role", "user")
                                put("content", buildJsonArray {
                                    c.forEach { part ->
                                        val p = part.jsonObject
                                        when (p.str("type")) {
                                            "text" -> add(buildJsonObject { put("type", "input_text"); put("text", p.str("text").orEmpty()) })
                                            "image_url" -> add(buildJsonObject {
                                                put("type", "input_image")
                                                put("image_url", p["image_url"]?.jsonObject?.str("url").orEmpty())
                                                put("detail", "auto")
                                            })
                                        }
                                    }
                                })
                            })
                            else -> add(buildJsonObject { put("role", "user"); put("content", (c as? JsonPrimitive)?.contentOrNull.orEmpty()) })
                        }
                    }
                }
            }
        })
        if (tools != null && tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                tools.forEach { t ->
                    val f = t.jsonObject["function"]?.jsonObject ?: return@forEach
                    add(buildJsonObject {
                        put("type", "function"); put("name", f.str("name").orEmpty())
                        f["description"]?.let { put("description", it) }
                        f["parameters"]?.let { put("parameters", it) }
                    })
                }
            })
            put("tool_choice", "auto")
        }
        effortFor(cfg, deployment)?.let { e -> put("reasoning", buildJsonObject { put("effort", e) }) }
        // Keep conversation state on the phone: nothing stored server-side, reasoning replayed encrypted.
        put("store", false)
        if (cacheKey != null && isGpt6Family(deployment)) put("prompt_cache_key", cacheKey)
        put("include", buildJsonArray { add(JsonPrimitive("reasoning.encrypted_content")) })
        if (maxTokens != null) put("max_output_tokens", maxTokens)
        if (stream) put("stream", true)
    }

    private suspend fun send(
        cfg: ProviderConfig,
        key: String,
        deployment: String,
        onDelta: ((String) -> Unit)?,
        wire: WireKind,
        body: (stream: Boolean) -> JsonObject,
    ): AiTurn = withContext(Dispatchers.IO) {
        var stream = onDelta != null
        var attempt = 0
        var emitted = false
        val delta: ((String) -> Unit)? = onDelta?.let { cb -> { d: String -> emitted = true; cb(d) } }
        var dropInclude = false
        var dropStreamOptions = false
        while (true) {
            attempt++
            var b = body(stream)
            if (dropInclude) b = JsonObject(b - "include")
            if (dropStreamOptions) b = JsonObject(b - "stream_options")
            val req = Request.Builder()
                .url(if (wire == WireKind.RESPONSES) responsesUrlFor(cfg) else urlFor(cfg, deployment))
                .header("api-key", key)
                .header("Content-Type", "application/json")
                .header("Accept", if (stream) "text/event-stream" else "application/json")
                .post(b.toString().toRequestBody(JSON_MT))
                .build()
            try {
                val resp = http.newCall(req).await()
                resp.use { r ->
                    if (!r.isSuccessful) throw mapHttpError(r.code, r.body?.string().orEmpty(), r.header("Retry-After"))
                    return@withContext when {
                        wire == WireKind.RESPONSES && stream -> readResponsesStream(r, delta!!)
                        wire == WireKind.RESPONSES -> parseResponse(r.body?.string().orEmpty().let { json.parseToJsonElement(it).jsonObject })
                        stream -> readStream(r, delta!!)
                        else -> readFull(r.body?.string().orEmpty())
                    }
                }
            } catch (e: AiException) {
                val msg = e.message.orEmpty()
                if (wire == WireKind.RESPONSES && !dropInclude && e.httpCode == 400 && (msg.contains("include", true) || msg.contains("encrypted_content", true))) {
                    dropInclude = true; continue // model without reasoning items
                }
                if (!dropStreamOptions && e.httpCode == 400 && msg.contains("stream_options", true)) { dropStreamOptions = true; continue }
                val retryable = (e.kind == AiErrorKind.RATE_LIMIT && e.retryAfterSec in 0..12) || e.kind == AiErrorKind.SERVER
                if (stream && e.kind == AiErrorKind.BAD_REQUEST && msg.contains("stream", true) && !msg.contains("/responses", true)) {
                    stream = false; continue // deployment rejects streaming: fall back to a single response
                }
                if (retryable && attempt < 3 && !emitted) { delay(((e.retryAfterSec.takeIf { it > 0 } ?: 2) * 1000L)); continue }
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                val mapped = mapIo(e)
                // Never replay a request whose partial answer was already shown/spoken.
                if (mapped.kind == AiErrorKind.TIMEOUT && attempt < 2 && !emitted) continue
                throw mapped
            } catch (e: kotlinx.serialization.SerializationException) {
                throw AiException(AiErrorKind.SERVER, "Unreadable response from endpoint.")
            }
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    // ---------- Responses API parsing ----------

    private suspend fun readResponsesStream(r: Response, onDelta: (String) -> Unit): AiTurn {
        val source = r.body?.source() ?: throw AiException(AiErrorKind.SERVER, "Empty response body")
        val ctx = kotlin.coroutines.coroutineContext
        val text = StringBuilder()
        var final: JsonObject? = null
        while (true) {
            ctx.ensureActive()
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val ev = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
            when (ev.str("type")) {
                "response.output_text.delta" -> ev.str("delta")?.let { if (it.isNotEmpty()) { text.append(it); onDelta(it) } }
                "response.completed", "response.incomplete" -> { final = ev["response"]?.jsonObject; break }
                "response.failed" -> {
                    val err = ev["response"]?.jsonObject?.get("error")?.jsonObject
                    throw mapHttpError(if (err?.str("code") == "server_error") 500 else 400, buildJsonObject { err?.let { put("error", it) } }.toString(), null)
                }
                "error" -> throw mapHttpError(400, buildJsonObject { put("error", ev) }.toString(), null)
            }
        }
        val f = final ?: throw AiException(AiErrorKind.SERVER, "The response stream ended before completion.")
        return parseResponse(f, streamedText = text.toString())
    }

    private fun parseResponse(obj: JsonObject, streamedText: String? = null): AiTurn {
        obj["error"]?.let { if (it is JsonObject) throw mapHttpError(400, buildJsonObject { put("error", it) }.toString(), null) }
        val output = obj["output"] as? JsonArray ?: JsonArray(emptyList())
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        output.forEach { el ->
            val item = el.jsonObject
            when (item.str("type")) {
                "message" -> (item["content"] as? JsonArray)?.forEach { c ->
                    val co = c.jsonObject
                    if (co.str("type") == "output_text") text.append(co.str("text").orEmpty())
                }
                "function_call" -> calls += ToolCall(item.str("call_id") ?: item.str("id") ?: "call_${calls.size}", item.str("name").orEmpty(), item.str("arguments") ?: "{}")
            }
        }
        val incomplete = obj["incomplete_details"] as? JsonObject
        if (incomplete?.str("reason") == "content_filter") throw AiException(AiErrorKind.CONTENT_FILTER, "Azure content filter blocked the response.")
        return AiTurn(
            content = text.toString().ifEmpty { streamedText.orEmpty() },
            toolCalls = calls.filter { it.name.isNotBlank() },
            finishReason = obj.str("status"),
            model = obj.str("model"),
            api = WireKind.RESPONSES,
            rawItems = output,
            usage = usageOf(obj["usage"] as? JsonObject),
        )
    }

    private suspend fun readStream(r: Response, onDelta: (String) -> Unit): AiTurn {
        val source = r.body?.source() ?: throw AiException(AiErrorKind.SERVER, "Empty response body")
        val content = StringBuilder()
        val calls = sortedMapOf<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>() // id, name, args
        var finish: String? = null
        var model: String? = null
        var usage: Usage? = null
        val ctx = kotlin.coroutines.coroutineContext
        while (true) {
            ctx.ensureActive()
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            if (data.isEmpty()) continue
            val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
            obj["error"]?.let { throw mapHttpError(400, data, null) }
            usageOf(obj["usage"] as? JsonObject)?.let { usage = it }
            obj.str("model")?.takeIf { it.isNotBlank() }?.let { model = it }
            val choices = obj["choices"] as? JsonArray ?: continue
            for (ch in choices) {
                val c = ch.jsonObject
                c.str("finish_reason")?.let { finish = it }
                val delta = c["delta"] as? JsonObject ?: continue
                delta.str("content")?.let { if (it.isNotEmpty()) { content.append(it); onDelta(it) } }
                (delta["tool_calls"] as? JsonArray)?.forEach { tcEl ->
                    val tc = tcEl.jsonObject
                    val idx = tc["index"]?.jsonPrimitive?.intOrNull ?: calls.size
                    val slot = calls.getOrPut(idx) { Triple(StringBuilder(), StringBuilder(), StringBuilder()) }
                    tc.str("id")?.let { if (slot.first.isEmpty()) slot.first.append(it) }
                    (tc["function"] as? JsonObject)?.let { f ->
                        f.str("name")?.let { if (slot.second.isEmpty()) slot.second.append(it) }
                        f.str("arguments")?.let { slot.third.append(it) }
                    }
                }
            }
        }
        if (finish == "content_filter") throw AiException(AiErrorKind.CONTENT_FILTER, "Azure content filter blocked the response.")
        val toolCalls = calls.values.mapIndexed { i, (id, name, args) ->
            ToolCall(id.toString().ifEmpty { "call_$i" }, name.toString(), args.toString().ifBlank { "{}" })
        }.filter { it.name.isNotBlank() }
        return AiTurn(content.toString(), toolCalls, finish, model, usage = usage)
    }

    private fun readFull(text: String): AiTurn {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
            throw AiException(AiErrorKind.SERVER, "Unreadable response from endpoint.")
        }
        val choice = (obj["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: throw AiException(AiErrorKind.SERVER, "Response contained no choices.")
        val finish = choice.str("finish_reason")
        if (finish == "content_filter") throw AiException(AiErrorKind.CONTENT_FILTER, "Azure content filter blocked the response.")
        val msg = choice["message"]?.jsonObject
        val calls = (msg?.get("tool_calls") as? JsonArray)?.mapIndexedNotNull { i, el ->
            val tc = el.jsonObject
            val f = tc["function"]?.jsonObject ?: return@mapIndexedNotNull null
            ToolCall(tc.str("id") ?: "call_$i", f.str("name") ?: return@mapIndexedNotNull null, f.str("arguments") ?: "{}")
        }.orEmpty()
        return AiTurn(msg?.str("content").orEmpty(), calls, finish, obj.str("model"), usage = usageOf(obj["usage"] as? JsonObject))
    }

    // ---------- Connection test ----------

    data class TestResult(val ok: Boolean, val summary: String, val capability: String)

    suspend fun testConnection(cfg: ProviderConfig): TestResult {
        validate(cfg)?.let { return TestResult(false, it, "") }
        val pingTool = buildJsonArray {
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", "ping"); put("description", "Connectivity check. Call it with value 'ok'.")
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject { put("value", buildJsonObject { put("type", "string") }) })
                        put("required", buildJsonArray { add(JsonPrimitive("value")) })
                    })
                })
            })
        }
        val started = System.currentTimeMillis()
        val caps = mutableListOf<String>()
        val model: String?
        try {
            val turn = try {
                chat(cfg, listOf(Msg.user("Call the ping tool with value 'ok'.")), pingTool)
            } catch (e: AiException) {
                if (e.kind == AiErrorKind.BAD_REQUEST || e.kind == AiErrorKind.CAPABILITY) {
                    val plain = chat(cfg, listOf(Msg.user("Reply with the single word OK.")))
                    val ms = System.currentTimeMillis() - started
                    return TestResult(
                        true,
                        "Connected, but tool calling failed on both Chat Completions and the Responses API (${e.message}). NYRO needs tool calling to control the phone; only chat will work. Try setting API to Responses and reasoning effort to a supported value.",
                        "Model: ${plain.model ?: cfg.deployment} · Tools ✗ · ${ms}ms"
                    )
                } else throw e
            }
            model = turn.model
            val ms = System.currentTimeMillis() - started
            caps += if (turn.api == WireKind.RESPONSES) "Responses API" else "Chat Completions"
            caps += if (turn.toolCalls.any { it.name == "ping" }) "Tools ✓" else "Tools ? (model answered without calling)"
            caps += "${ms}ms"
        } catch (e: AiException) {
            return TestResult(false, e.message ?: "Connection failed", "")
        }

        // Streaming probe
        caps += try {
            var got = false
            chat(cfg, listOf(Msg.user("Say OK.")), onDelta = { got = true })
            if (got) "Streaming ✓" else "Streaming ✓ (empty)"
        } catch (e: AiException) { "Streaming ✗" }

        // Vision probe on the vision deployment (or the main one) with a tiny generated image.
        val visionDep = cfg.visionDeployment.ifBlank { cfg.deployment }
        caps += try {
            chat(cfg, listOf(Msg.userWithImage("What single color fills this image? One word.", TINY_BLUE_JPEG)), deployment = visionDep)
            if (cfg.visionDeployment.isBlank()) "Vision ✓" else "Vision ✓ ($visionDep)"
        } catch (e: AiException) {
            if (e.kind == AiErrorKind.AUTH || e.kind == AiErrorKind.NETWORK) "Vision ?" else "Vision ✗"
        }
        return TestResult(true, "Connected to ${model ?: cfg.deployment}.", "Model: ${model ?: cfg.deployment} · " + caps.joinToString(" · "))
    }

    // ---------- Error mapping ----------

    private fun mapHttpError(code: Int, body: String, retryAfter: String?): AiException {
        val err = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonObject }.getOrNull()
        val ecode = err?.str("code").orEmpty()
        val emsg = (err?.str("message") ?: body.take(300)).replace(Regex("[A-Za-z0-9]{32,}"), "•••")
        val ra = retryAfter?.toIntOrNull() ?: Regex("retry after (\\d+) second", RegexOption.IGNORE_CASE).find(emsg)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return when {
            code == 401 -> AiException(AiErrorKind.AUTH, "Invalid API key or the key does not belong to this resource (401).", code)
            code == 403 -> AiException(AiErrorKind.AUTH, "Access denied (403): $emsg", code)
            code == 404 && (ecode.contains("DeploymentNotFound", true) || emsg.contains("deployment", true)) ->
                AiException(AiErrorKind.DEPLOYMENT_NOT_FOUND, "Deployment not found. Check the deployment name exactly as shown in Azure (404).", code)
            code == 404 -> AiException(AiErrorKind.INVALID_ENDPOINT, "Endpoint path not found (404). Check endpoint URL, provider type and API version. $emsg", code)
            code == 429 && (ecode.contains("quota", true) || emsg.contains("quota", true) || ecode == "insufficient_quota") ->
                AiException(AiErrorKind.QUOTA, "Azure quota exhausted for this deployment: $emsg", code, ra)
            code == 429 -> AiException(AiErrorKind.RATE_LIMIT, "Rate limited by Azure${if (ra > 0) ", retry in ${ra}s" else ""}.", code, ra)
            code == 400 && (emsg.contains("api-version", true) || emsg.contains("api version", true)) ->
                AiException(AiErrorKind.API_VERSION, "API version not supported by this endpoint: $emsg", code)
            code == 400 && ecode == "content_filter" -> AiException(AiErrorKind.CONTENT_FILTER, "Azure content filter blocked the request.", code)
            code == 400 && (emsg.contains("image", true) || emsg.contains("tools", true) || emsg.contains("function", true) || ecode.contains("unsupported", true)) ->
                AiException(AiErrorKind.CAPABILITY, "Model capability not supported: $emsg", code)
            code in 400..499 -> AiException(AiErrorKind.BAD_REQUEST, "Request rejected ($code): $emsg", code)
            code >= 500 -> AiException(AiErrorKind.SERVER, "Azure service error ($code). ${emsg.take(160)}", code)
            else -> AiException(AiErrorKind.UNKNOWN, "Unexpected response ($code): $emsg", code)
        }
    }

    private fun mapIo(e: IOException): AiException = when (e) {
        is UnknownHostException -> AiException(AiErrorKind.NETWORK, "Cannot resolve the endpoint host. Check the URL or your internet connection.")
        is InterruptedIOException -> AiException(AiErrorKind.TIMEOUT, "The request to Azure timed out.")
        is SSLException -> AiException(AiErrorKind.NETWORK, "Secure connection failed (TLS).")
        else -> AiException(AiErrorKind.NETWORK, "Network error: ${e.javaClass.simpleName}")
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    companion object {
        private val JSON_MT = "application/json".toMediaType()

        /** 16×16 solid blue JPEG, generated at runtime once. */
        private val TINY_BLUE_JPEG: String by lazy {
            val bmp = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.BLUE)
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            bmp.recycle()
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        }
    }
}

internal fun JsonElement?.asStringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
