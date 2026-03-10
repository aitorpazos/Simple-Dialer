package com.simplemobiletools.dialer.services

import android.app.KeyguardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.simplemobiletools.dialer.activities.CallActivity
import com.simplemobiletools.dialer.extensions.config
import com.simplemobiletools.dialer.extensions.getCallDuration
import com.simplemobiletools.dialer.extensions.getStateCompat
import com.simplemobiletools.dialer.extensions.isOutgoing
import com.simplemobiletools.dialer.extensions.powerManager
import com.simplemobiletools.dialer.helpers.*
import java.io.File

class CallService : InCallService() {
    private val callNotificationManager by lazy { CallNotificationManager(this) }
    private val callRecordingManager by lazy { CallRecordingManager(this) }
    private val callSummaryManager by lazy { CallSummaryManager(this) }
    private val handler = Handler(Looper.getMainLooper())

    // Track per-call state
    private var currentCallNumber = ""
    private var currentCallName = ""
    private var currentRecordingFile: File? = null
    private var callStartTimeMs = 0L

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
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        callNotificationManager.cancelNotification()
    }

    private fun extractCallInfo(call: Call) {
        try {
            val handle = call.details?.handle?.toString() ?: ""
            currentCallNumber = if (handle.startsWith("tel:")) {
                android.net.Uri.decode(handle.substringAfter("tel:"))
            } else {
                ""
            }

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

        // Start recording if enabled
        if (config.callRecordingEnabled) {
            val number = currentCallNumber.ifEmpty { "unknown" }
            val success = callRecordingManager.startRecording(number)
            if (!success) {
                currentRecordingFile = null
            }
        }
    }

    private fun onCallEnding(call: Call) {
        // Stop recording
        val recordingFile = if (callRecordingManager.isCurrentlyRecording()) {
            callRecordingManager.stopRecording()
        } else {
            null
        }
        currentRecordingFile = recordingFile

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
                recordingFile = recordingFile
            )
        }

        // Reset state
        callStartTimeMs = 0L
        currentCallNumber = ""
        currentCallName = ""
        currentRecordingFile = null
    }
}
