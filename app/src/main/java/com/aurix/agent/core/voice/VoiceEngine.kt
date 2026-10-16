package com.aurix.agent.core.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class VoiceException(message: String) : Exception(message)
enum class VoicePhase { IDLE, LISTENING, THINKING, SPEAKING }

/** Speech-to-text + text-to-speech on top of Android's built-in recognizer/TTS. One microphone user at a time. */
@Singleton
class VoiceEngine @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val settings: com.aurix.agent.core.approval.AgentSettings,
    private val http: okhttp3.OkHttpClient,
    private val contacts: com.aurix.agent.core.tools.device.ContactResolver,
) {
    private val _phase = MutableStateFlow(VoicePhase.IDLE)
    val phase: StateFlow<VoicePhase> = _phase.asStateFlow()
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    /** True while the voice screen is open: the wake-word service pauses itself. */
    @Volatile var uiActive = false
    private val main = Handler(Looper.getMainLooper())
    private val micLock = Mutex()
    @Volatile private var interrupt: (() -> Unit)? = null
    private var tts: TextToSpeech? = null

    fun setPhase(p: VoicePhase) { _phase.value = p }
    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(ctx)
    fun interruptWake() { interrupt?.let { main.post(it) } }

    private fun muteEarcons(mute: Boolean) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val dir = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
            for (s in intArrayOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) am.adjustStreamVolume(s, dir, 0)
        } catch (e: Exception) { /* not allowed on this device; the recognizer beep stays */ }
    }

    /**
     * Listens once. Returns the recognised text or null if nothing was understood.
     * With [wakeCheck] it returns as soon as a partial result satisfies the check (used for "Hey AURIX").
     */
    suspend fun listen(timeoutMs: Long = 9_000, wakeCheck: ((String) -> Boolean)? = null): String? = micLock.withLock {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw VoiceException("Microphone permission is off")
        if (!available()) throw VoiceException("Speech recognition is not available on this phone (enable the Google app / speech service)")
        val bias = if (wakeCheck == null && android.os.Build.VERSION.SDK_INT >= 33) biasList() else emptyList()
        withContext(Dispatchers.Main) {
            _partial.value = ""; _level.value = 0f
            suspendCancellableCoroutine<String?> { cont ->
                val rec = SpeechRecognizer.createSpeechRecognizer(ctx)
                var done = false
                var timeoutTask: Runnable? = null
                fun finish(t: String?, err: VoiceException? = null) {
                    if (done) return
                    done = true
                    timeoutTask?.let { main.removeCallbacks(it) }
                    interrupt = null
                    releaseListenFocus()
                    try { rec.destroy() } catch (e: Exception) { }
                    _level.value = 0f
                    if (!cont.isActive) return
                    if (err != null) cont.resumeWithException(err) else cont.resume(t)
                }
                timeoutTask = Runnable { finish(_partial.value.ifBlank { null }) }
                if (wakeCheck != null) interrupt = { finish(null) }
                cont.invokeOnCancellation {
                    main.post {
                        if (!done) { done = true; try { rec.cancel(); rec.destroy() } catch (e: Exception) { } }
                        interrupt = null
                    }
                }
                rec.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) { _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onPartialResults(partialResults: Bundle?) {
                        val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        if (t.isNotBlank()) _partial.value = t
                        if (wakeCheck != null && t.isNotBlank() && wakeCheck(t)) finish(t)
                    }
                    override fun onResults(results: Bundle?) {
                        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        finish(if (wakeCheck != null) list.firstOrNull { wakeCheck(it) } ?: list.firstOrNull() else list.firstOrNull())
                    }
                    override fun onError(error: Int) {
                        when (error) {
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> finish(null, VoiceException("Microphone permission is off"))
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                                finish(null, VoiceException("Speech recognition needs internet (or an offline language pack)"))
                            else -> finish(_partial.value.ifBlank { null })
                        }
                    }
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (Locale.getDefault().language == "hi") "hi-IN" else "en-IN")
                if (bias.isNotEmpty()) intent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(bias))
                if (wakeCheck == null) { // a command, not the wake-word loop: don't cut the user off mid-sentence, and quiet any music while they talk
                    intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
                        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                    requestListenFocus()
                }
                if (wakeCheck != null) muteEarcons(true)
                rec.startListening(intent)
                if (wakeCheck != null) main.postDelayed({ muteEarcons(false) }, 900)
                main.postDelayed(timeoutTask!!, timeoutMs)
            }
        }
    }

    // ------------------------------------------------------------------ speaking
    private suspend fun ensureTts(): TextToSpeech? {
        tts?.let { return it }
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<TextToSpeech?> { c ->
                var t: TextToSpeech? = null
                t = TextToSpeech(ctx) { status ->
                    if (status == TextToSpeech.SUCCESS) { tts = t; if (c.isActive) c.resume(t) } else if (c.isActive) c.resume(null)
                }
            }
        }
    }

    @Volatile private var player: android.media.MediaPlayer? = null
    fun stopSpeaking() { main.post { try { tts?.stop() } catch (e: Exception) { }; try { player?.stop() } catch (e: Exception) { } } }

    // ------------------------------------------------------------------ audio focus + recogniser bias
    private val audioManager get() = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private fun duckRequest() = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANT).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setOnAudioFocusChangeListener { }.build()
    @Volatile private var listenFocus: android.media.AudioFocusRequest? = null
    private fun requestListenFocus() { try { releaseListenFocus(); duckRequest().also { listenFocus = it; audioManager.requestAudioFocus(it) } } catch (e: Exception) { } }
    private fun releaseListenFocus() { try { listenFocus?.let { audioManager.abandonAudioFocusRequest(it) } } catch (e: Exception) { }; listenFocus = null }

    @Volatile private var biasCache: Pair<Long, List<String>>? = null
    private suspend fun biasList(): List<String> {
        biasCache?.let { (t, l) -> if (System.currentTimeMillis() - t < 600_000) return l }
        val l = try { VoiceBias.build(contacts.names()) } catch (e: Exception) { emptyList() }
        biasCache = System.currentTimeMillis() to l
        return l
    }

    // ------------------------------------------------------------------ speaking
    /** Downloads one ElevenLabs clip into [file]. False on any failure. */
    private suspend fun fetchEleven(text: String, file: java.io.File): Boolean = withContext(Dispatchers.IO) {
        val key = settings.elevenKey()
        if (key.isBlank()) return@withContext false
        try {
            val body = org.json.JSONObject().put("text", text).put("model_id", "eleven_multilingual_v2").toString().toRequestBody("application/json".toMediaType())
            val req = okhttp3.Request.Builder()
                .url("https://api.elevenlabs.io/v1/text-to-speech/${settings.elevenVoice()}?output_format=mp3_44100_64")
                .header("xi-api-key", key).header("Accept", "audio/mpeg").post(body).build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use false
                r.body?.byteStream()?.use { inp -> file.outputStream().use { out -> inp.copyTo(out) } }
                file.length() > 500
            }
        } catch (e: Exception) { false }
    }

    private suspend fun playFile(file: java.io.File): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Boolean> { cont ->
            val mp = android.media.MediaPlayer()
            player = mp
            fun end(v: Boolean) { try { mp.release() } catch (e: Exception) { }; if (player === mp) player = null; if (cont.isActive) cont.resume(v) }
            try {
                mp.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANT).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener { end(true) }
                mp.setOnErrorListener { _, _, _ -> end(false); true }
                mp.setOnPreparedListener { it.start() }
                mp.prepareAsync()
            } catch (e: Exception) { end(false) }
            cont.invokeOnCancellation { try { mp.release() } catch (e: Exception) { } }
        }
    }

    /** Speaks sentence by sentence: the next clip downloads while the current one plays, so the first words start after one short sentence, not after the whole reply. Returns how many chunks were spoken. */
    private suspend fun speakEleven(chunks: List<String>): Int {
        if (settings.elevenKey().isBlank() || chunks.isEmpty()) return 0
        val files = listOf(java.io.File(ctx.cacheDir, "eleven_tts_0.mp3"), java.io.File(ctx.cacheDir, "eleven_tts_1.mp3"))
        return kotlinx.coroutines.coroutineScope {
            var pending: kotlinx.coroutines.Deferred<Boolean>? = async { fetchEleven(chunks[0], files[0]) }
            var spoken = 0
            for (i in chunks.indices) {
                if (pending?.await() != true) break
                pending = if (i + 1 < chunks.size) async { fetchEleven(chunks[i + 1], files[(i + 1) % 2]) } else null
                if (!playFile(files[i % 2])) { pending?.cancel(); break }
                spoken++
            }
            spoken
        }
    }

    suspend fun speak(text: String) {
        val clean = speakable(text).take(700)
        if (clean.isBlank()) return
        _phase.value = VoicePhase.SPEAKING
        val am = audioManager
        val focus = duckRequest()
        try { am.requestAudioFocus(focus) } catch (e: Exception) { }
        try {
            val chunks = SpeechChunker.split(clean)
            val done = speakEleven(chunks)
            if (done >= chunks.size) return
            val rest = chunks.drop(done).joinToString(" ")
            val engine = ensureTts() ?: return
            suspendCancellableCoroutine<Unit> { cont ->
                val id = UUID.randomUUID().toString()
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                    override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                })
                engine.setLanguage(if (rest.any { it in '\u0900'..'\u097F' }) Locale("hi", "IN") else Locale("en", "IN"))
                if (engine.speak(rest, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS && cont.isActive) cont.resume(Unit)
                cont.invokeOnCancellation { engine.stop() }
            }
        } finally {
            try { am.abandonAudioFocusRequest(focus) } catch (e: Exception) { }
            if (_phase.value == VoicePhase.SPEAKING) _phase.value = VoicePhase.IDLE
        }
    }
}
