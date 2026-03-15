package com.simplemobiletools.dialer.helpers

import com.simplemobiletools.commons.helpers.TAB_CALL_HISTORY
import com.simplemobiletools.commons.helpers.TAB_CONTACTS
import com.simplemobiletools.commons.helpers.TAB_FAVORITES

// shared prefs
const val SPEED_DIAL = "speed_dial"
const val REMEMBER_SIM_PREFIX = "remember_sim_"
const val GROUP_SUBSEQUENT_CALLS = "group_subsequent_calls"
const val OPEN_DIAL_PAD_AT_LAUNCH = "open_dial_pad_at_launch"
const val DISABLE_PROXIMITY_SENSOR = "disable_proximity_sensor"
const val DISABLE_SWIPE_TO_ANSWER = "disable_swipe_to_answer"
const val SHOW_TABS = "show_tabs"
const val FAVORITES_CONTACTS_ORDER = "favorites_contacts_order"
const val FAVORITES_CUSTOM_ORDER_SELECTED = "favorites_custom_order_selected"
const val WAS_OVERLAY_SNACKBAR_CONFIRMED = "was_overlay_snackbar_confirmed"
const val DIALPAD_VIBRATION = "dialpad_vibration"
const val DIALPAD_BEEPS = "dialpad_beeps"
const val HIDE_DIALPAD_NUMBERS = "hide_dialpad_numbers"
const val ALWAYS_SHOW_FULLSCREEN = "always_show_fullscreen"
const val CALL_RECORDING_ENABLED = "call_recording_enabled"
const val CALL_RECORDING_PATH = "call_recording_path"
const val AUTO_ANSWER_MODE = "auto_answer_mode"
const val AUTO_ANSWER_GREETING = "auto_answer_greeting"
const val NOTIFICATION_ACTION_PLAY_RECORDING = "notification_action_play_recording"
const val NOTIFICATION_ACTION_SHARE = "notification_action_share"
const val NOTIFICATION_ACTION_SHARE_RECORDING = "notification_action_share_recording"
const val NOTIFICATION_ACTION_SHARE_TRANSCRIPTION = "notification_action_share_transcription"
const val NOTIFICATION_ACTION_SHOW_TRANSCRIPTION = "notification_action_show_transcription"

// Auto-answer mode values
const val AUTO_ANSWER_NONE = 0
const val AUTO_ANSWER_ALL = 1
const val AUTO_ANSWER_UNKNOWN = 2

// Default greeting for auto-answer TTS
const val DEFAULT_AUTO_ANSWER_GREETING = "Hello, this call is being answered automatically. Please leave your message after the tone."

const val ALL_TABS_MASK = TAB_CONTACTS or TAB_FAVORITES or TAB_CALL_HISTORY

val tabsList = arrayListOf(TAB_CONTACTS, TAB_FAVORITES, TAB_CALL_HISTORY)

private const val PATH = "com.simplemobiletools.dialer.action."
const val ACCEPT_CALL = PATH + "accept_call"
const val DECLINE_CALL = PATH + "decline_call"

const val DIALPAD_TONE_LENGTH_MS = 150L // The length of DTMF tones in milliseconds

const val MIN_RECENTS_THRESHOLD = 30

// Notification action intent actions
private const val SUMMARY_ACTION_PATH = "com.simplemobiletools.dialer.action."
const val ACTION_PLAY_RECORDING = SUMMARY_ACTION_PATH + "play_recording"
const val ACTION_SHARE_RECORDING = SUMMARY_ACTION_PATH + "share_recording"
const val ACTION_SHARE_TRANSCRIPTION = SUMMARY_ACTION_PATH + "share_transcription"
const val ACTION_SHARE_CHOOSER = SUMMARY_ACTION_PATH + "share_chooser"
const val ACTION_SHOW_TRANSCRIPTION = SUMMARY_ACTION_PATH + "show_transcription"

// Extras for notification action intents
const val EXTRA_RECORDING_URI = "extra_recording_uri"
const val EXTRA_RECORDING_NAME = "extra_recording_name"
const val EXTRA_CONTACT_NAME = "extra_contact_name"
const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

// Bitmask for enabled notification actions (default: play recording only)
const val NOTIF_ACTION_PLAY_RECORDING = 1
const val NOTIF_ACTION_SHARE = 2
const val NOTIF_ACTION_SHARE_RECORDING = 4
const val NOTIF_ACTION_SHARE_TRANSCRIPTION = 8
const val NOTIF_ACTION_SHOW_TRANSCRIPTION = 16
const val DEFAULT_NOTIFICATION_ACTIONS = NOTIF_ACTION_SHARE or NOTIF_ACTION_SHOW_TRANSCRIPTION

// Listen-in mode values
const val LISTEN_IN_OFF = 0
const val LISTEN_IN_NOTIFICATION = 1
const val LISTEN_IN_AUTO = 2

// Listen-in preference key
const val LISTEN_IN_MODE = "listen_in_mode"

// TTS settings
const val TTS_ENGINE = "tts_engine"
const val TTS_LANGUAGE = "tts_language"

// Per-SIM auto-answer settings (stored as JSON map keyed by SIM id)
const val PER_SIM_SETTINGS = "per_sim_settings"

// Active call notification actions
private const val ACTIVE_CALL_PATH = "com.simplemobiletools.dialer.action."
const val ACTION_LISTEN_IN = ACTIVE_CALL_PATH + "listen_in"
const val ACTION_STOP_LISTENING = ACTIVE_CALL_PATH + "stop_listening"
const val ACTION_HANG_UP = ACTIVE_CALL_PATH + "hang_up"

const val ACTIVE_CALL_NOTIFICATION_ID = 4001
const val ACTIVE_CALL_CHANNEL_ID = "active_call_channel"
