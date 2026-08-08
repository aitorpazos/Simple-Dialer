package com.simplemobiletools.dialer.helpers

/**
 * Pure TTS voice-selection rules kept separate from Android framework classes
 * so language matching can be covered by local unit tests.
 */
object TtsLanguagePolicy {
    data class VoiceCandidate(
        val name: String,
        val language: String,
        val country: String,
        val requiresNetwork: Boolean,
    )

    /**
     * Prefer an offline exact-locale voice, then an offline language match,
     * followed by equivalent network voices. A voice from another language is
     * never returned as a fallback because engines such as Piper treat an
     * explicit voice name as higher priority than the requested language.
     */
    fun selectVoice(
        desiredLanguage: String,
        desiredCountry: String,
        voices: Collection<VoiceCandidate>,
    ): VoiceCandidate? {
        val language = desiredLanguage.lowercase()
        val country = desiredCountry.uppercase()
        val matchingLanguage = voices.filter { it.language.lowercase() == language }

        fun exactLocale(candidate: VoiceCandidate): Boolean {
            return country.isNotEmpty() && candidate.country.equals(country, ignoreCase = true)
        }

        return matchingLanguage.firstOrNull { !it.requiresNetwork && exactLocale(it) }
            ?: matchingLanguage.firstOrNull { !it.requiresNetwork }
            ?: matchingLanguage.firstOrNull { exactLocale(it) }
            ?: matchingLanguage.firstOrNull()
    }

    /**
     * Piper's direct voice parameter must only contain the voice selected for
     * this request. Reusing TextToSpeech.voice can send the engine a stale
     * system-default voice, which overrides the requested piper_language.
     */
    fun piperVoiceOverride(
        desiredLanguage: String,
        selectedVoice: VoiceCandidate?,
        setVoiceSucceeded: Boolean,
    ): String? {
        return selectedVoice
            ?.takeIf { setVoiceSucceeded && it.language.equals(desiredLanguage, ignoreCase = true) }
            ?.name
    }
}
