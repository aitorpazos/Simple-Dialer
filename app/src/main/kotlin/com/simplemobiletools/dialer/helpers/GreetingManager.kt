package com.simplemobiletools.dialer.helpers

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.simplemobiletools.dialer.extensions.config
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "GreetingManager"

/**
 * Manages TTS-based greeting playback for auto-answered calls.
 *
 * Each speak request creates a **fresh** TTS instance bound to the requested
 * engine and language, identified by a monotonically increasing generation
 * counter.  This avoids every race-condition that comes from:
 *
 *  - Android TTS engines silently resetting their language after speak()
 *  - Switching language on an existing instance being unreliable
 *  - Rapid successive requests (e.g. tapping "preview" twice) causing the
 *    OnInitListener of a stale instance to fire on the wrong TTS object
 *
 * The generation counter ensures that only the *latest* request's init
 * callback ever proceeds to speak.  Any older callback is a no-op.
 */
class GreetingManager(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var onDoneCallback: (() -> Unit)? = null

    /** Monotonically increasing counter — each speakInternal() call bumps it. */
    private val generation = AtomicInteger(0)

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

        // Bump generation — any older init callback becomes a no-op
        val myGeneration = generation.incrementAndGet()

        Log.d(TAG, "speakInternal gen=$myGeneration engine='$desiredEngine' lang='$desiredLang' stream=$stream")

        // Tear down any previous instance completely
        destroyTts()

        onDoneCallback = onDone

        // Capture desired params so the callback closure is self-contained
        // and does not depend on any mutable field except the generation check.
        val desiredLocale = if (desiredLang.isNotEmpty()) {
            Locale.forLanguageTag(desiredLang)
        } else {
            Locale.getDefault()
        }

        val initListener = TextToSpeech.OnInitListener { status ->
            // If a newer request has been issued since we were created, bail out.
            if (generation.get() != myGeneration) {
                Log.w(TAG, "Init callback gen=$myGeneration is stale (current=${generation.get()}), skipping")
                return@OnInitListener
            }

            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS init failed with status=$status for gen=$myGeneration")
                onDoneCallback?.invoke()
                onDoneCallback = null
                return@OnInitListener
            }

            val instance = tts ?: return@OnInitListener

            // Double-check generation again — another request could have
            // slipped in between the status check and here.
            if (generation.get() != myGeneration) return@OnInitListener

            // Apply language
            val result = instance.setLanguage(desiredLocale)
            Log.d(TAG, "gen=$myGeneration setLanguage($desiredLocale) result=$result engine=${instance.defaultEngine}")

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
        // Use a dedicated temporary instance — never the playback instance.
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
        generation.incrementAndGet() // invalidate any pending init callbacks
        tts?.stop()
        onDoneCallback = null
    }

    fun shutdown() {
        generation.incrementAndGet() // invalidate any pending init callbacks
        destroyTts()
        onDoneCallback = null
    }

    private fun destroyTts() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
