package com.simplemobiletools.dialer.activities

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
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

class SimulatedCallActivity : AppCompatActivity() {
    companion object {
        private const val AUTO_DISCONNECT_MS = 15_000L
        private const val LISTEN_NOTIF_ID = ACTIVE_CALL_NOTIFICATION_ID
    }

    private lateinit var binding: ActivitySimulatedCallBinding
    private val handler = Handler(Looper.getMainLooper())
    private val greetingManager by lazy { GreetingManager(this) }
    private val callRecordingManager by lazy { CallRecordingManager(this) }

    private var state = State.RINGING
    private var autoAnswerCountdown = 0
    private var isListeningIn = false
    private var recordingResult: RecordingResult? = null

    private enum class State { RINGING, ACTIVE, ENDED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySimulatedCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        // Play greeting
        val greeting = config.autoAnswerGreeting
        if (greeting.isNotEmpty()) {
            handler.postDelayed({
                greetingManager.playGreetingForCall(
                    greeting = greeting,
                    languageTag = config.ttsLanguage,
                    engine = config.ttsEngine
                )
            }, 500)
        }

        // Start recording if enabled
        if (config.callRecordingEnabled) {
            callRecordingManager.startRecording("simulated")
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

        // Close after a short delay
        handler.postDelayed({ finish() }, 2000)
    }

    private fun enableSpeaker(on: Boolean) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.isSpeakerphoneOn = on
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
