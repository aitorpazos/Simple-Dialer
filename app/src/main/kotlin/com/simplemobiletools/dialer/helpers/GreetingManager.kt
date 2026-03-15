package com.simplemobiletools.dialer.helpers

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.simplemobiletools.dialer.extensions.config
import java.util.*

/**
 * Manages TTS-based greeting playback for auto-answered calls.
 * Plays the configured greeting text through the VOICE_COMMUNICATION stream
 * so the caller hears it, and also supports a local preview mode via the
 * MUSIC stream for testing in Settings.
 *
 * Supports selecting a specific TTS engine and language, and per-SIM overrides.
 */
class GreetingManager(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingAction: (() -> Unit)? = null
    private var onDoneCallback: (() -> Unit)? = null
    private var currentEngine: String = ""
    private var currentLanguageTag: String = ""

    private fun ensureTts(engine: String, languageTag: String, onReady: () -> Unit) {
        val desiredEngine = engine.ifEmpty { context.config.ttsEngine }
        val desiredLang = languageTag.ifEmpty { context.config.ttsLanguage }

        // Reinitialise if engine changed
        if (isInitialized && tts != null && desiredEngine == currentEngine) {
            applyLanguage(desiredLang)
            onReady()
            return
        }

        // Shut down existing instance if engine changed
        if (tts != null) {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isInitialized = false
        }

        currentEngine = desiredEngine
        currentLanguageTag = desiredLang
        pendingAction = onReady

        val initListener = TextToSpeech.OnInitListener { status ->
            isInitialized = status == TextToSpeech.SUCCESS
            if (isInitialized) {
                applyLanguage(desiredLang)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        onDoneCallback?.invoke()
                        onDoneCallback = null
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        onDoneCallback?.invoke()
                        onDoneCallback = null
                    }
                })
                pendingAction?.invoke()
                pendingAction = null
            }
        }

        tts = if (desiredEngine.isNotEmpty()) {
            TextToSpeech(context, initListener, desiredEngine)
        } else {
            TextToSpeech(context, initListener)
        }
    }

    private fun applyLanguage(languageTag: String) {
        if (languageTag.isNotEmpty()) {
            val locale = Locale.forLanguageTag(languageTag)
            tts?.language = locale
            currentLanguageTag = languageTag
        } else {
            tts?.language = Locale.getDefault()
            currentLanguageTag = ""
        }
    }

    /**
     * Play the configured greeting through VOICE_COMMUNICATION stream
     * so the remote caller hears it during an active call.
     *
     * @param greeting Override greeting text (or null to use global config)
     * @param languageTag BCP-47 language tag override (or empty for global/default)
     * @param engine TTS engine package name override (or empty for global/default)
     */
    fun playGreetingForCall(
        greeting: String? = null,
        languageTag: String = "",
        engine: String = "",
        onDone: (() -> Unit)? = null
    ) {
        val text = greeting ?: context.config.autoAnswerGreeting
        if (text.isEmpty()) {
            onDone?.invoke()
            return
        }

        onDoneCallback = onDone
        ensureTts(engine, languageTag) {
            val params = android.os.Bundle()
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "greeting_call")
        }
    }

    /**
     * Play the greeting through the MUSIC stream so the user can hear
     * what callers will hear — for testing in Settings.
     *
     * @param greeting Override greeting text (or null to use global config)
     * @param languageTag BCP-47 language tag override (or empty for global/default)
     * @param engine TTS engine package name override (or empty for global/default)
     */
    fun playGreetingPreview(
        greeting: String? = null,
        languageTag: String = "",
        engine: String = "",
        onDone: (() -> Unit)? = null
    ) {
        val text = greeting ?: context.config.autoAnswerGreeting
        if (text.isEmpty()) {
            onDone?.invoke()
            return
        }

        onDoneCallback = onDone
        ensureTts(engine, languageTag) {
            val params = android.os.Bundle()
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "greeting_preview")
        }
    }

    /**
     * Get the list of available TTS engines on the device.
     * Creates a temporary TextToSpeech instance to reliably query the engine
     * list via the standard API, which handles Android 11+ package visibility
     * correctly without manual PackageManager queries.
     */
    fun getAvailableEngines(): List<TextToSpeech.EngineInfo> {
        // If we already have an initialised TTS instance, use its engine list
        tts?.engines?.let { if (it.isNotEmpty()) return it }

        // Create a temporary TTS instance just to query engines.
        // The constructor is synchronous enough that .engines is available
        // immediately (it queries PackageManager internally with the correct
        // flags and component resolution).
        val tempTts = try {
            TextToSpeech(context) { /* no-op init listener */ }
        } catch (_: Exception) {
            return emptyList()
        }
        val engines = try {
            tempTts.engines ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        tempTts.shutdown()
        return engines
    }

    /**
     * Get available languages for the current (or specified) engine.
     * Must be called after TTS is initialised.
     */
    fun getAvailableLanguages(): Set<Locale> {
        return tts?.availableLanguages ?: emptySet()
    }

    fun stopGreeting() {
        tts?.stop()
        onDoneCallback = null
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        pendingAction = null
        onDoneCallback = null
        currentEngine = ""
        currentLanguageTag = ""
    }
}
