package com.simplemobiletools.dialer.services

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import androidx.core.app.NotificationCompat
import com.simplemobiletools.dialer.R
import com.simplemobiletools.dialer.activities.CallActivity
import com.simplemobiletools.dialer.extensions.config
import com.simplemobiletools.dialer.extensions.getAvailableSIMCardLabels
import com.simplemobiletools.dialer.extensions.getCallDuration
import com.simplemobiletools.dialer.extensions.getStateCompat
import com.simplemobiletools.dialer.extensions.isOutgoing
import com.simplemobiletools.dialer.extensions.powerManager
import com.simplemobiletools.dialer.helpers.*
import com.simplemobiletools.dialer.receivers.ActiveCallActionReceiver

class CallService : InCallService() {
    private val callNotificationManager by lazy { CallNotificationManager(this) }
    private val callRecordingManager by lazy { CallRecordingManager(this) }
    private val callSummaryManager by lazy { CallSummaryManager(this) }
    private val greetingManager by lazy { GreetingManager(this) }
    private val handler = Handler(Looper.getMainLooper())

    // Track per-call state
    private var currentCallNumber = ""
    private var currentCallName = ""
    private var currentRecordingResult: RecordingResult? = null
    private var callStartTimeMs = 0L
    private var wasAutoAnswered = false
    private var isListeningIn = false
    private var currentSimId: String = ""

    private val callListener = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            when (state) {
                Call.STATE_ACTIVE -> onCallActive(call)
                Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> {
                    onCallEnding(call)
                    callNotificationManager.cancelNotification()
                }
                else -> callNotificationManager.setupNotification()
            }
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.onCallAdded(call)
        CallManager.inCallService = this
        call.registerCallback(callListener)

        // Extract call info
        extractCallInfo(call)

        // Check auto-answer for incoming calls
        wasAutoAnswered = false
        if (!call.isOutgoing()) {
            handleAutoAnswer(call)
        }

        val isScreenLocked = (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceLocked
        if (!powerManager.isInteractive || call.isOutgoing() || isScreenLocked || config.alwaysShowFullscreen) {
            try {
                callNotificationManager.setupNotification(true)
                startActivity(CallActivity.getStartIntent(this))
            } catch (e: Exception) {
                callNotificationManager.setupNotification()
            }
        } else {
            callNotificationManager.setupNotification()
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callListener)
        val wasPrimaryCall = call == CallManager.getPrimaryCall()
        CallManager.onCallRemoved(call)
        if (CallManager.getPhoneState() == NoCall) {
            CallManager.inCallService = null
            callNotificationManager.cancelNotification()
            dismissActiveCallNotification()
        } else {
            callNotificationManager.setupNotification()
            if (wasPrimaryCall) {
                startActivity(CallActivity.getStartIntent(this))
            }
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        if (audioState != null) {
            CallManager.onAudioStateChanged(audioState)
            // Update listen-in notification when speaker state changes
            if (wasAutoAnswered && config.listenInMode != LISTEN_IN_OFF) {
                val isSpeaker = audioState.route == CallAudioState.ROUTE_SPEAKER
                if (isSpeaker != isListeningIn) {
                    isListeningIn = isSpeaker
                    showActiveCallNotification(isListeningIn)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        callNotificationManager.cancelNotification()
        dismissActiveCallNotification()
        greetingManager.shutdown()
    }

    private fun extractCallInfo(call: Call) {
        try {
            val handle = call.details?.handle?.toString() ?: ""
            currentCallNumber = if (handle.startsWith("tel:")) {
                android.net.Uri.decode(handle.substringAfter("tel:"))
            } else {
                ""
            }

            // Detect which SIM received this call
            currentSimId = ""
            try {
                val callAccountHandle = call.details?.accountHandle
                if (callAccountHandle != null) {
                    val simAccounts = getAvailableSIMCardLabels()
                    val matchedSim = simAccounts.firstOrNull { it.handle == callAccountHandle }
                    if (matchedSim != null) {
                        currentSimId = matchedSim.id.toString()
                    }
                }
            } catch (_: Exception) {}

            // Get contact name asynchronously
            getCallContact(this, call) { contact ->
                currentCallName = contact.name
            }
        } catch (e: Exception) {
            currentCallNumber = ""
        }
    }

    private fun handleAutoAnswer(call: Call) {
        val autoAnswerMode = config.autoAnswerMode
        if (autoAnswerMode == AUTO_ANSWER_NONE) return

        when (autoAnswerMode) {
            AUTO_ANSWER_ALL -> {
                // Auto-answer after a short delay to let the UI show
                handler.postDelayed({
                    if (call.getStateCompat() == Call.STATE_RINGING) {
                        wasAutoAnswered = true
                        call.answer(VideoProfile.STATE_AUDIO_ONLY)
                    }
                }, 1000)
            }
            AUTO_ANSWER_UNKNOWN -> {
                // Check if caller is unknown (not in contacts)
                getCallContact(this, call) { contact ->
                    val isUnknown = contact.name.isEmpty() || contact.name == contact.number
                    if (isUnknown) {
                        handler.postDelayed({
                            if (call.getStateCompat() == Call.STATE_RINGING) {
                                wasAutoAnswered = true
                                call.answer(VideoProfile.STATE_AUDIO_ONLY)
                            }
                        }, 1000)
                    }
                }
            }
        }
    }

    private fun onCallActive(call: Call) {
        callStartTimeMs = System.currentTimeMillis()

        // Play greeting if this was an auto-answered call
        if (wasAutoAnswered) {
            // Resolve per-SIM overrides
            val simSettings = if (currentSimId.isNotEmpty()) {
                config.getSimSettings(currentSimId)
            } else {
                null
            }
            val greetingText = simSettings?.greeting?.takeIf { it.isNotEmpty() } ?: config.autoAnswerGreeting
            val languageTag = simSettings?.language?.takeIf { it.isNotEmpty() } ?: config.ttsLanguage
            val enginePkg = simSettings?.engine?.takeIf { it.isNotEmpty() } ?: config.ttsEngine

            if (greetingText.isNotEmpty()) {
                // Small delay to let audio route stabilise after answer
                handler.postDelayed({
                    greetingManager.playGreetingForCall(
                        greeting = greetingText,
                        languageTag = languageTag,
                        engine = enginePkg
                    )
                }, 500)
            }

            // Handle listen-in
            val listenInMode = config.listenInMode
            when (listenInMode) {
                LISTEN_IN_AUTO -> {
                    handler.postDelayed({
                        CallManager.setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                        isListeningIn = true
                        showActiveCallNotification(true)
                    }, 600)
                }
                LISTEN_IN_NOTIFICATION -> {
                    isListeningIn = false
                    showActiveCallNotification(false)
                }
            }
        }

        // Start recording if enabled
        if (config.callRecordingEnabled) {
            val number = currentCallNumber.ifEmpty { "unknown" }
            val success = callRecordingManager.startRecording(number)
            if (!success) {
                currentRecordingResult = null
            }
        }
    }

    private fun onCallEnding(call: Call) {
        // Stop greeting if still playing
        greetingManager.stopGreeting()

        // Dismiss listen-in notification
        dismissActiveCallNotification()

        // Stop recording
        val recordingResult = if (callRecordingManager.isCurrentlyRecording()) {
            callRecordingManager.stopRecording()
        } else {
            null
        }
        currentRecordingResult = recordingResult

        // Calculate duration
        val durationSeconds = if (callStartTimeMs > 0) {
            ((System.currentTimeMillis() - callStartTimeMs) / 1000).toInt()
        } else {
            call.getCallDuration()
        }

        // Show call summary notification
        if (durationSeconds > 0) {
            val name = currentCallName.ifEmpty { currentCallNumber }
            callSummaryManager.showCallSummary(
                contactName = name,
                phoneNumber = currentCallNumber,
                durationSeconds = durationSeconds,
                recordingResult = recordingResult
            )
        }

        // Reset state
        callStartTimeMs = 0L
        currentCallNumber = ""
        currentCallName = ""
        currentRecordingResult = null
        wasAutoAnswered = false
        isListeningIn = false
        currentSimId = ""
    }

    // ---- Listen-in notification ----

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

    private fun showActiveCallNotification(isSpeakerOn: Boolean) {
        createActiveCallChannel()

        val callerLabel = currentCallName.ifEmpty { currentCallNumber.ifEmpty { getString(R.string.unknown_caller) } }
        val title = getString(R.string.active_call_notification_title, callerLabel)
        val text = if (isSpeakerOn) getString(R.string.listening_in) else getString(R.string.tap_listen_in)

        val builder = NotificationCompat.Builder(this, ACTIVE_CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_phone_vector)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)

        // Toggle listen action
        if (isSpeakerOn) {
            val stopIntent = Intent(this, ActiveCallActionReceiver::class.java).apply {
                action = ACTION_STOP_LISTENING
            }
            val stopPi = PendingIntent.getBroadcast(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_phone_vector, getString(R.string.stop_listening), stopPi)
        } else {
            val listenIntent = Intent(this, ActiveCallActionReceiver::class.java).apply {
                action = ACTION_LISTEN_IN
            }
            val listenPi = PendingIntent.getBroadcast(
                this, 2, listenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_phone_vector, getString(R.string.listen_in), listenPi)
        }

        // Hang up action
        val hangUpIntent = Intent(this, ActiveCallActionReceiver::class.java).apply {
            action = ACTION_HANG_UP
        }
        val hangUpPi = PendingIntent.getBroadcast(
            this, 3, hangUpIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(R.drawable.ic_phone_vector, getString(R.string.hang_up), hangUpPi)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ACTIVE_CALL_NOTIFICATION_ID, builder.build())
    }

    private fun dismissActiveCallNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(ACTIVE_CALL_NOTIFICATION_ID)
    }
}
