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
            ACTION_SHARE_TRANSCRIPTION -> shareTranscription(context, recordingName, contactName)
            ACTION_SHARE_CHOOSER -> showShareChooser(context, recordingUri, recordingName, contactName)
            ACTION_SHOW_TRANSCRIPTION -> showTranscription(context, recordingName, contactName)
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

    private fun shareTranscription(context: Context, recordingName: String?, contactName: String) {
        if (recordingName == null) {
            Toast.makeText(context, R.string.transcription_not_available, Toast.LENGTH_SHORT).show()
            return
        }

        val transcriptionManager = TranscriptionManager(context)
        val text = transcriptionManager.loadTranscription(recordingName)

        if (text.isNullOrBlank()) {
            Toast.makeText(context, R.string.transcription_not_available, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val subject = if (contactName.isNotEmpty()) {
                context.getString(R.string.transcription_share_subject, contactName)
            } else {
                context.getString(R.string.transcription_share_subject_generic)
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(shareIntent, context.getString(R.string.share_transcription))
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, R.string.share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showShareChooser(context: Context, recordingUri: Uri?, recordingName: String?, contactName: String) {
        // Share the recording directly (transcription can be shared via separate action)
        if (recordingUri != null) {
            shareRecording(context, recordingUri, recordingName)
        } else {
            shareTranscription(context, recordingName, contactName)
        }
    }

    private fun showTranscription(context: Context, recordingName: String?, contactName: String) {
        if (recordingName == null) {
            Toast.makeText(context, R.string.transcription_not_available, Toast.LENGTH_SHORT).show()
            return
        }

        val transcriptionManager = TranscriptionManager(context)
        val text = transcriptionManager.loadTranscription(recordingName)

        if (text.isNullOrBlank()) {
            Toast.makeText(context, R.string.transcription_not_available, Toast.LENGTH_SHORT).show()
            return
        }

        // Show transcription in a dialog via an activity (can't show dialog from receiver)
        // Use a simple alert dialog activity
        try {
            val viewIntent = Intent(context, com.simplemobiletools.dialer.activities.TranscriptionViewActivity::class.java).apply {
                putExtra(EXTRA_CONTACT_NAME, contactName)
                putExtra(EXTRA_RECORDING_NAME, recordingName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(viewIntent)
        } catch (e: Exception) {
            // Fallback: copy to clipboard and show toast
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Call Transcription", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, R.string.transcription_copied_to_clipboard, Toast.LENGTH_LONG).show()
        }
    }
}
