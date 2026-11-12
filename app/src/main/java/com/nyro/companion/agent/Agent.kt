package com.nyro.companion.agent

import com.nyro.companion.ai.AiErrorKind
import com.nyro.companion.ai.AiException
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
import com.nyro.companion.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    @Volatile private var currentRequest = ""
    private var lastFromVoice = false

    fun newConversation() { stop(); _conversationId.value = null; _state.value = AssistantState() }
    fun openConversation(id: Long) { stop(); _conversationId.value = id; _state.value = AssistantState() }

    fun submit(text: String, fromVoice: Boolean = false) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        job?.cancel()
        g.voice.stopSpeaking()
        lastFromVoice = fromVoice
        job = g.scope.launch(Dispatchers.Default) { run(clean) }
    }

    /** Emergency stop: cancels the task, speech and listening immediately. */
    fun stop() {
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
        g.dao.insertMessage(MessageEntity(conversationId = cid, role = "user", content = Redactor.redact(text)))

        val settings = g.settings.current
        val configured = settings.provider.isComplete && g.keys.hasApiKey()
        if (!configured) {
            localFallback(cid, text, "Azure isn't configured yet, so I'm in local mode.")
            return
        }

        val messages = ArrayList<JsonObject>()
        messages += Msg.system(systemPrompt())
        g.dao.recentMessages(cid, 17).dropLast(1).forEach { m ->
            messages += if (m.role == "user") Msg.user(m.content) else Msg.assistant(m.content)
        }
        messages += Msg.user(text)

        var taskId: Long? = null
        val log = StringBuilder()
        var stepNo = 0
        val tts = g.voice.streamingSpeaker(enabled = settings.speakReplies)

        try {
            for (iteration in 0 until MAX_ITERATIONS) {
                _state.update { it.copy(phase = Phase.THINKING, reply = "") }
                compact(messages)
                UiFeedback.status(null)
                g.usage.turnStarted()
                val onDelta = { d: String ->
                    _state.update { it.copy(reply = it.reply + d) }
                    g.usage.onDelta(d)
                    tts.push(d)
                }
                val turn = try {
                    g.ai.chat(settings.provider, messages, g.tools.schemas, onDelta = onDelta)
                } catch (e: AiException) {
                    val imgs = messages.indices.filter { messages[it]["content"] is JsonArray }
                    if (e.kind != AiErrorKind.CAPABILITY && e.kind != AiErrorKind.BAD_REQUEST || imgs.isEmpty()) throw e
                    // The deployment rejected image input: continue with accessibility data only.
                    imgs.forEach { messages[it] = Msg.user("[Screenshot could not be sent: this deployment does not accept images. Use read_screen instead, or configure a vision deployment.]") }
                    g.ai.chat(settings.provider, messages, g.tools.schemas, onDelta = onDelta)
                }
                tts.flush()
                g.usage.turnFinished(turn.usage)

                if (turn.toolCalls.isEmpty()) {
                    val reply = turn.content.trim().ifEmpty { if (stepNo > 0) "Done." else "…" }
                    _state.update { it.copy(reply = reply) }
                    g.dao.insertMessage(MessageEntity(conversationId = cid, role = "assistant", content = Redactor.redact(reply)))
                    taskId?.let { g.dao.finishTask(it, "completed", reply.take(400), log.toString()) }
                    finish(Phase.IDLE)
                    tts.awaitDone()
                    maybeContinueListening()
                    return
                }

                if (taskId == null) taskId = g.dao.insertTask(TaskEntity(request = text, status = "running"))
                messages += Msg.assistantToolCalls(turn.content, turn.toolCalls, turn.rawItems)
                val images = ArrayList<JsonObject>()
                for (call in turn.toolCalls) {
                    val result = execute(call, ++stepNo, log)
                    messages += Msg.tool(call.id, result.text.take(MAX_TOOL_CHARS))
                    result.imageJpeg?.let { images += Msg.userWithImage("Screenshot for tool call ${call.id}.", it) }
                }
                messages.addAll(images)
            }
            val msg = "I stopped after $MAX_ITERATIONS steps without finishing. Tell me how you'd like to continue."
            _state.update { it.copy(reply = msg) }
            g.dao.insertMessage(MessageEntity(conversationId = cid, role = "assistant", content = msg))
            taskId?.let { g.dao.finishTask(it, "failed", msg, log.toString()) }
            g.voice.speak(msg)
            finish(Phase.IDLE)
        } catch (e: CancellationException) {
            tts.cancel()
            g.usage.turnFinished(null)
            withContext(NonCancellable) {
                taskId?.let { g.dao.finishTask(it, "cancelled", "Stopped by user", log.toString()) }
                g.dao.insertMessage(MessageEntity(conversationId = cid, role = "assistant", content = "(stopped)"))
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
        val result = try {
            withTimeout(TOOL_TIMEOUT_MS) { tool.run(args) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // A slow tool is a tool failure, not a user cancellation.
            ToolResult.fail("${tool.name} timed out after ${TOOL_TIMEOUT_MS / 1000}s.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.fail("${tool.name} crashed: ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        }
        updateStep(stepNo, if (result.ok) StepStatus.DONE else StepStatus.FAILED, if (result.ok) "" else result.text.removePrefix("FAILED: ").lineSequence().first().take(140))
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
        g.dao.insertMessage(MessageEntity(conversationId = cid, role = "assistant", content = reply))
        finish(Phase.IDLE)
        if (g.settings.current.speakReplies) g.voice.speak(reply)
        return true
    }

    private suspend fun fail(cid: Long, msg: String) {
        _state.update { it.copy(phase = Phase.ERROR, error = msg, reply = it.reply.ifBlank { msg }, taskActive = false, confirmation = null) }
        UiFeedback.setExecuting(false)
        withContext(NonCancellable) { g.dao.insertMessage(MessageEntity(conversationId = cid, role = "assistant", content = "⚠ $msg")) }
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
        _conversationId.value?.let { g.dao.touchConversation(it, text.take(60)); return it }
        val id = g.dao.insertConversation(ConversationEntity(title = Redactor.redact(text).take(60)))
        _conversationId.value = id
        return id
    }

    private fun addStep(s: Step) = _state.update { it.copy(steps = it.steps + s) }
    private fun updateStep(id: Int, status: StepStatus, detail: String) =
        _state.update { st -> st.copy(steps = st.steps.map { if (it.id == id) it.copy(status = status, detail = detail) else it }) }

    /** Keeps the request small: only the newest screenshot stays, older screen dumps are shortened. */
    private fun compact(messages: MutableList<JsonObject>) {
        val imageIdx = messages.indices.filter { messages[it]["content"] is JsonArray }
        imageIdx.dropLast(1).forEach { i -> messages[i] = Msg.user("[earlier screenshot removed]") }
        val toolIdx = messages.indices.filter { (messages[it]["role"] as? JsonPrimitive)?.contentOrNull == "tool" }
        toolIdx.dropLast(4).forEach { i ->
            val m = messages[i]
            val c = (m["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (c.length > 700) messages[i] = buildJsonObject {
                put("role", "tool"); put("tool_call_id", (m["tool_call_id"] as JsonPrimitive).content)
                put("content", c.take(600) + "\n…[older output truncated]")
            }
        }
    }

    private suspend fun systemPrompt(): String {
        val memories = g.dao.memoryList(40)
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date())
        val fg = g.accessibility()?.let { svc -> ScreenSession.read(svc).let { "${it.appLabel} (${it.packageName})" } } ?: "unknown (accessibility off)"
        val caps = buildList {
            add(if (g.accessibility() != null) "phone control ON" else "phone control OFF (screen tools will fail; tell the user to enable NYRO Phone Control)")
            add(if (g.settings.current.allowScreenshots) "screenshots allowed" else "screenshots disabled by user")
        }.joinToString(", ")
        return """
You are NYRO, a personal AI companion that lives on the user's Android phone and operates it for them. Personality: calm, warm, quick, quietly confident. You speak like a capable human assistant, not a chatbot.

Phone: ${g.device.promptSummary()}. Now: $now. Foreground app: $fg. Capabilities: $caps.

How you work:
- You act through tools that run locally on the phone. Observe → act → observe. After every action, check the returned screen state to verify it worked before moving on. Never claim something happened unless a tool result confirms it.
- For multi-step tasks: open the app (open_app), read the screen, then use element ids with tap_element / type_text / scroll. Prefer element ids over coordinates. If the accessibility tree lacks what you need (images, video, games, canvas UIs), use take_screenshot.
- To search inside an app: tap its search control, type_text with submit=true, then confirm results appeared.
- To send a message (WhatsApp, SMS, etc.): open the chat, type_text into the message box WITHOUT submit, then tap the Send button (usually desc "Send"). Done only when the message appears in the conversation and the box is empty.
- Work fast: don't call read_screen right after an action; action results already include the new screen.
- If a step fails, try a sensible alternative (scroll, go back, re-read, search) up to a few times, then explain what blocked you.
- The phone's safety layer may ask the user to confirm risky actions; if a tool returns DENIED, stop that path and ask the user.
- Never enter passwords, OTPs or payment details, never bypass lock screens, biometrics or permission dialogs that require the user. Ask the user to do those steps.
- Device questions (battery, storage, RAM, sensors, network) must be answered from tools, not guesses.
- Use remember only for lasting preferences/facts the user shares; never for secrets.
- Replies are often spoken aloud: keep them short and natural (1–3 sentences), no markdown, no lists unless asked. While working, a brief progress phrase before tool calls is fine (e.g. "Opening YouTube.").
${if (memories.isNotEmpty()) "\nWhat you remember about the user (local memory):\n" + memories.joinToString("\n") { "- ${it.content}" } else ""}
""".trim()
    }

    companion object {
        const val MAX_ITERATIONS = 30
        const val MAX_TOOL_CHARS = 9000
        const val TOOL_TIMEOUT_MS = 45_000L
        const val CONFIRM_TIMEOUT_MS = 90_000L
    }
}
