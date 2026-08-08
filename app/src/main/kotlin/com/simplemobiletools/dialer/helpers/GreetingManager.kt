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

            // Delay before applying language — some TTS engines (Piper TTS,
            // Google TTS) haven't fully loaded their voice list when the init
            // callback fires.  A short delay gives the engine time to populate
            // getVoices() so that setVoice() can find the correct voice.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (generation.get() != myGeneration) return@postDelayed
                val inst = tts ?: return@postDelayed
                applyLanguageAndSpeak(inst, desiredLocale, text, stream, utteranceId, myGeneration)
            }, 300)
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
     * a single call to [TextToSpeech.setLanguage] or [TextToSpeech.setVoice].
     * To maximise compatibility we apply BOTH:
     *
     *  1. Call [TextToSpeech.setLanguage] first — this sets `request.language`
     *     in the synthesis request, which engines like Piper TTS use to select
     *     the correct voice model in `onSynthesizeText`.
     *  2. Then enumerate voices via [TextToSpeech.getVoices] and call
     *     [TextToSpeech.setVoice] to explicitly select a matching voice.
     *     This sets `request.voiceName` which some engines prefer.
     *
     * Applying both ensures the engine receives the correct language regardless
     * of which field it checks internally.
     *
     * IMPORTANT: The Android TTS framework may cache a default voice name
     * during initialization (from the device locale). `setLanguage()` does NOT
     * clear this cached voice name. So if `setVoice()` fails or is skipped,
     * the engine receives a stale voice name that may be for the wrong language.
     * We mitigate this by always calling `setVoice()` and verifying the result.
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

        // --- Step 1: Always call setLanguage first ---
        // This ensures request.language is set in the synthesis request.
        // Engines like Piper TTS check request.language in onSynthesizeText
        // to decide which voice model to load.
        val langResult = instance.setLanguage(desiredLocale)
        Log.d(TAG, "gen=$myGeneration setLanguage($desiredLocale) result=$langResult")

        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.e(TAG, "gen=$myGeneration language $desiredLocale not supported (result=$langResult), " +
                "engine will use its default")
        }

        // --- Step 2: Also try to set an explicit Voice ---
        // Keep the voice selected for THIS request separate from instance.voice.
        // Android can leave instance.voice pointing at the system default when
        // setLanguage()/setVoice() encounters its client-side verification race.
        val selectedVoiceName = selectVoiceForLocale(instance, desiredLocale, myGeneration)

        // Log framework state for diagnostics, but never use it as Piper's
        // direct voice override because it may be stale and in another language.
        val frameworkVoice = try { instance.voice } catch (_: Exception) { null }
        Log.d(TAG, "gen=$myGeneration framework voice=${frameworkVoice?.name} " +
            "locale=${frameworkVoice?.locale} selectedVoice=$selectedVoiceName " +
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

        // Speak — pass desired language directly in params Bundle.
        // This uses a custom protocol with Piper TTS that bypasses the
        // Android framework's unreliable voice/language state management.
        // The keys are: piper_language (2-letter), piper_country (2-letter),
        // piper_voice_name (optional exact voice name).
        val params = android.os.Bundle()
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, stream)
        // Direct language override for Piper TTS
        params.putString("piper_language", desiredLocale.language)
        if (desiredLocale.country.isNotEmpty()) {
            params.putString("piper_country", desiredLocale.country)
        }
        // Only send the voice explicitly selected for this request. Falling
        // back to instance.voice here used to send Piper a stale system-default
        // voice, which has higher priority than piper_language in that engine.
        if (selectedVoiceName != null) {
            params.putString("piper_voice_name", selectedVoiceName)
        }
        instance.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    /**
     * Select and apply a voice that matches [desiredLocale]. The returned name
     * is safe to send as Piper's direct voice override because it belongs to
     * this request and [TextToSpeech.setVoice] accepted it.
     */
    private fun selectVoiceForLocale(
        instance: TextToSpeech,
        desiredLocale: Locale,
        myGeneration: Int,
    ): String? {
        return try {
            val androidVoices = instance.voices.orEmpty()
            if (androidVoices.isEmpty()) {
                Log.w(TAG, "gen=$myGeneration getVoices returned null/empty, relying on language only")
                return null
            }

            val candidates = androidVoices.map { voice ->
                TtsLanguagePolicy.VoiceCandidate(
                    name = voice.name,
                    language = voice.locale.language,
                    country = voice.locale.country,
                    requiresNetwork = voice.isNetworkConnectionRequired,
                )
            }
            val selected = TtsLanguagePolicy.selectVoice(
                desiredLanguage = desiredLocale.language,
                desiredCountry = desiredLocale.country,
                voices = candidates,
            )
            if (selected == null) {
                Log.w(TAG, "gen=$myGeneration no voice found for locale=$desiredLocale " +
                    "among ${androidVoices.size} voices, relying on language only")
                return null
            }

            val androidVoice = androidVoices.firstOrNull { it.name == selected.name } ?: return null
            val result = instance.setVoice(androidVoice)
            val override = TtsLanguagePolicy.piperVoiceOverride(
                desiredLanguage = desiredLocale.language,
                selectedVoice = selected,
                setVoiceSucceeded = result == TextToSpeech.SUCCESS,
            )
            Log.d(TAG, "gen=$myGeneration setVoice(${androidVoice.name}, locale=${androidVoice.locale}) " +
                "result=$result directOverride=$override")

            // Do not call setLanguage() again. Android can reset the successful
            // voice selection back to the system default during verification.
            override
        } catch (e: Exception) {
            Log.w(TAG, "gen=$myGeneration getVoices/setVoice failed: ${e.message}")
            null
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

    /**
     * Synthesize the greeting text to a WAV file.
     *
     * This is used by SimulatedCallActivity to capture the TTS greeting as
     * audio for transcription — since simulated calls don't have a real phone
     * call audio route, the MediaRecorder (VOICE_COMMUNICATION source) cannot
     * capture TTS output played through the speaker.
     *
     * @param outputFile  the WAV file to write to
     * @param greeting    greeting text (falls back to config)
     * @param languageTag BCP-47 tag (falls back to config)
     * @param engine      TTS engine package (falls back to config)
     * @param onDone      called when synthesis finishes (success or failure)
     */
    fun synthesizeToFile(
        outputFile: java.io.File,
        greeting: String? = null,
        languageTag: String = "",
        engine: String = "",
        onDone: ((success: Boolean) -> Unit)? = null
    ) {
        val text = greeting ?: context.config.autoAnswerGreeting
        if (text.isEmpty()) {
            onDone?.invoke(false)
            return
        }

        val desiredEngine = engine.ifEmpty { context.config.ttsEngine }
        val desiredLang = languageTag.ifEmpty { context.config.ttsLanguage }
        val desiredLocale = if (desiredLang.isNotEmpty()) {
            Locale.forLanguageTag(desiredLang)
        } else {
            Locale.getDefault()
        }

        val myGeneration = generation.incrementAndGet()
        destroyTts()

        val initListener = TextToSpeech.OnInitListener { status ->
            if (generation.get() != myGeneration) return@OnInitListener
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS init failed for synthesizeToFile gen=$myGeneration")
                onDone?.invoke(false)
                return@OnInitListener
            }
            val instance = tts ?: return@OnInitListener
            if (generation.get() != myGeneration) return@OnInitListener

            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (generation.get() != myGeneration) return@postDelayed
                val inst = tts ?: return@postDelayed

                // Apply the same language and verified voice selection used by
                // live greeting playback.
                val langResult = inst.setLanguage(desiredLocale)
                Log.d(TAG, "synthesizeToFile setLanguage($desiredLocale) result=$langResult")
                val selectedVoiceName = selectVoiceForLocale(inst, desiredLocale, myGeneration)

                inst.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "synthesizeToFile complete: ${outputFile.absolutePath}")
                        onDone?.invoke(true)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "synthesizeToFile error")
                        onDone?.invoke(false)
                    }
                })

                val params = android.os.Bundle()
                // Direct language override for Piper TTS (same as speak path)
                params.putString("piper_language", desiredLocale.language)
                if (desiredLocale.country.isNotEmpty()) {
                    params.putString("piper_country", desiredLocale.country)
                }
                if (selectedVoiceName != null) {
                    params.putString("piper_voice_name", selectedVoiceName)
                }
                inst.synthesizeToFile(text, params, outputFile, "synth_to_file_$myGeneration")
            }, 300)
        }

        tts = if (desiredEngine.isNotEmpty()) {
            TextToSpeech(context, initListener, desiredEngine)
        } else {
            TextToSpeech(context, initListener)
        }
    }

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
