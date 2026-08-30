package space.gexemy.tasteroute.data.local

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import space.gexemy.tasteroute.data.state.AppState

/**
 * The navigation voice.
 */
object Voice {

    private var tts: TextToSpeech? = null
    private var audio: AudioManager? = null
    private var focusRequest: Any? = null
    private var ready = false
    private var lastSpoken = ""
    private var lastSpokenAt = 0L

    var installed: List<VoiceOption> = emptyList()
        private set

    data class VoiceOption(val id: String, val label: String, val offline: Boolean)

    val isReady: Boolean get() = ready

    fun attach(context: Context, onReady: (() -> Unit)? = null) {
        if (tts != null) { onReady?.invoke(); return }
        val app = context.applicationContext
        audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        tts = TextToSpeech(app) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) configure()
            onReady?.invoke()
        }
    }

    private fun configure() {
        val engine = tts ?: return
        engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        val locale = Locale.getDefault()
        if (engine.setLanguage(locale) == TextToSpeech.LANG_MISSING_DATA || engine.setLanguage(locale) == TextToSpeech.LANG_NOT_SUPPORTED) { engine.setLanguage(Locale.US) }
        engine.setSpeechRate(AppState.voiceSpeed)
        engine.setPitch(1.0f)
        installed = runCatching { engine.voices.orEmpty().filter { it.locale.language == engine.voice?.locale?.language || it.locale.language == locale.language }.filterNot { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true }.sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.isNetworkConnectionRequired }).take(8).map { VoiceOption(it.name, prettyName(it), !it.isNetworkConnectionRequired) } }.getOrDefault(emptyList())
        applyVoice(AppState.voiceName)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = abandonFocus()
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onError(utteranceId: String?) = abandonFocus()
            override fun onError(utteranceId: String?, errorCode: Int) = abandonFocus()
        })
    }

    private fun prettyName(voice: android.speech.tts.Voice): String {
        val parts = voice.name.split("-"); val tag = parts.getOrNull(3)?.uppercase(Locale.US) ?: voice.name
        val quality = when { voice.quality >= 500 -> "very high"; voice.quality >= 400 -> "high"; voice.quality >= 300 -> "normal"; else -> "basic" }
        return "Voice $tag · $quality${if (voice.isNetworkConnectionRequired) " · needs data" else ""}"
    }

    fun applyVoice(name: String) {
        val engine = tts ?: return
        val voices = runCatching { engine.voices.orEmpty() }.getOrDefault(emptySet())
        val chosen = voices.firstOrNull { it.name == name } ?: voices.filter { it.locale.language == Locale.getDefault().language }.filterNot { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true }.maxWithOrNull(compareBy<android.speech.tts.Voice> { it.quality }.thenBy { if (it.isNetworkConnectionRequired) 0 else 1 })
        chosen?.let { runCatching { engine.setVoice(it) } }
    }

    fun setSpeed(rate: Float) { tts?.setSpeechRate(rate.coerceIn(0.7f, 1.4f)) }

    fun say(text: String, force: Boolean = false) {
        val engine = tts ?: return
        if (!ready || text.isBlank()) return
        if (!force && !AppState.navVoice) return
        val now = System.currentTimeMillis()
        if (!force && text == lastSpoken && now - lastSpokenAt < REPEAT_GUARD_MS) return
        lastSpoken = text; lastSpokenAt = now; requestFocus(); engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav-${now}")
    }

    fun stop() { tts?.stop(); abandonFocus(); lastSpoken = "" }
    fun release() { stop(); tts?.shutdown(); tts = null; ready = false; installed = emptyList() }

    private fun requestFocus() {
        val manager = audio ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build()
            focusRequest = request; manager.requestAudioFocus(request)
        } else { @Suppress("DEPRECATION") manager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) }
    }

    private fun abandonFocus() {
        val manager = audio ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { (focusRequest as? AudioFocusRequest)?.let { manager.abandonAudioFocusRequest(it) }; focusRequest = null }
        else { @Suppress("DEPRECATION") manager.abandonAudioFocus(null) }
    }

    private const val REPEAT_GUARD_MS = 8_000L
}
