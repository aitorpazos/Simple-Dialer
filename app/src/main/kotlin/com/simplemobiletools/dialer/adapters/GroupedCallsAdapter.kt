package com.simplemobiletools.dialer.adapters

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.provider.CallLog.Calls
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.dialer.R
import com.simplemobiletools.dialer.databinding.ItemGroupedCallBinding
import com.simplemobiletools.dialer.helpers.TranscriptionManager
import com.simplemobiletools.dialer.models.RecentCall
import com.simplemobiletools.dialer.services.TranscriptionService

class GroupedCallsAdapter(
    private val activity: BaseSimpleActivity,
    private val calls: List<RecentCall>,
    private val transcriptionManager: TranscriptionManager,
    private val recordingsByCallId: Map<Int, String>,
    private val transcribedRecordings: Set<String>,
    private val onDismissDialog: () -> Unit
) : RecyclerView.Adapter<GroupedCallsAdapter.ViewHolder>() {

    companion object {
        private const val TAG = "GroupedCallsAdapter"
    }

    private var expandedPosition = -1
    private val textColor = activity.getProperTextColor()
    private val redColor = androidx.core.content.ContextCompat.getColor(activity, R.color.md_red_700)

    private val outgoingCallIcon: Drawable =
        activity.resources.getColoredDrawableWithColor(R.drawable.ic_outgoing_call_vector, textColor)
    private val incomingCallIcon: Drawable =
        activity.resources.getColoredDrawableWithColor(R.drawable.ic_incoming_call_vector, textColor)
    private val incomingMissedCallIcon: Drawable =
        activity.resources.getColoredDrawableWithColor(R.drawable.ic_incoming_call_vector, redColor)

    inner class ViewHolder(val binding: ItemGroupedCallBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGroupedCallBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val call = calls[position]
        val binding = holder.binding
        val isExpanded = position == expandedPosition

        // Call type icon
        val drawable = when (call.type) {
            Calls.OUTGOING_TYPE -> outgoingCallIcon
            Calls.MISSED_TYPE -> incomingMissedCallIcon
            else -> incomingCallIcon
        }
        binding.groupedCallType.setImageDrawable(drawable)

        // Date/time
        binding.groupedCallDateTime.text = call.startTS.formatDateOrTime(activity, false, true)
        binding.groupedCallDateTime.setTextColor(if (call.type == Calls.MISSED_TYPE) redColor else textColor)

        // Duration
        if (call.type != Calls.MISSED_TYPE && call.type != Calls.REJECTED_TYPE && call.duration > 0) {
            binding.groupedCallDuration.visibility = View.VISIBLE
            binding.groupedCallDuration.text = call.duration.getFormattedDuration()
            binding.groupedCallDuration.setTextColor(textColor)
        } else {
            binding.groupedCallDuration.visibility = View.GONE
        }

        // Recording metadata is preloaded off the main thread before the adapter is attached.
        val recordingName = recordingsByCallId[call.id]
        val hasRecording = recordingName != null
        val hasTranscription = recordingName != null && transcribedRecordings.contains(recordingName)

        // Expand indicator: only show if there's a recording
        binding.groupedCallIndicator.visibility = if (hasRecording) View.VISIBLE else View.INVISIBLE
        binding.groupedCallIndicator.setColorFilter(textColor)

        // Rotate indicator: right (0°) when collapsed, down (90°) when expanded
        binding.groupedCallIndicator.rotation = if (isExpanded) 90f else 0f

        // Actions panel
        binding.groupedCallActions.visibility = if (isExpanded && hasRecording) View.VISIBLE else View.GONE

        if (isExpanded && hasRecording) {
            setupActions(binding, call, recordingName!!, hasTranscription)
        }

        // Row click to expand/collapse
        binding.groupedCallRow.setOnClickListener {
            if (!hasRecording) return@setOnClickListener

            val adapterPosition = holder.bindingAdapterPosition
            if (adapterPosition == RecyclerView.NO_POSITION) return@setOnClickListener

            val previousExpanded = expandedPosition
            expandedPosition = if (expandedPosition == adapterPosition) -1 else adapterPosition

            if (previousExpanded >= 0) notifyItemChanged(previousExpanded)
            if (expandedPosition >= 0) notifyItemChanged(expandedPosition)
        }
    }

    override fun getItemCount() = calls.size

    private fun setupActions(
        binding: ItemGroupedCallBinding,
        call: RecentCall,
        recordingName: String,
        hasTranscription: Boolean
    ) {
        // Play recording
        binding.actionPlayRecording.visibility = View.VISIBLE
        binding.actionPlayRecordingIcon.setColorFilter(textColor)
        binding.actionPlayRecordingLabel.setTextColor(textColor)
        binding.actionPlayRecording.setOnClickListener {
            playRecording(recordingName)
        }

        // Share recording
        binding.actionShareRecording.visibility = View.VISIBLE
        binding.actionShareRecordingIcon.setColorFilter(textColor)
        binding.actionShareRecordingLabel.setTextColor(textColor)
        binding.actionShareRecording.setOnClickListener {
            shareRecording(recordingName, call)
        }

        if (hasTranscription) {
            // Show transcription
            binding.actionShowTranscription.visibility = View.VISIBLE
            binding.actionShowTranscriptionIcon.setColorFilter(textColor)
            binding.actionShowTranscriptionLabel.setTextColor(textColor)
            binding.actionShowTranscription.setOnClickListener {
                showTranscription(binding, recordingName, call)
            }

            // Share transcription
            binding.actionShareTranscription.visibility = View.VISIBLE
            binding.actionShareTranscriptionIcon.setColorFilter(textColor)
            binding.actionShareTranscriptionLabel.setTextColor(textColor)
            binding.actionShareTranscription.setOnClickListener {
                shareTranscription(recordingName, call)
            }

            // Hide transcribe button
            binding.actionTranscribe.visibility = View.GONE
        } else {
            // Show transcribe button
            binding.actionTranscribe.visibility = View.VISIBLE
            binding.actionTranscribeIcon.setColorFilter(textColor)
            binding.actionTranscribeLabel.setTextColor(textColor)
            binding.actionTranscribe.setOnClickListener {
                startTranscription(recordingName, call)
            }

            // Hide transcription-related actions
            binding.actionShowTranscription.visibility = View.GONE
            binding.actionShareTranscription.visibility = View.GONE
        }

        // Reset transcription viewer
        binding.actionTranscriptionViewer.visibility = View.GONE
    }

    private fun playRecording(recordingName: String) {
        val uri = transcriptionManager.getShareableRecordingUri(recordingName)
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

    @Suppress("UNUSED_PARAMETER")
    private fun shareRecording(recordingName: String, call: RecentCall) {
        val uri = transcriptionManager.getShareableRecordingUri(recordingName)
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

    private fun startTranscription(recordingName: String, call: RecentCall) {
        try {
            val intent = Intent(activity, TranscriptionService::class.java).apply {
                putExtra(TranscriptionService.EXTRA_RECORDING_NAME, recordingName)
                putExtra(TranscriptionService.EXTRA_CONTACT_NAME, call.name)
            }
            androidx.core.content.ContextCompat.startForegroundService(activity, intent)
            Toast.makeText(activity, R.string.transcription_started, Toast.LENGTH_SHORT).show()
            onDismissDialog()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start transcription service", e)
            Toast.makeText(activity, R.string.share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTranscription(binding: ItemGroupedCallBinding, recordingName: String, call: RecentCall) {
        val text = transcriptionManager.loadTranscription(recordingName)
        if (text.isNullOrBlank()) {
            Toast.makeText(activity, R.string.transcription_not_available, Toast.LENGTH_SHORT).show()
            return
        }

        binding.actionTranscriptionViewer.visibility = View.VISIBLE
        binding.actionTranscriptionLabel.setTextColor(textColor)
        binding.actionTranscriptionLabel.text = if (call.name.isNotEmpty()) {
            activity.getString(R.string.transcription_dialog_title, call.name)
        } else {
            activity.getString(R.string.transcription_dialog_title_generic)
        }
        binding.actionTranscriptionText.text = text
        binding.actionTranscriptionText.setTextColor(textColor)

        // Copy button
        binding.actionCopyTranscriptionIcon.setColorFilter(textColor)
        binding.actionCopyTranscriptionLabel.setTextColor(textColor)
        binding.actionCopyTranscription.setOnClickListener {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Call Transcription", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(activity, R.string.transcription_copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }

        // Hide the "Show transcription" button since it's now visible
        binding.actionShowTranscription.visibility = View.GONE
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
