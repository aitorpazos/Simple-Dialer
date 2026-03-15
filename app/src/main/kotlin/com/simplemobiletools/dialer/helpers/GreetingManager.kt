package com.simplemobiletools.dialer.helpers

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.simplemobiletools.dialer.extensions.config
import java.util.*

/**
 * Manages TTS-based greeting playback for auto-answered calls.
 *
 * Each speak request creates a **fresh** TTS instance bound to the requested
 * engine and language.  This avoids every race-condition and state-leak that
 * comes from reusing a long-lived TTS object across different engine/language
 * combinations:
 *
 *  - Android TTS engines may silently reset their language after a speak()
 *    finishes or when the underlying service reconnects.
 *  - Switching language on an existing instance is unreliable on several OEM
 *    TTS implementations.
 *  - Creating a new instance is cheap (~50-100 ms) and guarantees a clean
 *    state every time.
 *
 * The previous instance is always shut down before the new one is created.
 */
class GreetingManager(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var onDoneCallback: (() -> Unit)? = null

    /**
     * Play the configured greeting through VOICE_COMMUNICATION stream
     * so the remote caller hears it during an active call.
     */
    fun playGreetingForCall(
        greeting: String? = null,
        languageTag: String = "",
        engine: String = "",
        onDone: (() -> Unit)? = null
    ) {
        speakInternal(
            text = greeting ?: context.config.autoAnswerGreeting,
            engine = engine,
            languageTag = languageTag,
            stream = AudioManager.STREAM_VOICE_CALL,
            utteranceId = "greeting_call",
            onDone = onDone
        )
    }

    /**
     * Play the greeting through the MUSIC stream so the user can hear
     * what callers will hear — for testing in Settings.
     */
    fun playGreetingPreview(
        greeting: String? = null,
        languageTag: String = "",
        engine: String = "",
        onDone: (() -> Unit)? = null
    ) {
        speakInternal(
            text = greeting ?: context.config.autoAnswerGreeting,
            engine = engine,
            languageTag = languageTag,
            stream = AudioManager.STREAM_MUSIC,
            utteranceId = "greeting_preview",
            onDone = onDone
        )
    }

    // ---------------------------------------------------------------
    // Core: create a fresh TTS instance for every speak request
    // ---------------------------------------------------------------

    private fun speakInternal(
        text: String,
        engine: String,
        languageTag: String,
        stream: Int,
        utteranceId: String,
        onDone: (() -> Unit)?
    ) {
        if (text.isEmpty()) {
            onDone?.invoke()
            return
        }

        // Resolve effective engine/language from config if not overridden
        val desiredEngine = engine.ifEmpty { context.config.ttsEngine }
        val desiredLang = languageTag.ifEmpty { context.config.ttsLanguage }

        // Tear down any previous instance completely
        destroyTts()

        onDoneCallback = onDone

        val initListener = TextToSpeech.OnInitListener { status ->
            if (status != TextToSpeech.SUCCESS) {
                onDoneCallback?.invoke()
                onDoneCallback = null
                return@OnInitListener
            }

            val instance = tts ?: return@OnInitListener

            // Apply language
            if (desiredLang.isNotEmpty()) {
                instance.language = Locale.forLanguageTag(desiredLang)
            } else {
                instance.language = Locale.getDefault()
            }

            // Utterance listener
            instance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
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

            // Speak
            val params = android.os.Bundle()
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, stream)
            instance.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        }

        tts = if (desiredEngine.isNotEmpty()) {
            TextToSpeech(context, initListener, desiredEngine)
        } else {
            TextToSpeech(context, initListener)
        }
    }

    // ---------------------------------------------------------------
    // Engine discovery (stateless — uses a temporary TTS instance)
    // ---------------------------------------------------------------

    /**
     * Get the list of available TTS engines on the device.
     */
    fun getAvailableEngines(): List<TextToSpeech.EngineInfo> {
        // If we have a live instance, ask it first
        tts?.engines?.let { if (it.isNotEmpty()) return it }

        val tempTts = try {
            TextToSpeech(context) { /* no-op */ }
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

    // ---------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------

    fun stopGreeting() {
        tts?.stop()
        onDoneCallback = null
    }

    fun shutdown() {
        destroyTts()
        onDoneCallback = null
    }

    private fun destroyTts() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
