package com.simplemobiletools.dialer.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.CallAudioState
import com.simplemobiletools.dialer.helpers.*

class ActiveCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_LISTEN_IN -> {
                CallManager.setAudioRoute(CallAudioState.ROUTE_SPEAKER)
            }
            ACTION_STOP_LISTENING -> {
                CallManager.setAudioRoute(CallAudioState.ROUTE_WIRED_OR_EARPIECE)
            }
            ACTION_HANG_UP -> {
                CallManager.reject()
            }
        }
    }
}
