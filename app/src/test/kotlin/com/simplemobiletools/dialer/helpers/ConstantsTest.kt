package com.simplemobiletools.dialer.helpers

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests that Constants.kt bitmask values are correct and non-overlapping.
 */
class ConstantsTest {

    @Test
    fun `notification action bitmask values are unique powers of two`() {
        val flags = listOf(
            NOTIF_ACTION_PLAY_RECORDING,
            NOTIF_ACTION_SHARE,
            NOTIF_ACTION_SHARE_RECORDING,
            NOTIF_ACTION_SHARE_TRANSCRIPTION,
            NOTIF_ACTION_SHOW_TRANSCRIPTION,
        )

        // Each flag must be a power of 2
        for (flag in flags) {
            assertTrue("Flag $flag must be > 0", flag > 0)
            assertEquals("Flag $flag must be a power of 2", 0, flag and (flag - 1))
        }

        // All flags must be distinct
        assertEquals("All flags must be unique", flags.size, flags.toSet().size)
    }

    @Test
    fun `notification action bitmask values do not overlap`() {
        val flags = listOf(
            NOTIF_ACTION_PLAY_RECORDING,
            NOTIF_ACTION_SHARE,
            NOTIF_ACTION_SHARE_RECORDING,
            NOTIF_ACTION_SHARE_TRANSCRIPTION,
            NOTIF_ACTION_SHOW_TRANSCRIPTION,
        )

        // OR-ing all flags together and counting bits should equal flag count
        val combined = flags.fold(0) { acc, f -> acc or f }
        val bitCount = Integer.bitCount(combined)
        assertEquals("Bitmask flags must not overlap", flags.size, bitCount)
    }

    @Test
    fun `default notification actions is Share OR Show Transcription`() {
        assertEquals(
            NOTIF_ACTION_SHARE or NOTIF_ACTION_SHOW_TRANSCRIPTION,
            DEFAULT_NOTIFICATION_ACTIONS
        )
    }

    @Test
    fun `auto answer mode constants are distinct`() {
        val modes = setOf(AUTO_ANSWER_NONE, AUTO_ANSWER_ALL, AUTO_ANSWER_UNKNOWN)
        assertEquals("Auto-answer modes must be unique", 3, modes.size)
    }

    @Test
    fun `listen-in mode constants are distinct`() {
        val modes = setOf(LISTEN_IN_OFF, LISTEN_IN_NOTIFICATION, LISTEN_IN_AUTO)
        assertEquals("Listen-in modes must be unique", 3, modes.size)
    }

    @Test
    fun `action intent strings are unique`() {
        val actions = setOf(
            ACTION_PLAY_RECORDING,
            ACTION_SHARE_RECORDING,
            ACTION_SHARE_TRANSCRIPTION,
            ACTION_SHARE_CHOOSER,
            ACTION_SHOW_TRANSCRIPTION,
            ACTION_LISTEN_IN,
            ACTION_STOP_LISTENING,
            ACTION_HANG_UP,
        )
        assertEquals("All intent action strings must be unique", 8, actions.size)
    }
}
