package com.simplemobiletools.dialer.helpers

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
     * Queries PackageManager for services that handle the TTS engine intent,
     * which is more reliable than TextToSpeech.getEngines() on some devices
     * where the standard query may miss engines that don't declare CATEGORY_DEFAULT.
     */
    fun getAvailableEngines(): List<TextToSpeech.EngineInfo> {
        // If we already have an initialised TTS instance, use its engine list
        tts?.engines?.let { if (it.isNotEmpty()) return it }

        // Otherwise query PackageManager directly for TTS engine services.
        // This is synchronous and reliable, unlike creating a temporary
        // TextToSpeech instance which may not be fully bound yet.
        val pm = context.packageManager
        val serviceIntent = Intent("android.intent.action.TTS_SERVICE")
        return pm.queryIntentServices(serviceIntent, 0).mapNotNull { ri ->
            try {
                val info = TextToSpeech.EngineInfo()
                info.name = ri.serviceInfo.packageName
                val appInfo = pm.getApplicationInfo(ri.serviceInfo.packageName, 0)
                info.label = pm.getApplicationLabel(appInfo).toString()
                info
            } catch (_: Exception) {
                null
            }
        }.distinctBy { it.name }
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
