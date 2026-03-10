package com.simplemobiletools.dialer.helpers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.simplemobiletools.dialer.R

class CallSummaryManager(private val context: Context) {
    companion object {
        private const val CHANNEL_ID = "call_summary_channel"
        private const val NOTIFICATION_ID_BASE = 5000
        private var notificationCounter = 0
    }

    fun showCallSummary(contactName: String, phoneNumber: String, durationSeconds: Int, recordingName: String?) {
        createNotificationChannel()

        val durationText = formatDuration(durationSeconds)
        val title = context.getString(R.string.call_summary_title, contactName.ifEmpty { phoneNumber })

        val contentLines = mutableListOf<String>()
        contentLines.add(context.getString(R.string.call_summary_duration, durationText))
        if (phoneNumber.isNotEmpty() && phoneNumber != contactName) {
            contentLines.add(context.getString(R.string.call_summary_number, phoneNumber))
        }
        if (recordingName != null) {
            contentLines.add(context.getString(R.string.call_summary_recorded, recordingName))
        }

        val bigTextStyle = NotificationCompat.BigTextStyle()
            .bigText(contentLines.joinToString("\n"))

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_phone_vector)
            .setContentTitle(title)
            .setContentText(contentLines.first())
            .setStyle(bigTextStyle)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(NOTIFICATION_ID_BASE + notificationCounter++, notification)
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
