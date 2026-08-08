package com.simplemobiletools.dialer.dialogs

import androidx.appcompat.app.AlertDialog
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.extensions.getAlertDialogBuilder
import com.simplemobiletools.commons.extensions.setupDialogStuff
import com.simplemobiletools.commons.extensions.viewBinding
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import com.simplemobiletools.dialer.adapters.GroupedCallsAdapter
import com.simplemobiletools.dialer.databinding.DialogShowGroupedCallsBinding
import com.simplemobiletools.dialer.helpers.*

class ShowGroupedCallsDialog(val activity: BaseSimpleActivity, callIds: ArrayList<Int>, displayName: String = "") {
    private var dialog: AlertDialog? = null
    private val binding by activity.viewBinding(DialogShowGroupedCallsBinding::inflate)
    private val transcriptionManager = TranscriptionManager(activity)

    init {
        // Show contact name/number as dialog header
        if (displayName.isNotEmpty()) {
            binding.groupedCallsHeader.text = displayName
        } else {
            binding.groupedCallsHeader.visibility = android.view.View.GONE
        }

        RecentsHelper(activity).getRecentCalls(false) { allRecents ->
            ensureBackgroundThread {
                val recents = ArrayList(allRecents.filter { it.id in callIds })
                val recordingsByCallId = transcriptionManager.findRecordingsForCalls(recents)
                val transcribedRecordings = recordingsByCallId.values
                    .filter(transcriptionManager::hasTranscription)
                    .toSet()

                activity.runOnUiThread {
                    GroupedCallsAdapter(
                        activity,
                        recents,
                        transcriptionManager,
                        recordingsByCallId,
                        transcribedRecordings
                    ) {
                        dialog?.dismiss()
                    }.apply {
                        binding.selectGroupedCallsList.adapter = this
                    }
                }
            }
        }

        activity.getAlertDialogBuilder()
            .apply {
                activity.setupDialogStuff(binding.root, this) { alertDialog ->
                    dialog = alertDialog
                }
            }
    }
}
