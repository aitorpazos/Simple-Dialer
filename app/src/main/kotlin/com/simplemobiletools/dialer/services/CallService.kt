package com.simplemobiletools.dialer.services

import android.Manifest
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
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
    companion object {
        private const val TAG = "CallService"
        private const val RECORDING_FOREGROUND_ID = 4002
    }

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
    private var autoAnswerRunnable: Runnable? = null
    private var autoAnswerCountdown = 0
    private var recordingStartRunnable: Runnable? = null
    private var callEndingHandled = false
    private var recordingForegroundActive = false

    private val callListener = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            when (state) {
                Call.STATE_ACTIVE -> {
                    onCallActive(call)
                    // Always refresh the ongoing-call notification so the user can
                    // return to the call after switching apps.
                    callNotificationManager.setupNotification()
                }
                Call.STATE_DISCONNECTED -> {
                    onCallEnding(call)
                    callNotificationManager.cancelNotification()
                }
                Call.STATE_DISCONNECTING -> {
                    cancelPendingRecordingStart()
                    callNotificationManager.setupNotification()
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

        // Reset state that must only live for one call.
        callEndingHandled = false
        callStartTimeMs = 0L
        currentRecordingResult = null
        callRecordingManager.clearLastFailure()
        cancelPendingRecordingStart()

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
        cancelAutoAnswer()
        cancelPendingRecordingStart()
        if (!callEndingHandled && (callStartTimeMs > 0L || callRecordingManager.isCurrentlyRecording())) {
            onCallEnding(call)
        }
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
        cancelAutoAnswer()
        cancelPendingRecordingStart()
        if (callRecordingManager.isCurrentlyRecording()) {
            callRecordingManager.stopRecording()
        }
        stopRecordingForeground()
        callNotificationManager.cancelNotification()
        dismissActiveCallNotification()
        greetingManager.shutdown()
        super.onDestroy()
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
        if (autoAnswerMode == AUTO_ANSWER_NONE || autoAnswerMode == AUTO_ANSWER_MANUAL) return

        val shouldAnswer = { doAnswer: () -> Unit ->
            // Always run a visible countdown (minimum 1s) so the user can see
            // when the auto-answer will happen and inhibit it. If the user
            // answers or declines manually first, the countdown is cancelled.
            val delaySeconds = maxOf(config.autoAnswerDelaySeconds, 1)
            autoAnswerCountdown = delaySeconds
            CallManager.autoAnswerCountdown = autoAnswerCountdown
            val countdownRunnable = object : Runnable {
                override fun run() {
                    // User already answered/declined or the call state changed:
                    // never auto-answer over a manual action.
                    if (call.getStateCompat() != Call.STATE_RINGING) {
                        cancelAutoAnswer()
                        return
                    }
                    autoAnswerCountdown--
                    CallManager.autoAnswerCountdown = autoAnswerCountdown
                    if (autoAnswerCountdown <= 0) {
                        wasAutoAnswered = true
                        autoAnswerRunnable = null
                        CallManager.autoAnswerCountdown = -1
                        call.answer(VideoProfile.STATE_AUDIO_ONLY)
                    } else {
                        handler.postDelayed(this, 1000)
                    }
                }
            }
            autoAnswerRunnable = countdownRunnable
            handler.postDelayed(countdownRunnable, 1000)
        }

        when (autoAnswerMode) {
            AUTO_ANSWER_ALL -> shouldAnswer {}
            AUTO_ANSWER_UNKNOWN -> {
                getCallContact(this, call) { contact ->
                    val isUnknown = contact.name.isEmpty() || contact.name == contact.number
                    if (isUnknown) {
                        shouldAnswer {}
                    }
                }
            }
        }
    }

    /**
     * Answer a ringing incoming call through the auto-answer workflow, but only
     * when the explicit manual auto-answer mode is selected. A regular Answer
     * action continues to behave like a normal call and does not play the
     * greeting or start the auto-answer recording/listen-in flow.
     */
    fun answerWithAutoAnswer(): Boolean {
        if (config.autoAnswerMode != AUTO_ANSWER_MANUAL) return false

        val call = CallManager.getPrimaryCall() ?: return false
        if (call.isOutgoing() || call.getStateCompat() != Call.STATE_RINGING) return false

        cancelAutoAnswer()
        wasAutoAnswered = true
        call.answer(VideoProfile.STATE_AUDIO_ONLY)
        return true
    }

    /**
     * Cancel any pending auto-answer countdown. Called when the user
     * manually answers or declines the call during the delay.
     */
    fun cancelAutoAnswer() {
        autoAnswerRunnable?.let { handler.removeCallbacks(it) }
        autoAnswerRunnable = null
        autoAnswerCountdown = 0
        CallManager.autoAnswerCountdown = -1
    }

    private fun onCallActive(call: Call) {
        // ACTIVE can be emitted again after details/audio-route changes or after
        // a hold. Greeting and recording must only be started once per call.
        if (callStartTimeMs > 0L) return
        callStartTimeMs = System.currentTimeMillis()

        // Cancel any pending auto-answer countdown since the call is now active
        cancelAutoAnswer()

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
                // Start recording BEFORE greeting so the greeting itself is captured
                startRecordingIfEnabled()
                // Small delay to let audio route stabilise after answer
                handler.postDelayed({
                    greetingManager.playGreetingForCall(
                        greeting = greetingText,
                        languageTag = languageTag,
                        engine = enginePkg
                    ) {
                        // Greeting finished — recording continues to capture caller's response
                    }
                }, 500)
            } else {
                // No greeting — start recording immediately
                startRecordingIfEnabled()
            }

            // Handle listen-in — respect silence/vibrate/DND mode
            val listenInMode = config.listenInMode
            val canActivateSpeaker = !isInSilentMode()
            when (listenInMode) {
                LISTEN_IN_AUTO -> {
                    if (canActivateSpeaker) {
                        handler.postDelayed({
                            CallManager.setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                            isListeningIn = true
                            showActiveCallNotification(true)
                        }, 600)
                    } else {
                        // Phone is in silent/vibrate/DND — don't activate speaker
                        isListeningIn = false
                        showActiveCallNotification(false)
                    }
                }
                LISTEN_IN_NOTIFICATION -> {
                    isListeningIn = false
                    showActiveCallNotification(false)
                }
            }
        } else {
            // Non-auto-answered call — start recording immediately
            startRecordingIfEnabled()
        }
    }

    private fun startRecordingIfEnabled() {
        if (!config.callRecordingEnabled || callRecordingManager.isCurrentlyRecording()) return

        cancelPendingRecordingStart()
        recordingStartRunnable = Runnable {
            recordingStartRunnable = null
            if (callEndingHandled || CallManager.getState() != Call.STATE_ACTIVE) return@Runnable

            if (!startRecordingForeground()) {
                callRecordingManager.setLastFailure(RecordingFailure.FOREGROUND_START_FAILED)
                return@Runnable
            }

            val number = currentCallNumber.ifEmpty { "unknown" }
            if (!callRecordingManager.startRecording(number)) {
                currentRecordingResult = null
                stopRecordingForeground()
            }
        }
        // A short delay lets Telecom finish the ACTIVE route transition without
        // blocking several seconds of the call as the previous source probe did.
        handler.postDelayed(recordingStartRunnable!!, 350)
    }

    private fun cancelPendingRecordingStart() {
        recordingStartRunnable?.let(handler::removeCallbacks)
        recordingStartRunnable = null
    }

    private fun onCallEnding(call: Call) {
        if (callEndingHandled) return
        callEndingHandled = true
        cancelPendingRecordingStart()

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
        stopRecordingForeground()
        val recordingFailure = if (config.callRecordingEnabled && recordingResult == null) {
            callRecordingManager.getLastFailure()
        } else {
            null
        }

        // Calculate duration
        val durationSeconds = if (callStartTimeMs > 0) {
            ((System.currentTimeMillis() - callStartTimeMs) / 1000).toInt()
        } else {
            call.getCallDuration()
        }

        // Show call summary notification
        if (durationSeconds > 0) {
            val name = currentCallName.ifEmpty { currentCallNumber }
            val summaryNotificationId = callSummaryManager.showCallSummary(
                contactName = name,
                phoneNumber = currentCallNumber,
                durationSeconds = durationSeconds,
                recordingResult = recordingResult,
                recordingFailure = recordingFailure,
            )

            // Trigger transcription automatically if recording exists and transcription is enabled
            if (recordingResult != null && config.callTranscriptionEnabled) {
                val transcriptionUri = recordingResult.uri
                    ?: recordingResult.file?.let { android.net.Uri.fromFile(it) }
                if (transcriptionUri != null) {
                    try {
                        val transcriptionIntent = TranscriptionService.createIntent(
                            this, transcriptionUri, recordingResult.name,
                            summaryNotificationId = summaryNotificationId,
                            contactName = name,
                            recordingFilePath = recordingResult.file?.absolutePath
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
        }

        // Reset state
        callStartTimeMs = 0L
        currentCallNumber = ""
        currentCallName = ""
        currentRecordingResult = null
        wasAutoAnswered = false
        isListeningIn = false
        currentSimId = ""
        autoAnswerRunnable = null
        autoAnswerCountdown = 0
    }

    private fun startRecordingForeground(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            callRecordingManager.setLastFailure(RecordingFailure.PERMISSION_MISSING)
            Log.e(TAG, "Cannot start recording foreground service without RECORD_AUDIO")
            return false
        }

        return try {
            createActiveCallChannel()
            val contentIntent = PendingIntent.getActivity(
                this,
                4,
                CallActivity.getStartIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(this, ACTIVE_CALL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_phone_vector)
                .setContentTitle(getString(R.string.call_recording))
                .setContentText(getString(R.string.call_recording_started))
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(RECORDING_FOREGROUND_ID, notification, types)
            } else {
                startForeground(RECORDING_FOREGROUND_ID, notification)
            }
            recordingForegroundActive = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not enter microphone foreground mode", e)
            false
        }
    }

    private fun stopRecordingForeground() {
        if (!recordingForegroundActive) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop recording foreground mode", e)
        } finally {
            recordingForegroundActive = false
        }
    }

    // ---- Silent / DND detection ----

    /**
     * Returns true if the phone is in a mode where the speaker should NOT be
     * activated automatically (vibrate-only, silent, or Do Not Disturb).
     */
    private fun isInSilentMode(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val ringerMode = audioManager.ringerMode
        if (ringerMode == AudioManager.RINGER_MODE_SILENT || ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            return true
        }

        // Also check DND (Do Not Disturb) on Android M+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val filter = nm.currentInterruptionFilter
            if (filter == NotificationManager.INTERRUPTION_FILTER_NONE ||
                filter == NotificationManager.INTERRUPTION_FILTER_ALARMS) {
                return true
            }
        }

        return false
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
