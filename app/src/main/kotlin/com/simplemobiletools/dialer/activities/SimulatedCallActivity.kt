package com.simplemobiletools.dialer.activities

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Chronometer
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import com.simplemobiletools.dialer.R
import com.simplemobiletools.dialer.databinding.ActivitySimulatedCallBinding
import com.simplemobiletools.dialer.extensions.config
import com.simplemobiletools.dialer.helpers.*
import com.simplemobiletools.dialer.receivers.ActiveCallActionReceiver
import com.simplemobiletools.dialer.services.TranscriptionService
import java.io.File
import com.simplemobiletools.dialer.services.TranscriptionService

class SimulatedCallActivity : AppCompatActivity() {
    companion object {
        private const val AUTO_DISCONNECT_MS = 15_000L
        private const val LISTEN_NOTIF_ID = ACTIVE_CALL_NOTIFICATION_ID
        const val EXTRA_SIM_ID = "extra_sim_id"
    }

    private lateinit var binding: ActivitySimulatedCallBinding
    private val handler = Handler(Looper.getMainLooper())
    private val greetingManager by lazy { GreetingManager(this) }
    private val synthGreetingManager by lazy { GreetingManager(this) }
    private val callRecordingManager by lazy { CallRecordingManager(this) }

    private var state = State.RINGING
    private var autoAnswerCountdown = 0
    private var isListeningIn = false
    private var recordingResult: RecordingResult? = null
    private var simId: String? = null
    private var greetingAudioFile: File? = null

    private enum class State { RINGING, ACTIVE, ENDED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySimulatedCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        simId = intent.getStringExtra(EXTRA_SIM_ID)

        binding.callerName.text = getString(R.string.simulated_call_caller)
        binding.callerNumber.text = getString(R.string.simulated_call_number)
        binding.callStatus.text = getString(R.string.simulated_call_ringing)

        binding.answerButton.setOnClickListener { answerCall() }
        binding.declineButton.setOnClickListener { endCall() }
        binding.hangUpButton.setOnClickListener { endCall() }

        binding.hangUpButton.visibility = android.view.View.GONE
        binding.callTimer.visibility = android.view.View.GONE

        // Check auto-answer
        val autoAnswerMode = config.autoAnswerMode
        if (autoAnswerMode != AUTO_ANSWER_NONE) {
            startAutoAnswerCountdown()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        greetingManager.shutdown()
        synthGreetingManager.shutdown()
        dismissListenNotification()
    }

    private fun startAutoAnswerCountdown() {
        autoAnswerCountdown = 3
        updateCountdownText()
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (state != State.RINGING) return
                autoAnswerCountdown--
                if (autoAnswerCountdown <= 0) {
                    answerCall()
                } else {
                    updateCountdownText()
                    handler.postDelayed(this, 1000)
                }
            }
        }, 1000)
    }

    private fun updateCountdownText() {
        binding.callStatus.text = getString(R.string.simulated_call_auto_answering, autoAnswerCountdown)
    }

    private fun answerCall() {
        if (state != State.RINGING) return
        state = State.ACTIVE

        binding.callStatus.text = getString(R.string.simulated_call_active)
        binding.answerButton.visibility = android.view.View.GONE
        binding.declineButton.visibility = android.view.View.GONE
        binding.hangUpButton.visibility = android.view.View.VISIBLE

        // Start timer
        binding.callTimer.visibility = android.view.View.VISIBLE
        binding.callTimer.base = SystemClock.elapsedRealtime()
        binding.callTimer.start()

        // Play greeting — use per-SIM settings if a SIM was selected
        val simSettings = simId?.let { config.getSimSettings(it) }
        val greeting = if (simSettings != null && simSettings.greeting.isNotEmpty()) {
            simSettings.greeting
        } else {
            config.autoAnswerGreeting
        }
        val languageTag = if (simSettings != null && simSettings.language.isNotEmpty()) {
            simSettings.language
        } else {
            config.ttsLanguage
        }
        val enginePkg = if (simSettings != null && simSettings.engine.isNotEmpty()) {
            simSettings.engine
        } else {
            config.ttsEngine
        }

        if (greeting.isNotEmpty()) {
            // Start recording BEFORE greeting so ambient mic audio is captured
            startRecordingIfEnabled()

            // Synthesize greeting to a WAV file for transcription using a
            // separate GreetingManager instance so it doesn't conflict with
            // the playback instance.
            val greetingDir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "CallRecordings")
            greetingDir.mkdirs()
            greetingAudioFile = File(greetingDir, "greeting_simulated_${System.currentTimeMillis()}.wav")
            synthGreetingManager.synthesizeToFile(
                outputFile = greetingAudioFile!!,
                greeting = greeting,
                languageTag = languageTag,
                engine = enginePkg
            ) { /* synthesis done — file is ready for transcription at call end */ }

            // Also play the greeting audibly so the user hears it
            handler.postDelayed({
                greetingManager.playGreetingPreview(
                    greeting = greeting,
                    languageTag = languageTag,
                    engine = enginePkg
                ) {
                    // Greeting finished — recording continues to capture silence/response
                }
            }, 500)
        } else {
            // No greeting — start recording immediately
            startRecordingIfEnabled()
        }

        // Handle listen-in
        val listenInMode = config.listenInMode
        when (listenInMode) {
            LISTEN_IN_AUTO -> {
                handler.postDelayed({
                    enableSpeaker(true)
                    isListeningIn = true
                    showListenNotification(true)
                }, 600)
            }
            LISTEN_IN_NOTIFICATION -> {
                isListeningIn = false
                showListenNotification(false)
            }
        }

        // Auto-disconnect after timeout
        handler.postDelayed({ endCall() }, AUTO_DISCONNECT_MS)
    }

    private fun endCall() {
        if (state == State.ENDED) return
        state = State.ENDED

        handler.removeCallbacksAndMessages(null)
        greetingManager.stopGreeting()
        dismissListenNotification()

        binding.callTimer.stop()
        binding.callStatus.text = getString(R.string.simulated_call_ended)
        binding.answerButton.visibility = android.view.View.GONE
        binding.declineButton.visibility = android.view.View.GONE
        binding.hangUpButton.visibility = android.view.View.GONE

        // Stop recording and show summary
        if (callRecordingManager.isCurrentlyRecording()) {
            recordingResult = callRecordingManager.stopRecording()
        }

        val elapsed = if (binding.callTimer.base > 0) {
            ((SystemClock.elapsedRealtime() - binding.callTimer.base) / 1000).toInt()
        } else 0

        if (elapsed > 0) {
            CallSummaryManager(this).showCallSummary(
                contactName = getString(R.string.simulated_call_caller),
                phoneNumber = getString(R.string.simulated_call_number),
                durationSeconds = elapsed,
                recordingResult = recordingResult
            )
        }

        // Trigger transcription if transcription is enabled.
        // For simulated calls, prefer the synthesized greeting WAV file since
        // the mic recording (VOICE_COMMUNICATION) cannot capture TTS output.
        // Fall back to the mic recording if no greeting was synthesized.
        if (config.callTranscriptionEnabled) {
            val transcriptionUri: Uri?
            val transcriptionName: String

            if (greetingAudioFile != null && greetingAudioFile!!.exists() && greetingAudioFile!!.length() > 0) {
                // Use the synthesized greeting audio
                transcriptionUri = Uri.fromFile(greetingAudioFile!!)
                transcriptionName = greetingAudioFile!!.name
            } else if (recordingResult != null) {
                // Fall back to mic recording
                val transcriptionManager = TranscriptionManager(this)
                transcriptionUri = transcriptionManager.getRecordingUri(recordingResult!!)
                transcriptionName = recordingResult!!.name
            } else {
                transcriptionUri = null
                transcriptionName = ""
            }

            if (transcriptionUri != null && transcriptionName.isNotEmpty()) {
                try {
                    val transcriptionIntent = TranscriptionService.createIntent(
                        this, transcriptionUri, transcriptionName
                    )
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        startForegroundService(transcriptionIntent)
                    } else {
                        startService(transcriptionIntent)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // Close after a short delay (longer to allow transcription service to start)
        handler.postDelayed({ finish() }, 3000)
    }

    private fun enableSpeaker(on: Boolean) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.isSpeakerphoneOn = on
    }

    private fun startRecordingIfEnabled() {
        if (state != State.ACTIVE) return
        if (config.callRecordingEnabled) {
            callRecordingManager.startRecording("simulated")
        }
    }

    // ---- Listen-in notification (mirrors CallService) ----

    private fun showListenNotification(isSpeakerOn: Boolean) {
        createActiveCallChannel()

        val title = getString(R.string.active_call_notification_title, getString(R.string.simulated_call_caller))
        val text = if (isSpeakerOn) getString(R.string.listening_in) else getString(R.string.tap_listen_in)

        val builder = NotificationCompat.Builder(this, ACTIVE_CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_phone_vector)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (isSpeakerOn) {
            val stopIntent = Intent(this, ActiveCallActionReceiver::class.java).apply {
                action = ACTION_STOP_LISTENING
            }
            val stopPi = PendingIntent.getBroadcast(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(R.drawable.ic_phone_vector, getString(R.string.stop_listening), stopPi)
        } else {
            val listenIntent = Intent(this, ActiveCallActionReceiver::class.java).apply {
                action = ACTION_LISTEN_IN
            }
            val listenPi = PendingIntent.getBroadcast(this, 2, listenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(R.drawable.ic_phone_vector, getString(R.string.listen_in), listenPi)
        }

        val hangUpIntent = Intent(this, ActiveCallActionReceiver::class.java).apply {
            action = ACTION_HANG_UP
        }
        val hangUpPi = PendingIntent.getBroadcast(this, 3, hangUpIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        builder.addAction(R.drawable.ic_phone_vector, getString(R.string.hang_up), hangUpPi)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(LISTEN_NOTIF_ID, builder.build())
    }

    private fun createActiveCallChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.active_call_channel_name)
            val channel = NotificationChannel(
                ACTIVE_CALL_CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.active_call_channel_description)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun dismissListenNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(LISTEN_NOTIF_ID)
    }
}
