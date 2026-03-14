package com.simplemobiletools.dialer.receivers

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.simplemobiletools.dialer.R
import com.simplemobiletools.dialer.helpers.*

class CallSummaryActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        val recordingUriStr = intent.getStringExtra(EXTRA_RECORDING_URI)
        val recordingUri = if (recordingUriStr != null) Uri.parse(recordingUriStr) else null
        val recordingName = intent.getStringExtra(EXTRA_RECORDING_NAME)
        val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: ""

        when (intent.action) {
            ACTION_PLAY_RECORDING -> playRecording(context, recordingUri)
            ACTION_SHARE_RECORDING -> shareRecording(context, recordingUri, recordingName)
            ACTION_SHARE_TRANSCRIPTION -> shareTranscription(context, contactName)
            ACTION_SHARE_CHOOSER -> showShareChooser(context, recordingUri, recordingName, contactName)
            ACTION_SHOW_TRANSCRIPTION -> showTranscription(context, contactName)
        }

        // Dismiss the notification
        if (notificationId >= 0) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notificationId)
        }
    }

    private fun playRecording(context: Context, uri: Uri?) {
        if (uri == null) {
            Toast.makeText(context, R.string.recording_not_found, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val playIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "audio/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(playIntent)
        } catch (e: Exception) {
            Toast.makeText(context, R.string.no_app_to_play_recording, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareRecording(context: Context, uri: Uri?, name: String?) {
        if (uri == null) {
            Toast.makeText(context, R.string.recording_not_found, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, name ?: "Call Recording")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(shareIntent, context.getString(R.string.share_recording))
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, R.string.share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareTranscription(context: Context, contactName: String) {
        // Transcription is a placeholder — for now show a toast
        Toast.makeText(context, R.string.transcription_not_available, Toast.LENGTH_SHORT).show()
    }

    private fun showShareChooser(context: Context, recordingUri: Uri?, recordingName: String?, contactName: String) {
        // Show a chooser dialog where user can pick sharing the recording or transcription
        // Since we can't show a dialog from a BroadcastReceiver easily, we launch an activity
        // For now, share the recording directly (transcription can be added later)
        if (recordingUri != null) {
            shareRecording(context, recordingUri, recordingName)
        } else {
            shareTranscription(context, contactName)
        }
    }

    private fun showTranscription(context: Context, contactName: String) {
        // Transcription is a placeholder — for now show a toast
        Toast.makeText(context, R.string.transcription_not_available, Toast.LENGTH_SHORT).show()
    }
}
