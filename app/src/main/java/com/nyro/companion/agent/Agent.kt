package com.nyro.companion.agent

import com.nyro.companion.ai.AiErrorKind
import com.nyro.companion.ai.AiException
import com.nyro.companion.ai.RAW_ITEMS_KEY
import com.nyro.companion.access.NyroAccessibilityService
import com.nyro.companion.ai.Msg
import com.nyro.companion.ai.ToolCall
import com.nyro.companion.core.AppGraph
import com.nyro.companion.data.ConversationEntity
import com.nyro.companion.data.MessageEntity
import com.nyro.companion.data.Redactor
import com.nyro.companion.data.TaskEntity
import com.nyro.companion.overlay.UiFeedback
import com.nyro.companion.tools.Risk
import com.nyro.companion.tools.ScreenSession
import com.nyro.companion.tools.Tool
import com.nyro.companion.tools.annotate
import com.nyro.companion.tools.captureScreen
import com.nyro.companion.tools.encode
import com.nyro.companion.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Phase { IDLE, LISTENING, THINKING, SPEAKING, EXECUTING, ERROR }
enum class StepStatus { RUNNING, DONE, FAILED, DENIED }

data class Step(val id: Int, val label: String, val status: StepStatus, val detail: String = "", val risk: Risk = Risk.LOW)

data class Confirmation(val risk: Risk, val text: String, internal val answer: CompletableDeferred<Boolean>)

data class AssistantState(
    val phase: Phase = Phase.IDLE,
    val partialTranscript: String = "",
    val request: String = "",
    val reply: String = "",
    val steps: List<Step> = emptyList(),
    val confirmation: Confirmation? = null,
    val error: String? = null,
    val taskActive: Boolean = false,
    val localOnly: Boolean = false,
)

/**
 * The on-device agent. Azure decides *what* to do (structured tool calls); this class validates
 * each call locally, asks the user for risky ones, executes them with Android APIs and feeds the
 * observed result back until the model produces a final answer.
 */
class Agent(private val g: AppGraph) {
    private val _state = MutableStateFlow(AssistantState())
    val state: StateFlow<AssistantState> = _state

    private val _conversationId = MutableStateFlow<Long?>(null)
    val conversationId: StateFlow<Long?> = _conversationId

    private var job: Job? = null

    private val _private = MutableStateFlow(false)
    /** Private session: nothing from it is written to history, tasks or memory. */
    val privateSession: StateFlow<Boolean> = _private
    private val _privateMessages = MutableStateFlow<List<MessageEntity>>(emptyList())
    /** Messages of the current private session, kept in memory only. */
    val privateMessages: StateFlow<List<MessageEntity>> = _privateMessages
    private val _paused = MutableStateFlow(false)
    private val undoStack = ArrayList<Pair<String, suspend () -> Unit>>()
    private val _undoLabel = MutableStateFlow<String?>(null)
    /** Label of the action Undo would reverse, or null. */
    val undoLabel: StateFlow<String?> = _undoLabel
    private val _mood = MutableStateFlow<String?>(null)
    /** Short-lived mood for the orb after a task: "happy" or "confused". Null otherwise. */
    val mood: StateFlow<String?> = _mood
    private val _sentLog = MutableStateFlow<List<String>>(emptyList())
    /** Recent requests sent to Azure, newest first (privacy log). */
    val sentLog: StateFlow<List<String>> = _sentLog
    val paused: StateFlow<Boolean> = _paused

    private val _lastSent = MutableStateFlow("")
    /** Plain-language summary of the most recent request sent to Azure (Privacy section on the Activity tab). */
    val lastSent: StateFlow<String> = _lastSent
    @Volatile private var currentRequest = ""
    @Volatile private var lastActionLabel: String? = null
    private var lastFromVoice = false

    fun newConversation() { stop(); _conversationId.value = null; _state.value = AssistantState() }

    fun startPrivateSession() {
        stop(); _private.value = true; _privateMessages.value = emptyList(); _conversationId.value = PRIVATE_ID
        _state.value = AssistantState()
    }

    fun endPrivateSession() {
        stop(); _private.value = false; _privateMessages.value = emptyList(); _conversationId.value = null
        _state.value = AssistantState()
    }

    /** Pause between steps. The current model call or tool finishes first; resume continues from the same point. */
    fun togglePause() { _paused.value = !_paused.value }

    private suspend fun awaitIfPaused(): Boolean {
        if (!_paused.value) return false
        _paused.first { !it }
        return true
    }

    /** Writes go through these so a private session never touches the database. */
    private suspend fun saveMessage(m: MessageEntity): Long {
        if (_private.value) {
            val id = -(_privateMessages.value.size + 1).toLong()
            _privateMessages.update { it + m.copy(id = id) }
            return id
        }
        return g.dao.insertMessage(m)
    }

    private suspend fun saveTask(t: TaskEntity): Long = if (_private.value) -1L else g.dao.insertTask(t)

    private suspend fun priorMessages(cid: Long, n: Int): List<MessageEntity> =
        if (_private.value) _privateMessages.value.takeLast(n) else g.dao.recentMessages(cid, n)
    fun openConversation(id: Long) { stop(); _conversationId.value = id; _state.value = AssistantState() }

    fun submit(text: String, fromVoice: Boolean = false) {
        val clean = expandCommand(text.trim())
        if (clean.isEmpty()) return
        job?.cancel()
        g.voice.stopSpeaking()
        lastFromVoice = fromVoice
        job = g.scope.launch(Dispatchers.Default) { run(clean) }
    }

    /** Emergency stop: cancels the task, speech and listening immediately. */
    fun stop() {
        _paused.value = false
        job?.cancel(); job = null
        _state.value.confirmation?.answer?.complete(false)
        g.voice.stopSpeaking(); g.voice.stopListening()
        UiFeedback.setExecuting(false)
        _state.update { it.copy(phase = Phase.IDLE, confirmation = null, taskActive = false, partialTranscript = "") }
    }

    fun answerConfirmation(allow: Boolean) { _state.value.confirmation?.answer?.complete(allow) }

    // ---------------- Voice glue ----------------

    fun startListening(interruptTask: Boolean = true) {
        if (_state.value.phase == Phase.LISTENING) { g.voice.stopListening(); setPhase(Phase.IDLE); return }
        if (interruptTask && job?.isActive == true) stop()
        g.voice.stopSpeaking()
        _state.update { it.copy(phase = Phase.LISTENING, partialTranscript = "", error = null) }
        g.voice.listen(
            onPartial = { p -> _state.update { it.copy(partialTranscript = p) } },
            onFinal = { text ->
                _state.update { it.copy(partialTranscript = text) }
                if (text.isBlank()) _state.update { it.copy(phase = Phase.IDLE) } else submit(text, fromVoice = true)
            },
            onError = { msg ->
                _state.update { it.copy(phase = if (msg == null) Phase.IDLE else Phase.ERROR, error = msg, partialTranscript = "") }
                if (msg != null) g.scope.launch {
                    kotlinx.coroutines.delay(5000)
                    if (_state.value.phase == Phase.ERROR) _state.update { it.copy(phase = Phase.IDLE) }
                }
            },
        )
    }

    fun setPhase(p: Phase) = _state.update { it.copy(phase = p) }

    // ---------------- Main loop ----------------

    private suspend fun run(text: String) {
        _state.value = AssistantState(phase = Phase.THINKING, request = text, taskActive = true)
        currentRequest = text
        g.usage.startTask()
        UiFeedback.status(null)
        val cid = ensureConversation(text)
        saveMessage(MessageEntity(conversationId = cid, role = "user", content = Redactor.redact(text)))

        val settings = g.settings.current
        val configured = settings.provider.isComplete && g.keys.hasApiKey()
        if (!configured) {
            localFallback(cid, text, "Azure isn't configured yet, so I'm in local mode.")
            return
        }

        val messages = ArrayList<JsonObject>()
        messages += Msg.system(systemPrompt())
        conversationSummary(cid)?.takeIf { it.isNotBlank() }?.let { summary ->
            messages += Msg.user("Summary of earlier in this conversation (for context; the newest messages follow):\n$summary")
        }
        priorMessages(cid, 17).dropLast(1).forEach { m ->
            messages += if (m.role == "user") Msg.user(m.content) else Msg.assistant(m.content)
        }
        // Phone state (time, foreground app, device, memories) goes after the request, so the
        // system prompt and everything before it stays byte-identical and cacheable.
        messages += Msg.user(text + "\n\n<phone_state>\n" + stateBlock() + "\n</phone_state>")
        val promptCacheKey = "nyro-conv-$cid"

        var taskId: Long? = null
        val log = StringBuilder()
        var stepNo = 0
        val tts = g.voice.streamingSpeaker(enabled = settings.speakReplies)
        var resumedNote = false
        var lastBatchWasScreen = false
        var noChangeStreak = 0

        try {
            for (iteration in 0 until MAX_ITERATIONS) {
                if (awaitIfPaused()) resumedNote = true
                if (resumedNote) {
                    messages += Msg.user("[NYRO was paused by the user. Re-read the screen before the next step; it may have changed.]")
                    resumedNote = false
                }
                _state.update { it.copy(phase = Phase.THINKING, reply = "") }
                compactIfNeeded(messages, log)
                recordSent(messages)
                UiFeedback.status(null)
                g.usage.turnStarted()
                val onDelta = { d: String ->
                    _state.update { it.copy(reply = it.reply + d) }
                    g.usage.onDelta(d)
                    tts.push(d)
                }
                // One compressed, numbered screenshot per step that acts on the screen.
                if (lastBatchWasScreen) screenshotForStep()?.let { messages += it }
                val modelStart = System.currentTimeMillis()
                val turn = try {
                    g.ai.chat(settings.provider, messages, g.tools.schemas, onDelta = onDelta, cacheKey = promptCacheKey)
                } catch (e: AiException) {
                    val imgs = messages.indices.filter { messages[it]["content"] is JsonArray }
                    if (e.kind != AiErrorKind.CAPABILITY && e.kind != AiErrorKind.BAD_REQUEST || imgs.isEmpty()) throw e
                    // The deployment rejected image input: continue with accessibility data only.
                    imgs.forEach { messages[it] = Msg.user("[Screenshot could not be sent: this deployment does not accept images. Use read_screen instead, or configure a vision deployment.]") }
                    g.ai.chat(settings.provider, messages, g.tools.schemas, onDelta = onDelta, cacheKey = promptCacheKey)
                }
                val modelMs = System.currentTimeMillis() - modelStart
                tts.flush()
                g.usage.turnFinished(turn.usage)
                // Screenshots are used by the call that follows them; drop the image so later calls don't pay for it again.
                for (i in messages.indices) if (messages[i]["content"] is JsonArray) {
                    messages[i] = Msg.user("[Screenshot shared earlier. Its numbered elements are described in the step results.]")
                }
                turn.usage?.let { u ->
                    log.append("  model %.1fs · input %d (cached %d, written %d) · output %d\n".format(modelMs / 1000.0, u.input, u.cached, u.cacheWrite, u.output))
                }

                if (turn.toolCalls.isEmpty()) {
                    val reply = turn.content.trim().ifEmpty { if (stepNo > 0) "Done." else "…" }
                    _state.update { it.copy(reply = reply) }
                    saveMessage(MessageEntity(conversationId = cid, role = "assistant", content = Redactor.redact(reply)))
                    flashMood(if (_state.value.steps.any { it.status == StepStatus.FAILED }) "confused" else "happy")
                    taskId?.let { g.dao.finishTask(it, "completed", reply.take(400), log.toString()) }
                    finish(Phase.IDLE)
                    tts.awaitDone()
                    maybeContinueListening()
                    return
                }

                if (taskId == null) taskId = saveTask(TaskEntity(request = text, status = "running"))
                messages += Msg.assistantToolCalls(turn.content, turn.toolCalls, turn.rawItems)
                val images = ArrayList<JsonObject>()
                for (call in turn.toolCalls) {
                    if (awaitIfPaused()) resumedNote = true
                    val toolStart = System.currentTimeMillis()
                    val result = execute(call, ++stepNo, log)
                    log.append("  tool ${System.currentTimeMillis() - toolStart} ms\n")
                    messages += Msg.tool(call.id, result.text.take(MAX_TOOL_CHARS))
                    if (result.text.contains("Screen changed: NO")) noChangeStreak++ else if (result.ok) noChangeStreak = 0
                    result.imageJpeg?.let { images += Msg.userWithImage("Screenshot for tool call ${call.id}.", it) }
                }
                lastBatchWasScreen = turn.toolCalls.any { c -> g.tools.get(c.name)?.let { it.touchesScreen || it.name == "open_app" } == true }
                if (noChangeStreak >= 3) {
                    messages += Msg.user("[NYRO stopped repeating actions that did not change the screen. Say what is blocking you and ask the user what to do.]")
                    noChangeStreak = 0
                }
                messages.addAll(images)
            }
            val msg = "I stopped after $MAX_ITERATIONS steps without finishing. Tell me how you'd like to continue."
            _state.update { it.copy(reply = msg) }
            saveMessage(MessageEntity(conversationId = cid, role = "assistant", content = msg))
            taskId?.let { g.dao.finishTask(it, "failed", msg, log.toString()) }
            g.voice.speak(msg)
            finish(Phase.IDLE)
        } catch (e: CancellationException) {
            tts.cancel()
            g.usage.turnFinished(null)
            withContext(NonCancellable) {
                taskId?.let { g.dao.finishTask(it, "cancelled", "Stopped by user", log.toString()) }
                saveMessage(MessageEntity(conversationId = cid, role = "assistant", content = "(stopped)"))
            }
            UiFeedback.setExecuting(false)
            throw e
        } catch (e: AiException) {
            tts.cancel()
            g.usage.turnFinished(null)
            taskId?.let { g.dao.finishTask(it, "failed", e.message.orEmpty(), log.toString()) }
            val offlineKinds = setOf(AiErrorKind.NETWORK, AiErrorKind.TIMEOUT, AiErrorKind.SERVER, AiErrorKind.RATE_LIMIT, AiErrorKind.QUOTA)
            if (e.kind in offlineKinds && stepNo == 0 && localFallback(cid, text, null, quietIfUnhandled = true)) return
            fail(cid, e.message ?: "AI request failed.")
        } catch (e: Exception) {
            tts.cancel()
            taskId?.let { g.dao.finishTask(it, "failed", e.message.orEmpty(), log.toString()) }
            fail(cid, "Something went wrong: ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}")
        }
    }

    private suspend fun execute(call: ToolCall, stepNo: Int, log: StringBuilder): ToolResult {
        val tool = g.tools.get(call.name)
        if (tool == null) {
            addStep(Step(stepNo, "Unknown tool ${call.name}", StepStatus.FAILED))
            return ToolResult.fail("Unknown tool '${call.name}'.")
        }
        val args = g.tools.parseArgs(call.arguments).getOrElse {
            addStep(Step(stepNo, tool.name, StepStatus.FAILED, "Invalid arguments"))
            return ToolResult.fail("Arguments were not valid JSON.")
        }
        tool.validate(args)?.let { err ->
            addStep(Step(stepNo, tool.describe(args), StepStatus.FAILED, err))
            return ToolResult.fail(err)
        }
        g.tools.unmet(tool)?.let { req ->
            addStep(Step(stepNo, tool.describe(args), StepStatus.FAILED, req.label))
            return ToolResult.fail(req.label)
        }

        val assessment = tool.assess(args)
        val coveredByRequest = com.nyro.companion.tools.SensitiveUi.requestCovers(currentRequest, assessment, tool.name)
        val needsConfirm = assessment.risk == Risk.HIGH ||
            (assessment.risk == Risk.MEDIUM && g.settings.current.confirmMediumRisk && !coveredByRequest)
        val label = tool.describe(args)
        if (needsConfirm) {
            addStep(Step(stepNo, label, StepStatus.RUNNING, "Waiting for your confirmation", assessment.risk))
            val allowed = confirm(assessment.risk, assessment.reason)
            if (!allowed) {
                updateStep(stepNo, StepStatus.DENIED, "You declined")
                log.append("✗ ").append(label).append(" — declined\n")
                return ToolResult("DENIED: The user declined this action (${assessment.reason}). Do not retry it; ask what to do instead.", ok = false)
            }
        } else addStep(Step(stepNo, label, StepStatus.RUNNING, risk = assessment.risk))

        _state.update { it.copy(phase = Phase.EXECUTING) }
        UiFeedback.setExecuting(true)
        if (tool.touchesScreen) UiFeedback.collapseForAction()
        UiFeedback.status(label)
        val firstResult = try {
            withTimeout(TOOL_TIMEOUT_MS) { tool.run(args) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // A slow tool is a tool failure, not a user cancellation.
            ToolResult.fail("${tool.name} timed out after ${TOOL_TIMEOUT_MS / 1000}s.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.fail("${tool.name} crashed: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        }
        val result = withRecovery(tool, firstResult)
        if (result.ok) lastActionLabel = label
        if (result.ok) result.undo?.let { pushUndo(label) { it() } }
        // Verification: a successful action whose screen did not change is flagged, not reported as clean success.
        val detail = if (result.ok) {
            if (result.text.contains("Screen changed: NO")) "Done, but the screen did not visibly change" else ""
        } else result.text.removePrefix("FAILED: ").lineSequence().first().take(140)
        updateStep(stepNo, if (result.ok) StepStatus.DONE else StepStatus.FAILED, detail)
        log.append(if (result.ok) "✓ " else "✗ ").append(label).append('\n')
        return result
    }

    private suspend fun confirm(risk: Risk, text: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        _state.update { it.copy(confirmation = Confirmation(risk, text, d)) }
        UiFeedback.requestAttention()
        g.voice.speak(if (risk == Risk.HIGH) "This needs your approval." else "Should I go ahead?")
        val answer = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { d.await() } ?: false
        _state.update { it.copy(confirmation = null) }
        return answer
    }

    private suspend fun localFallback(cid: Long, text: String, prefix: String?, quietIfUnhandled: Boolean = false): Boolean {
        _state.update { it.copy(localOnly = true, phase = Phase.EXECUTING) }
        val r = runCatching { LocalCommands.handle(text) }.getOrNull()
        if (r == null && quietIfUnhandled) return false
        val reply = when {
            r != null -> r.reply
            prefix != null -> "$prefix Offline I can answer battery, storage, memory, network and device questions, open apps, go home or back, and toggle the flashlight. Configure Azure in Settings for everything else."
            else -> "Azure is unreachable right now, and that request needs it."
        }
        r?.tool?.let { addStep(Step(1, "Local: ${it.replace('_', ' ')}", StepStatus.DONE)) }
        _state.update { it.copy(reply = reply) }
        saveMessage(MessageEntity(conversationId = cid, role = "assistant", content = reply))
        finish(Phase.IDLE)
        if (g.settings.current.speakReplies) g.voice.speak(reply)
        return true
    }

    private suspend fun fail(cid: Long, msg: String) {
        _state.update { it.copy(phase = Phase.ERROR, error = msg, reply = it.reply.ifBlank { msg }, taskActive = false, confirmation = null) }
        UiFeedback.setExecuting(false)
        withContext(NonCancellable) { saveMessage(MessageEntity(conversationId = cid, role = "assistant", content = "⚠ $msg")) }
        if (g.settings.current.speakReplies) g.voice.speak("Sorry, " + msg.substringBefore('.').take(120))
        g.scope.launch {
            kotlinx.coroutines.delay(5000)
            if (_state.value.phase == Phase.ERROR) _state.update { it.copy(phase = Phase.IDLE) }
        }
    }

    private fun finish(phase: Phase) {
        UiFeedback.setExecuting(false)
        UiFeedback.status(null)
        _state.update { it.copy(phase = phase, taskActive = false, confirmation = null) }
    }

    private fun maybeContinueListening() {
        val s = g.settings.current
        if (lastFromVoice && s.continuousConversation && s.speakReplies) g.scope.launch(Dispatchers.Main) { startListening(interruptTask = false) }
    }

    private suspend fun ensureConversation(text: String): Long {
        if (_private.value) { _conversationId.value = PRIVATE_ID; return PRIVATE_ID }
        _conversationId.value?.let { g.dao.touchConversation(it, text.take(60)); return it }
        val id = g.dao.insertConversation(ConversationEntity(title = Redactor.redact(text).take(60)))
        _conversationId.value = id
        return id
    }

    private fun addStep(s: Step) = _state.update { it.copy(steps = it.steps + s) }
    private fun updateStep(id: Int, status: StepStatus, detail: String) =
        _state.update { st -> st.copy(steps = st.steps.map { if (it.id == id) it.copy(status = status, detail = detail) else it }) }

    private fun recordSent(messages: List<JsonObject>) {
        val images = messages.count { it["content"] is JsonArray }
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "$time · ${messages.size} messages, $images with screenshots, about " +
            "%,d tokens before caching".format(estimateTokens(messages))
        _lastSent.value = "Last request to Azure: " + line.substringAfter("· ")
        _sentLog.update { (listOf(line) + it).take(10) }
    }

    /**
     * Continuity for long conversations: messages older than the live window are summarized once
     * into the conversation row, so later requests keep the goals and outcomes without resending
     * every old message. Private sessions have no stored conversation and skip this.
     */
    private suspend fun conversationSummary(cid: Long): String? {
        if (cid < 0 || _private.value) return null
        val conv = g.dao.conversation(cid) ?: return null
        val windowStart = g.dao.recentMessages(cid, 17).firstOrNull()?.id ?: return conv.summary
        val older = g.dao.messagesBetween(cid, conv.summaryUpTo, windowStart)
        if (older.size < 6) return conv.summary
        val updated = runCatching { summarize(conv.summary, older) }.getOrNull() ?: return conv.summary
        g.dao.updateSummary(cid, updated, older.last().id)
        return updated
    }

    private suspend fun summarize(previous: String, msgs: List<MessageEntity>): String {
        val settings = g.settings.current.provider
        val transcript = msgs.joinToString("\n") { "${it.role}: ${it.content.take(400)}" }
        val turn = g.ai.chat(
            settings,
            listOf(
                Msg.system("You compress chat history for an assistant that controls a phone. Keep facts, goals, outcomes, names and user preferences. Drop small talk."),
                Msg.user("Previous summary:\n${previous.ifBlank { "(none)" }}\n\nNew messages:\n$transcript\n\nWrite the updated summary in under 200 words."),
            ),
            maxTokens = 400,
        )
        return turn.content.trim().take(1500)
    }

    /** One compressed screenshot with numbered boxes on the elements of the latest screen result. */
    private suspend fun screenshotForStep(): JsonObject? {
        if (!g.settings.current.allowScreenshots) return null
        if (NyroAccessibilityService.current == null) return null
        val snap = ScreenSession.last ?: return null
        val raw = captureScreen() ?: return null
        val enc = encode(annotate(raw, snap), maxDim = 720, quality = 50)
        ScreenSession.shotScale = enc.scale
        return Msg.userWithImage("Current screen. Numbered boxes are the elements in the latest screen result. Act on a box by its number with tap_element.", enc.base64)
    }

    /** Personal commands: a typed shortcut that exactly matches a saved trigger is replaced by its instruction. */
    private fun expandCommand(text: String): String {
        val hit = g.settings.current.commands.lines().mapNotNull { line ->
            line.split('\t', limit = 2).takeIf { it.size == 2 }
        }.firstOrNull { it[0].trim().equals(text, ignoreCase = true) }
        return hit?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: text
    }

    private fun flashMood(v: String) {
        _mood.value = v
        g.scope.launch { delay(3000); if (_mood.value == v) _mood.value = null }
    }

    private fun pushUndo(label: String, action: suspend () -> Unit) {
        undoStack += label to action
        _undoLabel.value = label
    }

    /** Reverses the most recent reversible action. Taps and sent messages can't be reversed. */
    suspend fun undoLast(): String {
        val (label, action) = undoStack.removeLastOrNull() ?: return "Nothing to undo."
        _undoLabel.value = undoStack.lastOrNull()?.first
        return try {
            action()
            "Undone: $label."
        } catch (e: Exception) {
            "Could not undo $label: ${e.message ?: e.javaClass.simpleName}."
        }
    }

    /** After a screen action fails because the element moved, re-read the screen so the model gets current ids. */
    private suspend fun withRecovery(tool: Tool, r: ToolResult): ToolResult {
        if (r.ok || !tool.touchesScreen || !r.text.contains("no longer on screen")) return r
        val svc = NyroAccessibilityService.current ?: return r
        val fresh = ScreenSession.read(svc).render(220)
        return ToolResult(r.text + "\nRecovery: the screen was re-read. Use these current element ids:\n" + fresh)
    }

    /** Rough token count: about 4 characters per token, about 1000 per image, plus encrypted reasoning. */
    private fun estimateTokens(messages: List<JsonObject>): Int = messages.sumOf { m ->
        val content = when (val c = m["content"]) {
            is JsonPrimitive -> (c.contentOrNull?.length ?: 0) / 4
            is JsonArray -> c.sumOf { part ->
                val o = part.jsonObject
                if ((o["type"] as? JsonPrimitive)?.contentOrNull == "image_url") 1000
                else ((o["text"] as? JsonPrimitive)?.contentOrNull?.length ?: 0) / 4
            }
            else -> 0
        }
        content + ((m["tool_calls"]?.toString()?.length ?: 0) + (m[RAW_ITEMS_KEY]?.toString()?.length ?: 0)) / 4
    }

    /**
     * Prompt caching needs the earlier messages to stay identical, so history is left alone until
     * it reaches [COMPACT_AT_TOKENS]. Then the middle of the task is replaced with a summary built
     * from the step log (no extra model call). The newest exchanges stay verbatim, and the cut never
     * separates a tool call from its output.
     */
    private fun compactIfNeeded(messages: MutableList<JsonObject>, log: StringBuilder) {
        if (estimateTokens(messages) < COMPACT_AT_TOKENS || messages.size < KEEP_TAIL_MESSAGES + 4) return
        var cut = messages.size - KEEP_TAIL_MESSAGES
        while (cut < messages.size && (messages[cut]["role"] as? JsonPrimitive)?.contentOrNull == "tool") cut++
        if (cut <= 2 || cut >= messages.size) return
        val summary = "Earlier in this task (summarized to fit the context budget). Steps taken:\n" +
            log.toString().takeLast(MAX_SUMMARY_CHARS)
        val head = messages.subList(0, 2).toList()
        val tail = messages.subList(cut, messages.size).toList()
        messages.clear()
        messages.addAll(head)
        messages += Msg.user(summary)
        messages.addAll(tail)
    }

    /** The user's standing instructions, added to the static prefix (changes rarely, so the cache holds). */
    private fun instructionsBlock(): String {
        val t = g.settings.current.customInstructions.trim()
        return if (t.isEmpty()) "" else "\n\nThe user's standing instructions (follow them unless they conflict with safety rules):\n$t"
    }

    /** Phone state for the newest request only: changes every task, so it sits after the cached prefix. */
    private suspend fun stateBlock(): String {
        val memories = g.dao.memoryList(40)
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date())
        val fg = g.accessibility()?.let { svc -> ScreenSession.read(svc).let { "${it.appLabel} (${it.packageName})" } } ?: "unknown (accessibility off)"
        val caps = buildList {
            add(if (g.accessibility() != null) "phone control ON" else "phone control OFF (screen tools will fail; tell the user to enable NYRO Phone Control)")
            add(if (g.settings.current.allowScreenshots) "screenshots allowed" else "screenshots disabled by user")
        }.joinToString(", ")
        return buildString {
            append("Now: ").append(now).append('\n')
            append("Phone: ").append(g.device.promptSummary()).append('\n')
            append("Foreground app: ").append(fg).append('\n')
            lastActionLabel?.let { append("Last action: ").append(it).append('\n') }
            append("Capabilities: ").append(caps).append('\n')
            if (memories.isNotEmpty()) {
                append("Local memory about the user:\n")
                memories.forEach { append("- ").append(it.content).append('\n') }
            }
        }.trimEnd()
    }

    /** Static instructions only: identical on every request so the cache prefix holds. */
    private fun systemPrompt(): String {
        return """
You are NYRO, a personal AI companion that lives on the user's Android phone and operates it for them. Personality: calm, warm, quick, quietly confident. You speak like a capable human assistant, not a chatbot.


How you work:
- You act through tools that run locally on the phone. Observe → act → observe. After every action, check the returned screen state to verify it worked before moving on. Never claim something happened unless a tool result confirms it.
- For multi-step tasks: open the app (open_app), read the screen, then use element ids with tap_element / type_text / scroll. Prefer element ids over coordinates. If the accessibility tree lacks what you need (images, video, games, canvas UIs), use take_screenshot.
- To search inside an app: tap its search control, type_text with submit=true, then confirm results appeared.
- To send a message (WhatsApp, SMS, etc.): open the chat, type_text into the message box WITHOUT submit, then tap the Send button (usually desc "Send"). Done only when the message appears in the conversation and the box is empty.
- The phone state block at the end of the user's message holds the current time, foreground app and memories. Use it; don't ask for it.
- Never ask in text whether to go ahead with an action ("shall I go ahead?", "want me to continue?"). Call the tool instead. If an action needs approval, the phone shows an approval card by itself. Ask in text only for information you don't have.
- Before tapping, find the target in the latest screenshot and its number in the element list, then tap by number with tap_element. Don't tap coordinates you estimated.
- If an action doesn't change the screen, don't repeat it. Try another element, scroll, or go back.
- Work fast: don't call read_screen right after an action; action results already include the new screen.
- If a step fails, try a sensible alternative (scroll, go back, re-read, search) up to a few times, then explain what blocked you.
- The phone's safety layer may ask the user to confirm risky actions; if a tool returns DENIED, stop that path and ask the user.
- Never enter passwords, OTPs or payment details, never bypass lock screens, biometrics or permission dialogs that require the user. Ask the user to do those steps.
- Device questions (battery, storage, RAM, sensors, network) must be answered from tools, not guesses.
- Use remember only for lasting preferences/facts the user shares; never for secrets.
- Reply length: ${when (g.settings.current.responseLength) { "short" -> "one short sentence unless the user asks for more"; "detailed" -> "explain the key details clearly, up to a short paragraph"; else -> "1–3 short sentences" }}. No markdown, no lists unless asked. While working, a brief progress phrase before tool calls is fine (e.g. "Opening YouTube.").
- Reply in the same language the user speaks or writes in (English, Hindi, Telugu or another language).
- Follow-ups: "do that again", "cancel it" or "the second one" refer to the last action and the recent screen in the phone state block.
${instructionsBlock()}""".trim()
    }

    companion object {
        const val MAX_ITERATIONS = 30
        const val PRIVATE_ID = -1L
        const val MAX_TOOL_CHARS = 9000
        const val TOOL_TIMEOUT_MS = 45_000L
        const val CONFIRM_TIMEOUT_MS = 90_000L
        /** Working budget for one task's prompt. Compaction starts at 90% of it, far below the model's 1M limit. */
        const val WORKING_BUDGET_TOKENS = 120_000
        const val COMPACT_AT_TOKENS = WORKING_BUDGET_TOKENS * 9 / 10
        const val KEEP_TAIL_MESSAGES = 12
        const val MAX_SUMMARY_CHARS = 6000
    }
}
