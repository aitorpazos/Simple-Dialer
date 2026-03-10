package com.simplemobiletools.dialer.helpers

import android.content.Context
import android.media.AudioAttributes
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
 */
class GreetingManager(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingAction: (() -> Unit)? = null
    private var onDoneCallback: (() -> Unit)? = null

    private fun ensureTts(onReady: () -> Unit) {
        if (isInitialized && tts != null) {
            onReady()
            return
        }

        pendingAction = onReady
        tts = TextToSpeech(context) { status ->
            isInitialized = status == TextToSpeech.SUCCESS
            if (isInitialized) {
                tts?.language = Locale.getDefault()
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
    }

    /**
     * Play the configured greeting through VOICE_COMMUNICATION stream
     * so the remote caller hears it during an active call.
     */
    fun playGreetingForCall(onDone: (() -> Unit)? = null) {
        val greeting = context.config.autoAnswerGreeting
        if (greeting.isEmpty()) {
            onDone?.invoke()
            return
        }

        onDoneCallback = onDone
        ensureTts {
            val params = android.os.Bundle()
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
            tts?.speak(greeting, TextToSpeech.QUEUE_FLUSH, params, "greeting_call")
        }
    }

    /**
     * Play the greeting through the MUSIC stream so the user can hear
     * what callers will hear — for testing in Settings.
     */
    fun playGreetingPreview(onDone: (() -> Unit)? = null) {
        val greeting = context.config.autoAnswerGreeting
        if (greeting.isEmpty()) {
            onDone?.invoke()
            return
        }

        onDoneCallback = onDone
        ensureTts {
            val params = android.os.Bundle()
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            tts?.speak(greeting, TextToSpeech.QUEUE_FLUSH, params, "greeting_preview")
        }
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
    }
}
