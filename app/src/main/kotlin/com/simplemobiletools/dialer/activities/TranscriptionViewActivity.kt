package com.simplemobiletools.dialer.activities

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.simplemobiletools.dialer.R
import com.simplemobiletools.dialer.helpers.EXTRA_CONTACT_NAME
import com.simplemobiletools.dialer.helpers.EXTRA_RECORDING_NAME
import com.simplemobiletools.dialer.helpers.TranscriptionManager

/**
 * Minimal activity that shows a transcription in a dialog.
 * Launched from notification actions when user taps "Show Transcription".
 */
class TranscriptionViewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: ""
        val recordingName = intent.getStringExtra(EXTRA_RECORDING_NAME) ?: ""

        val transcriptionManager = TranscriptionManager(this)
        val text = transcriptionManager.loadTranscription(recordingName)

        if (text.isNullOrBlank()) {
            Toast.makeText(this, R.string.transcription_not_available, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val title = if (contactName.isNotEmpty()) {
            getString(R.string.transcription_dialog_title, contactName)
        } else {
            getString(R.string.transcription_dialog_title_generic)
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setNeutralButton(R.string.copy_text) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Call Transcription", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, R.string.transcription_copied_to_clipboard, Toast.LENGTH_SHORT).show()
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }
}
