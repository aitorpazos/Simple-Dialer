package com.simplemobiletools.dialer.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRecordingPolicyTest {
    @Test
    fun `auto mode uses legal microphone sources for regular installs`() {
        assertEquals(
            listOf(
                CallRecordingPolicy.Source.MIC,
                CallRecordingPolicy.Source.VOICE_COMMUNICATION,
                CallRecordingPolicy.Source.VOICE_RECOGNITION,
            ),
            CallRecordingPolicy.sourcePriority(AUDIO_SOURCE_AUTO, hasCaptureAudioOutput = false),
        )
    }

    @Test
    fun `auto mode only prioritizes voice call with privileged permission`() {
        assertEquals(
            CallRecordingPolicy.Source.VOICE_CALL,
            CallRecordingPolicy.sourcePriority(AUDIO_SOURCE_AUTO, hasCaptureAudioOutput = true).first(),
        )
        assertFalse(
            CallRecordingPolicy.sourcePriority(AUDIO_SOURCE_AUTO, hasCaptureAudioOutput = false)
                .contains(CallRecordingPolicy.Source.VOICE_CALL),
        )
    }

    @Test
    fun `explicit source selection is honored`() {
        assertEquals(
            listOf(CallRecordingPolicy.Source.VOICE_COMMUNICATION),
            CallRecordingPolicy.sourcePriority(
                AUDIO_SOURCE_VOICE_COMMUNICATION,
                hasCaptureAudioOutput = false,
            ),
        )
        assertEquals(
            listOf(CallRecordingPolicy.Source.MIC),
            CallRecordingPolicy.sourcePriority(AUDIO_SOURCE_MIC, hasCaptureAudioOutput = false),
        )
    }

    @Test
    fun `recording requires data and a nonzero signal`() {
        assertFalse(CallRecordingPolicy.isUsableRecording(0, 100))
        assertFalse(
            CallRecordingPolicy.isUsableRecording(
                dataBytes = 32000,
                maxAmplitude = CallRecordingPolicy.MIN_VALID_AMPLITUDE - 1,
            ),
        )
        assertTrue(
            CallRecordingPolicy.isUsableRecording(
                dataBytes = 32000,
                maxAmplitude = CallRecordingPolicy.MIN_VALID_AMPLITUDE,
            ),
        )
    }
}
