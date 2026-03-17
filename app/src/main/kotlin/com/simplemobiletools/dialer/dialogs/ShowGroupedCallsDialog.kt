package com.simplemobiletools.dialer.dialogs

import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.extensions.getAlertDialogBuilder
import com.simplemobiletools.commons.extensions.getProperTextColor
import com.simplemobiletools.commons.extensions.setupDialogStuff
import com.simplemobiletools.commons.extensions.viewBinding
import com.simplemobiletools.dialer.R
import com.simplemobiletools.dialer.activities.SimpleActivity
import com.simplemobiletools.dialer.activities.TranscriptionViewActivity
import com.simplemobiletools.dialer.adapters.RecentCallsAdapter
import com.simplemobiletools.dialer.databinding.DialogShowGroupedCallsBinding
import com.simplemobiletools.dialer.helpers.*
import com.simplemobiletools.dialer.models.RecentCall

class ShowGroupedCallsDialog(val activity: BaseSimpleActivity, callIds: ArrayList<Int>) {
    private var dialog: AlertDialog? = null
    private val binding by activity.viewBinding(DialogShowGroupedCallsBinding::inflate)
    private val transcriptionManager = TranscriptionManager(activity)

    init {
        RecentsHelper(activity).getRecentCalls(false) { allRecents ->
            val recents = allRecents.filter { callIds.contains(it.id) }.toMutableList() as ArrayList<RecentCall>
            activity.runOnUiThread {
                RecentCallsAdapter(activity as SimpleActivity, recents, binding.selectGroupedCallsList, null, false) {
                }.apply {
                    binding.selectGroupedCallsList.adapter = this
                }

                setupRecordingActions(recents)
            }
        }

        activity.getAlertDialogBuilder()
            .apply {
                activity.setupDialogStuff(binding.root, this) { alertDialog ->
                    dialog = alertDialog
                }
            }
    }

    private fun setupRecordingActions(recents: List<RecentCall>) {
        // Use the first (most recent) call to find associated recording
        val call = recents.firstOrNull() ?: return

        val recordingName = transcriptionManager.findRecordingForCall(call.phoneNumber, call.startTS)
        val hasRecording = recordingName != null
        val hasTranscription = hasRecording && transcriptionManager.hasTranscription(recordingName!!)

        if (!hasRecording && !hasTranscription) return

        val textColor = activity.getProperTextColor()

        binding.recordingActionsHolder.visibility = View.VISIBLE

        if (hasRecording) {
            binding.btnPlayRecording.visibility = View.VISIBLE
            binding.btnPlayRecordingIcon.setColorFilter(textColor)
            binding.btnPlayRecordingLabel.setTextColor(textColor)
            binding.btnPlayRecording.setOnClickListener {
                playRecording(recordingName!!)
            }

            binding.btnShareRecording.visibility = View.VISIBLE
            binding.btnShareRecordingIcon.setColorFilter(textColor)
            binding.btnShareRecordingLabel.setTextColor(textColor)
            binding.btnShareRecording.setOnClickListener {
                shareRecording(recordingName!!, call)
            }
        }

        if (hasTranscription) {
            binding.btnShowTranscription.visibility = View.VISIBLE
            binding.btnShowTranscriptionIcon.setColorFilter(textColor)
            binding.btnShowTranscriptionLabel.setTextColor(textColor)
            binding.btnShowTranscription.setOnClickListener {
                showTranscription(recordingName!!, call)
            }

            binding.btnShareTranscription.visibility = View.VISIBLE
            binding.btnShareTranscriptionIcon.setColorFilter(textColor)
            binding.btnShareTranscriptionLabel.setTextColor(textColor)
            binding.btnShareTranscription.setOnClickListener {
                shareTranscription(recordingName!!, call)
            }
        }
    }

    private fun playRecording(recordingName: String) {
        val uri = transcriptionManager.getRecordingUriByName(recordingName)
        if (uri == null) {
            Toast.makeText(activity, R.string.recording_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "audio/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(activity, R.string.no_app_to_play_recording, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareRecording(recordingName: String, call: RecentCall) {
        val uri = transcriptionManager.getRecordingUriByName(recordingName)
        if (uri == null) {
            Toast.makeText(activity, R.string.recording_not_found, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, recordingName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, activity.getString(R.string.share_recording))
            activity.startActivity(chooser)
        } catch (_: Exception) {
            Toast.makeText(activity, R.string.share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTranscription(recordingName: String, call: RecentCall) {
        val text = transcriptionManager.loadTranscription(recordingName)
        if (text.isNullOrBlank()) {
            Toast.makeText(activity, R.string.transcription_not_available, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(activity, TranscriptionViewActivity::class.java).apply {
                putExtra(EXTRA_CONTACT_NAME, call.name)
                putExtra(EXTRA_RECORDING_NAME, recordingName)
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(activity, R.string.transcription_not_available, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareTranscription(recordingName: String, call: RecentCall) {
        val text = transcriptionManager.loadTranscription(recordingName)
        if (text.isNullOrBlank()) {
            Toast.makeText(activity, R.string.transcription_not_available, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val subject = if (call.name.isNotEmpty()) {
                activity.getString(R.string.transcription_share_subject, call.name)
            } else {
                activity.getString(R.string.transcription_share_subject_generic)
            }
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            val chooser = Intent.createChooser(shareIntent, activity.getString(R.string.share_transcription))
            activity.startActivity(chooser)
        } catch (_: Exception) {
            Toast.makeText(activity, R.string.share_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
