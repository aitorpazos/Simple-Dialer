package com.simplemobiletools.dialer.services

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service that enables reliable call audio capture.
 *
 * On many Android devices (Android 9+), the VOICE_CALL audio source is blocked
 * for third-party apps. Having an active AccessibilityService signals to the
 * Android audio framework that the app has elevated privileges, which unlocks
 * the VOICE_CALL source on many OEMs (Samsung, Xiaomi, OnePlus, Pixel, etc.).
 *
 * This service does not read or interact with screen content — it exists solely
 * to enable the VOICE_CALL audio source for reliable call recording.
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
