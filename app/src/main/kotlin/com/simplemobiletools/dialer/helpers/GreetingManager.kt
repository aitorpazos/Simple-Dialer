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
 * **Language fix:** Many TTS engines (Google TTS, Piper TTS, etc.) ignore or
 * inconsistently apply `setLanguage()`.  To reliably select the right language:
 *  1. We enumerate all voices via `getVoices()`.
 *  2. We find a voice matching the desired locale (exact match, then language-only).
 *  3. We use `setVoice()` to explicitly select it — this is much more reliable
 *     than `setLanguage()` across different engine implementations.
 *  4. We fall back to `setLanguage()` only when `getVoices()` is unavailable.
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
     * Apply the desired locale and speak.
     *
     * Many TTS engines (Google TTS, Piper TTS, etc.) do not reliably honour
     * [TextToSpeech.setLanguage].  The most robust approach is:
     *
     *  1. Enumerate all voices via [TextToSpeech.getVoices].
     *  2. Find a voice whose locale matches the desired language tag
     *     (exact match first, then language-only fallback).
     *  3. Use [TextToSpeech.setVoice] to explicitly select it.
     *  4. Fall back to [TextToSpeech.setLanguage] only when no matching
     *     voice is found (e.g. very old engines that don't expose voices).
     *
     * This fixes the issue where the first call uses the right language but
     * subsequent calls revert to the engine's default/cached language.
     */
    private fun applyLanguageAndSpeak(
        instance: TextToSpeech,
        desiredLocale: Locale,
        text: String,
        stream: Int,
        utteranceId: String,
        myGeneration: Int,
        @Suppress("UNUSED_PARAMETER") attempt: Int = 0
    ) {
        if (generation.get() != myGeneration) return

        // --- Step 1: Try to find and set an explicit Voice ---
        var voiceSet = false
        try {
            val allVoices = instance.voices
            if (allVoices != null && allVoices.isNotEmpty()) {
                // Exact locale match (language + country, e.g. en-GB)
                var match = allVoices.firstOrNull { v ->
                    !v.isNetworkConnectionRequired &&
                    v.locale.language == desiredLocale.language &&
                    v.locale.country == desiredLocale.country &&
                    desiredLocale.country.isNotEmpty()
                }

                // Fallback: language-only match (e.g. "en" matches any en-* voice)
                if (match == null) {
                    match = allVoices.firstOrNull { v ->
                        !v.isNetworkConnectionRequired &&
                        v.locale.language == desiredLocale.language
                    }
                }

                // Last resort: allow network voices too
                if (match == null) {
                    match = allVoices.firstOrNull { v ->
                        v.locale.language == desiredLocale.language &&
                        v.locale.country == desiredLocale.country &&
                        desiredLocale.country.isNotEmpty()
                    } ?: allVoices.firstOrNull { v ->
                        v.locale.language == desiredLocale.language
                    }
                }

                if (match != null) {
                    val setResult = instance.setVoice(match)
                    voiceSet = (setResult == TextToSpeech.SUCCESS)
                    Log.d(TAG, "gen=$myGeneration setVoice(${match.name}, locale=${match.locale}) " +
                        "result=$setResult voiceSet=$voiceSet")
                } else {
                    Log.w(TAG, "gen=$myGeneration no voice found for locale=$desiredLocale " +
                        "among ${allVoices.size} voices")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "gen=$myGeneration getVoices/setVoice failed: ${e.message}")
        }

        // --- Step 2: Fallback to setLanguage if setVoice didn't work ---
        if (!voiceSet) {
            val result = instance.setLanguage(desiredLocale)
            Log.d(TAG, "gen=$myGeneration setLanguage($desiredLocale) result=$result (fallback)")

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "gen=$myGeneration language $desiredLocale not supported (result=$result), " +
                    "engine will use its default")
            }
        }

        // Log final state
        val finalVoice = try { instance.voice } catch (_: Exception) { null }
        Log.d(TAG, "gen=$myGeneration final voice=${finalVoice?.name} locale=${finalVoice?.locale} " +
            "engine=${instance.defaultEngine}")

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
