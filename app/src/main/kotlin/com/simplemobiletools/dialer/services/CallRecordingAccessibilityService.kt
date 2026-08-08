package com.simplemobiletools.dialer.services

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Legacy accessibility integration retained for existing installations.
 *
 * An AccessibilityService does not grant CAPTURE_AUDIO_OUTPUT and therefore
 * cannot unlock Android's protected VOICE_CALL stream. Recording no longer
 * depends on this service. It does not read or interact with screen content.
 */
class CallRecordingAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "CallRecAccessibility"

        @Volatile
        var instance: CallRecordingAccessibilityService? = null
            private set

        /**
         * Returns true if the accessibility service is currently running.
         */
        fun isAvailable(): Boolean {
            return instance != null
        }

        /**
         * Check if the accessibility service is enabled in system settings.
         */
        fun isServiceEnabled(context: Context): Boolean {
            val enabledServices = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val expectedComponent = "${context.packageName}/${CallRecordingAccessibilityService::class.java.canonicalName}"
            return enabledServices.split(':').any {
                it.equals(expectedComponent, ignoreCase = true)
            }
        }

        /**
         * Open system accessibility settings so the user can enable the service.
         */
        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected — VOICE_CALL audio source unlocked")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used — service exists for call recording audio source only
    }

    override fun onInterrupt() {
        // Not used
    }

    override fun onDestroy() {
        instance = null
        Log.i(TAG, "Accessibility service destroyed")
        super.onDestroy()
    }
}
