package com.nyro.companion.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.content.ContextCompat
import com.nyro.companion.data.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speech in: Android SpeechRecognizer, on-device when the device supports it. The network
 * recognizer is only used if the user explicitly enabled the fallback in Settings.
 * Speech out: Android TextToSpeech. Only recognized text ever reaches the AI provider.
 */
class VoiceEngine(private val context: Context, private val settings: SettingsRepository) {
    private val main = Handler(Looper.getMainLooper())

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking
    private val _micLevel = MutableStateFlow(0f)
    /** 0..1 microphone level while listening, for the orb animation. */
    val micLevel: StateFlow<Float> = _micLevel
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening

    // ---------------- TTS ----------------

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private val pending = AtomicInteger(0)

    @Volatile private var firstUtterance: String? = null

    /** TTS is bound lazily on first use so Normal Mode doesn't keep a speech engine alive. */
    fun warmUp() = initTts()

    @Synchronized
    private fun initTts() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { _speaking.value = true }
                override fun onDone(id: String?) { if (pending.decrementAndGet() <= 0) { pending.set(0); _speaking.value = false } }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { onDone(id) }
                override fun onStop(id: String?, interrupted: Boolean) { pending.set(0); _speaking.value = false }
            })
            firstUtterance?.let { u -> firstUtterance = null; if (ttsReady) main.post { speak(u) } }
        }
    }

    fun voices(): List<Voice> = runCatching { initTts(); tts?.voices?.filter { !it.isNetworkConnectionRequired }?.sortedBy { it.name } }.getOrNull().orEmpty()

    private fun applyVoiceSettings() {
        val s = settings.current
        val t = tts ?: return
        t.setSpeechRate(s.speechRate)
        t.setPitch(s.speechPitch)
        if (s.voiceName.isNotBlank()) voices().firstOrNull { it.name == s.voiceName }?.let { t.voice = it }
    }

    fun speak(text: String, queue: Boolean = false) {
        if (!settings.current.speakReplies) return
        val clean = cleanForSpeech(text)
        if (clean.isBlank()) return
        if (!ttsReady) { initTts(); firstUtterance = (firstUtterance?.let { "$it " } ?: "") + clean; return }
        applyVoiceSettings()
        pending.incrementAndGet()
        _speaking.value = true
        tts?.speak(clean, if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun stopSpeaking() {
        firstUtterance = null
        tts?.stop(); pending.set(0); _speaking.value = false
    }

    suspend fun awaitSpeechDone(timeoutMs: Long = 60_000) {
        withTimeoutOrNull(timeoutMs) { while (_speaking.value) delay(120) }
    }

    /** Speaks streamed model text sentence by sentence, so replies start playing before they finish. */
    inner class StreamingSpeaker(private val enabled: Boolean) {
        private val buf = StringBuilder()
        private var spokeAny = false
        fun push(delta: String) {
            if (!enabled) return
            buf.append(delta)
            while (true) {
                val idx = sentenceEnd(buf) ?: break
                val sentence = buf.substring(0, idx + 1)
                buf.delete(0, idx + 1)
                emit(sentence)
            }
        }
        fun flush() { if (enabled && buf.isNotBlank()) { emit(buf.toString()); buf.clear() } }
        private fun emit(s: String) { if (s.isNotBlank()) { speak(s, queue = spokeAny); spokeAny = true } }
        fun cancel() { buf.clear(); if (spokeAny) stopSpeaking() }
        suspend fun awaitDone() { if (spokeAny) awaitSpeechDone() }

        private fun sentenceEnd(b: StringBuilder): Int? {
            for (i in 0 until b.length - 1) {
                val c = b[i]
                if ((c == '.' || c == '!' || c == '?' || c == '\n') && b[i + 1].isWhitespace() && i > 12) return i
            }
            return null
        }
    }

    fun streamingSpeaker(enabled: Boolean) = StreamingSpeaker(enabled)

    private fun cleanForSpeech(t: String) = t
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("[*_#`>|]"), "")
        .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")
        .replace(Regex("https?://\\S+"), "link")
        .trim()

    // ---------------- STT ----------------

    private var recognizer: SpeechRecognizer? = null

    enum class RecognizerKind { ON_DEVICE, NETWORK_FALLBACK, UNAVAILABLE }

    fun recognizerKind(): RecognizerKind = when {
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context) -> RecognizerKind.ON_DEVICE
        settings.current.allowNetworkRecognizer && SpeechRecognizer.isRecognitionAvailable(context) -> RecognizerKind.NETWORK_FALLBACK
        else -> RecognizerKind.UNAVAILABLE
    }

    fun hasMicPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Must be called on the main thread. [onError] receives null for "nothing heard". */
    fun listen(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String?) -> Unit, forceNetwork: Boolean = false) {
        if (!hasMicPermission()) {
            context.startActivity(Intent(context, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            onError("Microphone permission is needed. Allow it and tap again.")
            return
        }
        val kind = if (forceNetwork) RecognizerKind.NETWORK_FALLBACK else recognizerKind()
        if (kind == RecognizerKind.UNAVAILABLE) {
            onError(
                if (SpeechRecognizer.isRecognitionAvailable(context))
                    "On-device speech recognition isn't available on this phone. Enable the speech fallback in Settings → Voice (audio is then processed by the phone's speech service), or type instead."
                else "No speech recognition service is installed. Install or enable Google speech services, or type instead."
            )
            return
        }
        stopListening()
        stopSpeaking()
        val rec = if (kind == RecognizerKind.ON_DEVICE) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = rec
        val lang = settings.current.recognitionLanguage.ifBlank { Locale.getDefault().toLanguageTag() }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        var delivered = false
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { _listening.value = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { _micLevel.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { _micLevel.value = 0f }
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }
            override fun onResults(b: Bundle?) {
                if (delivered) return; delivered = true
                finishListening()
                onFinal(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            }
            override fun onError(error: Int) {
                if (delivered) return; delivered = true
                finishListening()
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onError(null)
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> {
                        if (kind == RecognizerKind.ON_DEVICE) triggerModelDownload(intent)
                        if (kind == RecognizerKind.ON_DEVICE && settings.current.allowNetworkRecognizer && SpeechRecognizer.isRecognitionAvailable(context)) {
                            main.post { listen(onPartial, onFinal, onError, forceNetwork = true) }
                            return
                        }
                        onError("The on-device speech model for $lang isn't installed. NYRO asked Android to download it; try again shortly.")
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> onError("Microphone permission was denied.")
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> onError("The speech recognizer is busy. Try again.")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> onError("Speech recognition needs a network connection on this recognizer.")
                    SpeechRecognizer.ERROR_AUDIO -> onError("Microphone audio error. Another app may be using the mic.")
                    else -> onError("Speech recognition error ($error).")
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        rec.startListening(intent)
    }

    private fun triggerModelDownload(intent: Intent) {
        runCatching {
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            r.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(s: RecognitionSupport) {
                    r.triggerModelDownload(intent); main.postDelayed({ r.destroy() }, 2000)
                }
                override fun onError(error: Int) { r.destroy() }
            })
        }
    }

    private fun finishListening() {
        _listening.value = false; _micLevel.value = 0f
        main.post { recognizer?.destroy(); recognizer = null }
    }

    fun stopListening() {
        val r = recognizer ?: return
        main.post { runCatching { r.cancel(); r.destroy() } }
        recognizer = null
        _listening.value = false; _micLevel.value = 0f
    }

    fun shutdown() { stopListening(); tts?.shutdown(); tts = null }
}

/** Invisible trampoline so the overlay can trigger the RECORD_AUDIO runtime prompt. */
class MicPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Now that the mic is granted (and we are in the foreground), let NYRO Mode hold the mic FGS type.
        runCatching { com.nyro.companion.overlay.NyroModeService.instance?.refreshForeground() }
        finish()
    }
}
