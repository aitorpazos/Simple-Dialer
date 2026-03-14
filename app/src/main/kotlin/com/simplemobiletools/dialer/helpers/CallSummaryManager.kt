package com.simplemobiletools.dialer.helpers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.simplemobiletools.dialer.R
import com.simplemobiletools.dialer.extensions.config
import com.simplemobiletools.dialer.receivers.CallSummaryActionReceiver
import java.io.File

class CallSummaryManager(private val context: Context) {
    companion object {
        private const val CHANNEL_ID = "call_summary_channel"
        private const val NOTIFICATION_ID_BASE = 5000
        private var notificationCounter = 0
    }

    fun showCallSummary(
        contactName: String,
        phoneNumber: String,
        durationSeconds: Int,
        recordingResult: RecordingResult?
    ) {
        createNotificationChannel()

        val durationText = formatDuration(durationSeconds)
        val title = context.getString(R.string.call_summary_title, contactName.ifEmpty { phoneNumber })

        val contentLines = mutableListOf<String>()
        contentLines.add(context.getString(R.string.call_summary_duration, durationText))
        if (phoneNumber.isNotEmpty() && phoneNumber != contactName) {
            contentLines.add(context.getString(R.string.call_summary_number, phoneNumber))
        }
        if (recordingResult != null) {
            contentLines.add(context.getString(R.string.call_summary_recorded, recordingResult.name))
        }

        val bigTextStyle = NotificationCompat.BigTextStyle()
            .bigText(contentLines.joinToString("\n"))

        val notificationId = NOTIFICATION_ID_BASE + notificationCounter++
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_phone_vector)
            .setContentTitle(title)
            .setContentText(contentLines.first())
            .setStyle(bigTextStyle)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        // Add configurable actions
        val enabledActions = context.config.callEndNotificationActions
        val hasRecording = recordingResult?.uri != null

        if (hasRecording) {
            val recordingUri = getShareableUri(recordingResult!!)

            if (enabledActions and NOTIF_ACTION_PLAY_RECORDING != 0) {
                val playIntent = createActionIntent(
                    ACTION_PLAY_RECORDING, notificationId, recordingUri,
                    recordingResult.name, contactName
                )
                val playPi = PendingIntent.getBroadcast(
                    context, notificationId * 10 + 1, playIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(R.drawable.ic_phone_vector, context.getString(R.string.notif_action_play_recording), playPi)
            }

            if (enabledActions and NOTIF_ACTION_SHARE != 0) {
                val shareIntent = createActionIntent(
                    ACTION_SHARE_CHOOSER, notificationId, recordingUri,
                    recordingResult.name, contactName
                )
                val sharePi = PendingIntent.getBroadcast(
                    context, notificationId * 10 + 2, shareIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(R.drawable.ic_phone_vector, context.getString(R.string.notif_action_share), sharePi)
            }

            if (enabledActions and NOTIF_ACTION_SHARE_RECORDING != 0) {
                val shareRecIntent = createActionIntent(
                    ACTION_SHARE_RECORDING, notificationId, recordingUri,
                    recordingResult.name, contactName
                )
                val shareRecPi = PendingIntent.getBroadcast(
                    context, notificationId * 10 + 3, shareRecIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(R.drawable.ic_phone_vector, context.getString(R.string.notif_action_share_recording), shareRecPi)
            }
        }

        // Transcription actions don't require a recording
        if (enabledActions and NOTIF_ACTION_SHARE_TRANSCRIPTION != 0) {
            val shareTransIntent = createActionIntent(
                ACTION_SHARE_TRANSCRIPTION, notificationId, null,
                recordingResult?.name, contactName
            )
            val shareTransPi = PendingIntent.getBroadcast(
                context, notificationId * 10 + 4, shareTransIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_phone_vector, context.getString(R.string.notif_action_share_transcription), shareTransPi)
        }

        if (enabledActions and NOTIF_ACTION_SHOW_TRANSCRIPTION != 0) {
            val showTransIntent = createActionIntent(
                ACTION_SHOW_TRANSCRIPTION, notificationId, null,
                recordingResult?.name, contactName
            )
            val showTransPi = PendingIntent.getBroadcast(
                context, notificationId * 10 + 5, showTransIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_phone_vector, context.getString(R.string.notif_action_show_transcription), showTransPi)
        }

        notificationManager.notify(notificationId, builder.build())
    }

    private fun getShareableUri(result: RecordingResult): Uri? {
        // If it's already a content:// URI (SAF), use it directly
        result.uri?.let { uri ->
            if (uri.scheme == "content") return uri
        }
        // For file:// URIs, convert via FileProvider for sharing
        result.file?.let { file ->
            if (file.exists()) {
                return try {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                } catch (e: Exception) {
                    result.uri // fallback to original
                }
            }
        }
        return result.uri
    }

    private fun createActionIntent(
        action: String,
        notificationId: Int,
        recordingUri: Uri?,
        recordingName: String?,
        contactName: String
    ): Intent {
        return Intent(context, CallSummaryActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            if (recordingUri != null) {
                putExtra(EXTRA_RECORDING_URI, recordingUri.toString())
            }
            if (recordingName != null) {
                putExtra(EXTRA_RECORDING_NAME, recordingName)
            }
            putExtra(EXTRA_CONTACT_NAME, contactName)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.call_summary_channel_name)
            val descriptionText = context.getString(R.string.call_summary_channel_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format("%d:%02d", minutes, secs)
        }
    }
}
