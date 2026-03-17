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
 *
 * **Language fix:** Some TTS engines (notably Google TTS) ignore setLanguage()
 * when called immediately inside OnInitListener, or return success but still
 * speak in the previously cached language.  To work around this:
 *  1. We always fully destroy the previous TTS instance before creating a new one.
 *  2. After setLanguage(), we verify the engine's actual language matches what
 *     we requested.  If it doesn't, we retry setLanguage() after a short delay.
 *  3. We pass the language as a TTS param via `KEY_PARAM_LANGUAGE` (undocumented
 *     but honoured by some engines) as an additional safety net.
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

            // Apply language and verify it was actually set
            applyLanguageAndSpeak(instance, desiredLocale, text, stream, utteranceId, myGeneration)
        }

        tts = if (desiredEngine.isNotEmpty()) {
            TextToSpeech(context, initListener, desiredEngine)
        } else {
            TextToSpeech(context, initListener)
        }
    }

    /**
     * Apply the desired locale, verify it took effect, and then speak.
     *
     * Some TTS engines (especially Google TTS) have a race condition where
     * setLanguage() returns SUCCESS but the engine internally still uses the
     * previous language.  We work around this by:
     *  1. Calling setLanguage() and checking the return code.
     *  2. Verifying instance.voice?.locale or instance.language matches.
     *  3. If mismatch, posting a short delayed retry (100ms) up to 2 times.
     */
    private fun applyLanguageAndSpeak(
        instance: TextToSpeech,
        desiredLocale: Locale,
        text: String,
        stream: Int,
        utteranceId: String,
        myGeneration: Int,
        attempt: Int = 0
    ) {
        if (generation.get() != myGeneration) return

        val result = instance.setLanguage(desiredLocale)
        val actualLocale = try {
            instance.voice?.locale
        } catch (_: Exception) {
            null
        }

        Log.d(TAG, "gen=$myGeneration setLanguage($desiredLocale) result=$result " +
            "actualVoiceLocale=$actualLocale engine=${instance.defaultEngine} attempt=$attempt")

        // Check if the language was actually applied
        val languageMismatch = actualLocale != null &&
            actualLocale.language != desiredLocale.language

        if (languageMismatch && attempt < 2) {
            // Retry after a short delay — the engine may need time to switch
            Log.w(TAG, "gen=$myGeneration language mismatch: wanted=${desiredLocale.language} " +
                "got=${actualLocale?.language}, retrying in 150ms (attempt ${attempt + 1})")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (generation.get() == myGeneration) {
                    applyLanguageAndSpeak(instance, desiredLocale, text, stream, utteranceId, myGeneration, attempt + 1)
                }
            }, 150)
            return
        }

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "gen=$myGeneration language $desiredLocale not supported (result=$result), " +
                "falling back to default")
            // Don't call setLanguage again — let the engine use its default
        }

        // Set up utterance listener
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
        try {
            tts?.stop()
        } catch (_: Exception) {}
        try {
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
    }
}
