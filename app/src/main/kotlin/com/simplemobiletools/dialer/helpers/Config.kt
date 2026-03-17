package com.simplemobiletools.dialer.helpers

import android.content.ComponentName
import android.content.Context
import android.telecom.PhoneAccountHandle
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.simplemobiletools.commons.helpers.BaseConfig
import com.simplemobiletools.dialer.extensions.getPhoneAccountHandleModel
import com.simplemobiletools.dialer.extensions.putPhoneAccountHandle
import com.simplemobiletools.dialer.models.SimAutoAnswerSettings
import com.simplemobiletools.dialer.models.SpeedDial

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
    }

    fun getSpeedDialValues(): ArrayList<SpeedDial> {
        val speedDialType = object : TypeToken<List<SpeedDial>>() {}.type
        val speedDialValues = Gson().fromJson<ArrayList<SpeedDial>>(speedDial, speedDialType) ?: ArrayList(1)

        for (i in 1..9) {
            val speedDial = SpeedDial(i, "", "")
            if (speedDialValues.firstOrNull { it.id == i } == null) {
                speedDialValues.add(speedDial)
            }
        }

        return speedDialValues
    }

    fun saveCustomSIM(number: String, handle: PhoneAccountHandle) {
        prefs.edit().putPhoneAccountHandle(REMEMBER_SIM_PREFIX + number, handle).apply()
    }

    fun getCustomSIM(number: String): PhoneAccountHandle? {
        val myPhoneAccountHandle = prefs.getPhoneAccountHandleModel(REMEMBER_SIM_PREFIX + number, null)
        return if (myPhoneAccountHandle != null) {
            val packageName = myPhoneAccountHandle.packageName
            val className = myPhoneAccountHandle.className
            val componentName = ComponentName(packageName, className)
            val id = myPhoneAccountHandle.id
            PhoneAccountHandle(componentName, id)
        } else {
            null
        }
    }

    fun removeCustomSIM(number: String) {
        prefs.edit().remove(REMEMBER_SIM_PREFIX + number).apply()
    }

    var showTabs: Int
        get() = prefs.getInt(SHOW_TABS, ALL_TABS_MASK)
        set(showTabs) = prefs.edit().putInt(SHOW_TABS, showTabs).apply()

    var groupSubsequentCalls: Boolean
        get() = prefs.getBoolean(GROUP_SUBSEQUENT_CALLS, true)
        set(groupSubsequentCalls) = prefs.edit().putBoolean(GROUP_SUBSEQUENT_CALLS, groupSubsequentCalls).apply()

    var openDialPadAtLaunch: Boolean
        get() = prefs.getBoolean(OPEN_DIAL_PAD_AT_LAUNCH, false)
        set(openDialPad) = prefs.edit().putBoolean(OPEN_DIAL_PAD_AT_LAUNCH, openDialPad).apply()

    var disableProximitySensor: Boolean
        get() = prefs.getBoolean(DISABLE_PROXIMITY_SENSOR, false)
        set(disableProximitySensor) = prefs.edit().putBoolean(DISABLE_PROXIMITY_SENSOR, disableProximitySensor).apply()

    var disableSwipeToAnswer: Boolean
        get() = prefs.getBoolean(DISABLE_SWIPE_TO_ANSWER, false)
        set(disableSwipeToAnswer) = prefs.edit().putBoolean(DISABLE_SWIPE_TO_ANSWER, disableSwipeToAnswer).apply()

    var wasOverlaySnackbarConfirmed: Boolean
        get() = prefs.getBoolean(WAS_OVERLAY_SNACKBAR_CONFIRMED, false)
        set(wasOverlaySnackbarConfirmed) = prefs.edit().putBoolean(WAS_OVERLAY_SNACKBAR_CONFIRMED, wasOverlaySnackbarConfirmed).apply()

    var dialpadVibration: Boolean
        get() = prefs.getBoolean(DIALPAD_VIBRATION, true)
        set(dialpadVibration) = prefs.edit().putBoolean(DIALPAD_VIBRATION, dialpadVibration).apply()

    var hideDialpadNumbers: Boolean
        get() = prefs.getBoolean(HIDE_DIALPAD_NUMBERS, false)
        set(hideDialpadNumbers) = prefs.edit().putBoolean(HIDE_DIALPAD_NUMBERS, hideDialpadNumbers).apply()

    var dialpadBeeps: Boolean
        get() = prefs.getBoolean(DIALPAD_BEEPS, true)
        set(dialpadBeeps) = prefs.edit().putBoolean(DIALPAD_BEEPS, dialpadBeeps).apply()

    var alwaysShowFullscreen: Boolean
        get() = prefs.getBoolean(ALWAYS_SHOW_FULLSCREEN, false)
        set(alwaysShowFullscreen) = prefs.edit().putBoolean(ALWAYS_SHOW_FULLSCREEN, alwaysShowFullscreen).apply()

    var callRecordingEnabled: Boolean
        get() = prefs.getBoolean(CALL_RECORDING_ENABLED, false)
        set(callRecordingEnabled) = prefs.edit().putBoolean(CALL_RECORDING_ENABLED, callRecordingEnabled).apply()

    var callRecordingPath: String
        get() = prefs.getString(CALL_RECORDING_PATH, "") ?: ""
        set(callRecordingPath) = prefs.edit().putString(CALL_RECORDING_PATH, callRecordingPath).apply()

    var autoAnswerMode: Int
        get() = prefs.getInt(AUTO_ANSWER_MODE, AUTO_ANSWER_NONE)
        set(autoAnswerMode) = prefs.edit().putInt(AUTO_ANSWER_MODE, autoAnswerMode).apply()

    var autoAnswerGreeting: String
        get() = prefs.getString(AUTO_ANSWER_GREETING, DEFAULT_AUTO_ANSWER_GREETING) ?: DEFAULT_AUTO_ANSWER_GREETING
        set(autoAnswerGreeting) = prefs.edit().putString(AUTO_ANSWER_GREETING, autoAnswerGreeting).apply()

    var callEndNotificationActions: Int
        get() = prefs.getInt("call_end_notification_actions", DEFAULT_NOTIFICATION_ACTIONS)
        set(value) = prefs.edit().putInt("call_end_notification_actions", value).apply()

    var listenInMode: Int
        get() = prefs.getInt(LISTEN_IN_MODE, LISTEN_IN_NOTIFICATION)
        set(value) = prefs.edit().putInt(LISTEN_IN_MODE, value).apply()

    var ttsEngine: String
        get() = prefs.getString(TTS_ENGINE, "") ?: ""
        set(value) = prefs.edit().putString(TTS_ENGINE, value).apply()

    var ttsLanguage: String
        get() = prefs.getString(TTS_LANGUAGE, "") ?: ""
        set(value) = prefs.edit().putString(TTS_LANGUAGE, value).apply()

    var callTranscriptionEnabled: Boolean
        get() = prefs.getBoolean(CALL_TRANSCRIPTION_ENABLED, false)
        set(value) = prefs.edit().putBoolean(CALL_TRANSCRIPTION_ENABLED, value).apply()

    /**
     * Per-SIM auto-answer settings stored as JSON: {"1": {"language":"es-ES","greeting":"Hola..."}, ...}
     * Key is the SIM id (1-based index from getAvailableSIMCardLabels).
     */
    fun getPerSimSettings(): Map<String, SimAutoAnswerSettings> {
        val json = prefs.getString(PER_SIM_SETTINGS, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, SimAutoAnswerSettings>>() {}.type
            Gson().fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun getSimSettings(simId: String): SimAutoAnswerSettings {
        return getPerSimSettings()[simId] ?: SimAutoAnswerSettings()
    }

    fun setSimSettings(simId: String, settings: SimAutoAnswerSettings) {
        val map = getPerSimSettings().toMutableMap()
        map[simId] = settings
        prefs.edit().putString(PER_SIM_SETTINGS, Gson().toJson(map)).apply()
    }
}
