package com.simplemobiletools.dialer.helpers

/**
 * Pure call-recording policy kept separate from Android audio APIs so the
 * source-selection and validation rules can be unit tested.
 */
object CallRecordingPolicy {
    enum class Source {
        VOICE_CALL,
        VOICE_COMMUNICATION,
        VOICE_RECOGNITION,
        MIC,
    }

    const val MIN_VALID_AMPLITUDE = 8

    /**
     * VOICE_CALL is restricted by CAPTURE_AUDIO_OUTPUT. An enabled
     * AccessibilityService does not grant that signature permission, so a
     * regular installation must use microphone-based sources in automatic
     * mode. MIC is first because it is the most widely supported source while
     * Telecom owns the call audio route.
     */
    fun sourcePriority(setting: String, hasCaptureAudioOutput: Boolean): List<Source> {
        val selected = when (setting) {
            AUDIO_SOURCE_VOICE_CALL -> Source.VOICE_CALL
            AUDIO_SOURCE_VOICE_COMMUNICATION -> Source.VOICE_COMMUNICATION
            AUDIO_SOURCE_VOICE_RECOGNITION -> Source.VOICE_RECOGNITION
            AUDIO_SOURCE_MIC -> Source.MIC
            else -> null
        }
        if (selected != null) return listOf(selected)

        return if (hasCaptureAudioOutput) {
            listOf(Source.VOICE_CALL, Source.MIC, Source.VOICE_COMMUNICATION)
        } else {
            listOf(Source.MIC, Source.VOICE_COMMUNICATION, Source.VOICE_RECOGNITION)
        }
    }

    fun isUsableRecording(dataBytes: Long, maxAmplitude: Int): Boolean {
        return dataBytes > 0L && maxAmplitude >= MIN_VALID_AMPLITUDE
    }
}

enum class RecordingFailure {
    PERMISSION_MISSING,
    FOREGROUND_START_FAILED,
    AUDIO_SOURCE_UNAVAILABLE,
    SILENT_AUDIO,
    STORAGE_ERROR,
}
