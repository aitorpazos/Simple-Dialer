package com.simplemobiletools.dialer.dialogs

import androidx.appcompat.app.AlertDialog
import com.simplemobiletools.commons.activities.BaseSimpleActivity
import com.simplemobiletools.commons.extensions.getAlertDialogBuilder
import com.simplemobiletools.commons.extensions.setupDialogStuff
import com.simplemobiletools.commons.extensions.viewBinding
import com.simplemobiletools.dialer.adapters.GroupedCallsAdapter
import com.simplemobiletools.dialer.databinding.DialogShowGroupedCallsBinding
import com.simplemobiletools.dialer.helpers.*
import com.simplemobiletools.dialer.models.RecentCall

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
            val recents = allRecents.filter { callIds.contains(it.id) }.toMutableList() as ArrayList<RecentCall>
            activity.runOnUiThread {
                GroupedCallsAdapter(activity, recents, transcriptionManager) {
                    dialog?.dismiss()
                }.apply {
                    binding.selectGroupedCallsList.adapter = this
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
