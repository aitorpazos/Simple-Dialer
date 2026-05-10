package com.simplemobiletools.dialer.activities

import android.annotation.SuppressLint
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
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.*
import com.simplemobiletools.dialer.R
import com.simplemobiletools.dialer.databinding.ActivitySimulatedCallBinding
import com.simplemobiletools.dialer.extensions.config
import com.simplemobiletools.dialer.helpers.*
import com.simplemobiletools.dialer.receivers.ActiveCallActionReceiver
import com.simplemobiletools.dialer.services.TranscriptionService
import java.io.File
import kotlin.math.max
import kotlin.math.min

class SimulatedCallActivity : SimpleActivity() {
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
    private var callDuration = 0
    private var stopAnimation = false
    private var dragDownX = 0f

    private enum class State { RINGING, ACTIVE, ENDED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySimulatedCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        simId = intent.getStringExtra(EXTRA_SIM_ID)

        val cs = binding.callScreen
        cs.callerNameLabel.text = getString(R.string.simulated_call_caller)
        cs.callerNumber.text = getString(R.string.simulated_call_number)
        cs.callStatusLabel.text = getString(R.string.simulated_call_ringing)

        updateTextColors(cs.callHolder)
        initButtons()
        addLockScreenFlags()

        cs.incomingCallHolder.beVisible()
        cs.ongoingCallHolder.beGone()

        if (config.autoAnswerMode != AUTO_ANSWER_NONE) {
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

    @SuppressLint("ClickableViewAccessibility")
    private fun initButtons() {
        val cs = binding.callScreen

        if (config.disableSwipeToAnswer) {
            cs.callDraggable.beGone()
            cs.callDraggableBackground.beGone()
            cs.callLeftArrow.beGone()
            cs.callRightArrow.beGone()

            cs.callDecline.setOnClickListener { endCall() }
            cs.callAccept.setOnClickListener { answerCall() }
        } else {
            handleSwipe()
        }

        cs.callEnd.setOnClickListener { endCall() }

        // Disable real-call-only buttons
        cs.callToggleMicrophone.setOnClickListener { }
        cs.callToggleSpeaker.setOnClickListener { }
        cs.callDialpad.setOnClickListener { }
        cs.callToggleHold.setOnClickListener { }
        cs.callAdd.setOnClickListener { }

        cs.autoAnswerSkipButton.setOnClickListener {
            cancelAutoAnswerCountdown()
        }

        val bgColor = getProperBackgroundColor()
        val inactiveColor = getProperTextColor().adjustAlpha(0.10f)
        arrayOf(
            cs.callToggleMicrophone, cs.callToggleSpeaker, cs.callDialpad,
            cs.callToggleHold, cs.callAdd
        ).forEach {
            it.applyColorFilter(bgColor.getContrastColor())
            it.background.applyColorFilter(inactiveColor)
        }

        cs.controlsSingleCall.beVisible()
        cs.controlsTwoCalls.beGone()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleSwipe() {
        val cs = binding.callScreen
        var minDragX = 0f
        var maxDragX = 0f
        var initialDraggableX = 0f
        var initialLeftArrowX = 0f
        var initialRightArrowX = 0f
        var initialLeftArrowScaleX = 0f
        var initialLeftArrowScaleY = 0f
        var initialRightArrowScaleX = 0f
        var initialRightArrowScaleY = 0f
        var leftArrowTranslation = 0f
        var rightArrowTranslation = 0f

        val isRtl = isRTLLayout
        cs.callAccept.onGlobalLayout {
            minDragX = if (isRtl) cs.callAccept.left.toFloat() else cs.callDecline.left.toFloat()
            maxDragX = if (isRtl) cs.callDecline.left.toFloat() else cs.callAccept.left.toFloat()
            initialDraggableX = cs.callDraggable.left.toFloat()
            initialLeftArrowX = cs.callLeftArrow.x
            initialRightArrowX = cs.callRightArrow.x
            initialLeftArrowScaleX = cs.callLeftArrow.scaleX
            initialLeftArrowScaleY = cs.callLeftArrow.scaleY
            initialRightArrowScaleX = cs.callRightArrow.scaleX
            initialRightArrowScaleY = cs.callRightArrow.scaleY
            leftArrowTranslation = if (isRtl) cs.callAccept.x else -cs.callDecline.x
            rightArrowTranslation = if (isRtl) -cs.callAccept.x else cs.callDecline.x

            if (isRtl) {
                cs.callLeftArrow.setImageResource(R.drawable.ic_chevron_right_vector)
                cs.callRightArrow.setImageResource(R.drawable.ic_chevron_left_vector)
            }

            cs.callLeftArrow.applyColorFilter(getColor(R.color.md_red_400))
            cs.callRightArrow.applyColorFilter(getColor(R.color.md_green_400))

            startArrowAnimation(cs.callLeftArrow, initialLeftArrowX, initialLeftArrowScaleX, initialLeftArrowScaleY, leftArrowTranslation)
            startArrowAnimation(cs.callRightArrow, initialRightArrowX, initialRightArrowScaleX, initialRightArrowScaleY, rightArrowTranslation)
        }

        cs.callDraggable.drawable.mutate().setTint(getProperTextColor())
        cs.callDraggableBackground.drawable.mutate().setTint(getProperTextColor())

        var lock = false
        cs.callDraggable.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragDownX = event.x
                    cs.callDraggableBackground.animate().alpha(0f)
                    stopAnimation = true
                    cs.callLeftArrow.animate().alpha(0f)
                    cs.callRightArrow.animate().alpha(0f)
                    lock = false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragDownX = 0f
                    cs.callDraggable.animate().x(initialDraggableX).withEndAction {
                        cs.callDraggableBackground.animate().alpha(0.2f)
                    }
                    cs.callDraggable.setImageDrawable(getDrawable(R.drawable.ic_phone_down_vector))
                    cs.callDraggable.drawable.mutate().setTint(getProperTextColor())
                    cs.callLeftArrow.animate().alpha(1f)
                    cs.callRightArrow.animate().alpha(1f)
                    stopAnimation = false
                    startArrowAnimation(cs.callLeftArrow, initialLeftArrowX, initialLeftArrowScaleX, initialLeftArrowScaleY, leftArrowTranslation)
                    startArrowAnimation(cs.callRightArrow, initialRightArrowX, initialRightArrowScaleX, initialRightArrowScaleY, rightArrowTranslation)
                }
                MotionEvent.ACTION_MOVE -> {
                    cs.callDraggable.x = min(maxDragX, max(minDragX, event.rawX - dragDownX))
                    when {
                        cs.callDraggable.x >= maxDragX - 50f -> {
                            if (!lock) {
                                lock = true
                                cs.callDraggable.performHapticFeedback()
                                if (isRtl) endCall() else answerCall()
                            }
                        }
                        cs.callDraggable.x <= minDragX + 50f -> {
                            if (!lock) {
                                lock = true
                                cs.callDraggable.performHapticFeedback()
                                if (isRtl) answerCall() else endCall()
                            }
                        }
                        cs.callDraggable.x > initialDraggableX -> {
                            lock = false
                            val drawableRes = if (isRtl) R.drawable.ic_phone_down_red_vector else R.drawable.ic_phone_green_vector
                            cs.callDraggable.setImageDrawable(getDrawable(drawableRes))
                        }
                        cs.callDraggable.x <= initialDraggableX -> {
                            lock = false
                            val drawableRes = if (isRtl) R.drawable.ic_phone_green_vector else R.drawable.ic_phone_down_red_vector
                            cs.callDraggable.setImageDrawable(getDrawable(drawableRes))
                        }
                    }
                }
            }
            true
        }
    }

    private fun startArrowAnimation(arrow: ImageView, initialX: Float, initialScaleX: Float, initialScaleY: Float, translation: Float) {
        arrow.apply {
            alpha = 1f
            x = initialX
            scaleX = initialScaleX
            scaleY = initialScaleY
            animate()
                .alpha(0f)
                .translationX(translation)
                .scaleXBy(-0.5f)
                .scaleYBy(-0.5f)
                .setDuration(1000)
                .withEndAction {
                    if (!stopAnimation) {
                        startArrowAnimation(this, initialX, initialScaleX, initialScaleY, translation)
                    }
                }
        }
    }

    private fun startAutoAnswerCountdown() {
        val delaySeconds = config.autoAnswerDelaySeconds
        autoAnswerCountdown = if (delaySeconds <= 0) 3 else delaySeconds

        val cs = binding.callScreen
        cs.autoAnswerCountdownLabel.text = getString(R.string.auto_answer_countdown, autoAnswerCountdown)
        cs.autoAnswerCountdownLabel.beVisible()
        cs.autoAnswerSkipButton.beVisible()

        handler.postDelayed(object : Runnable {
            override fun run() {
                if (state != State.RINGING) return
                autoAnswerCountdown--
                if (autoAnswerCountdown <= 0) {
                    answerCall()
                } else {
                    cs.autoAnswerCountdownLabel.text = getString(R.string.auto_answer_countdown, autoAnswerCountdown)
                    handler.postDelayed(this, 1000)
                }
            }
        }, 1000)
    }

    private fun cancelAutoAnswerCountdown() {
        autoAnswerCountdown = 0
        val cs = binding.callScreen
        cs.autoAnswerCountdownLabel.beGone()
        cs.autoAnswerSkipButton.beGone()
        cs.callStatusLabel.text = getString(R.string.simulated_call_ringing)
    }

    private fun answerCall() {
        if (state != State.RINGING) return
        state = State.ACTIVE

        val cs = binding.callScreen
        cs.incomingCallHolder.beGone()
        cs.ongoingCallHolder.beVisible()
        cs.autoAnswerCountdownLabel.beGone()
        cs.autoAnswerSkipButton.beGone()

        callDuration = 0
        val callDurationHandler = Handler(Looper.getMainLooper())
        val updateDurationTask = object : Runnable {
            override fun run() {
                if (state == State.ACTIVE) {
                    callDuration++
                    cs.callStatusLabel.text = callDuration.getFormattedDuration()
                    callDurationHandler.postDelayed(this, 1000)
                }
            }
        }
        cs.callStatusLabel.text = getString(R.string.simulated_call_active)
        callDurationHandler.postDelayed(updateDurationTask, 1000)

        // Play greeting
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
            startRecordingIfEnabled()

            val greetingDir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "CallRecordings")
            greetingDir.mkdirs()
            greetingAudioFile = File(greetingDir, "greeting_simulated_${System.currentTimeMillis()}.wav")
            synthGreetingManager.synthesizeToFile(
                outputFile = greetingAudioFile!!,
                greeting = greeting,
                languageTag = languageTag,
                engine = enginePkg
            ) { }

            handler.postDelayed({
                greetingManager.playGreetingPreview(
                    greeting = greeting,
                    languageTag = languageTag,
                    engine = enginePkg
                ) { }
            }, 500)
        } else {
            startRecordingIfEnabled()
        }

        // Handle listen-in
        when (config.listenInMode) {
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

        handler.postDelayed({ endCall() }, AUTO_DISCONNECT_MS)
    }

    private fun endCall() {
        if (state == State.ENDED) return
        state = State.ENDED

        handler.removeCallbacksAndMessages(null)
        greetingManager.stopGreeting()
        dismissListenNotification()

        val cs = binding.callScreen
        cs.callStatusLabel.text = getString(R.string.simulated_call_ended)
        cs.incomingCallHolder.beGone()
        cs.ongoingCallHolder.beGone()

        if (callRecordingManager.isCurrentlyRecording()) {
            recordingResult = callRecordingManager.stopRecording()
        }

        if (callDuration > 0) {
            CallSummaryManager(this).showCallSummary(
                contactName = getString(R.string.simulated_call_caller),
                phoneNumber = getString(R.string.simulated_call_number),
                durationSeconds = callDuration,
                recordingResult = recordingResult
            )
        }

        if (config.callTranscriptionEnabled) {
            val transcriptionUri: Uri?
            val transcriptionName: String

            if (greetingAudioFile != null && greetingAudioFile!!.exists() && greetingAudioFile!!.length() > 0) {
                transcriptionUri = Uri.fromFile(greetingAudioFile!!)
                transcriptionName = greetingAudioFile!!.name
            } else if (recordingResult != null) {
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(transcriptionIntent)
                    } else {
                        startService(transcriptionIntent)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

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

    @SuppressLint("NewApi")
    private fun addLockScreenFlags() {
        if (isOreoMr1Plus()) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        if (isOreoPlus()) {
            (getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).requestDismissKeyguard(this, null)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
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
