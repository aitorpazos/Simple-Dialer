package com.simplemobiletools.dialer.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsLanguagePolicyTest {
    private fun voice(
        name: String,
        language: String,
        country: String,
        network: Boolean = false,
    ) = TtsLanguagePolicy.VoiceCandidate(name, language, country, network)

    @Test
    fun `offline exact locale is preferred`() {
        val voices = listOf(
            voice("es_MX-voice", "es", "MX"),
            voice("es_ES-network", "es", "ES", network = true),
            voice("es_ES-local", "es", "ES"),
        )

        assertEquals(
            "es_ES-local",
            TtsLanguagePolicy.selectVoice("es", "ES", voices)?.name,
        )
    }

    @Test
    fun `offline language fallback wins over a network voice`() {
        val voices = listOf(
            voice("es_ES-network", "es", "ES", network = true),
            voice("es_MX-local", "es", "MX"),
        )

        assertEquals(
            "es_MX-local",
            TtsLanguagePolicy.selectVoice("es", "ES", voices)?.name,
        )
    }

    @Test
    fun `voice from another language is never selected`() {
        val voices = listOf(
            voice("en_US-default", "en", "US"),
            voice("de_DE-default", "de", "DE"),
        )

        assertNull(TtsLanguagePolicy.selectVoice("es", "ES", voices))
    }

    @Test
    fun `Piper override only uses the successfully selected request voice`() {
        val spanish = voice("es_ES-davefx-medium", "es", "ES")
        val staleEnglish = voice("en_US-system-default", "en", "US")

        assertEquals(
            spanish.name,
            TtsLanguagePolicy.piperVoiceOverride("es", spanish, setVoiceSucceeded = true),
        )
        assertNull(
            TtsLanguagePolicy.piperVoiceOverride("es", staleEnglish, setVoiceSucceeded = true),
        )
        assertNull(
            TtsLanguagePolicy.piperVoiceOverride("es", spanish, setVoiceSucceeded = false),
        )
        assertNull(
            TtsLanguagePolicy.piperVoiceOverride("es", selectedVoice = null, setVoiceSucceeded = true),
        )
    }
}
