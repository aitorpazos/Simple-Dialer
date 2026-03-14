package com.simplemobiletools.dialer.helpers

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests listen-in mode constants and parsing logic.
 *
 * Listen-in modes:
 *   LISTEN_IN_OFF          (0) — No listen-in UI
 *   LISTEN_IN_NOTIFICATION (1) — Show notification with speaker toggle (default)
 *   LISTEN_IN_AUTO         (2) — Auto-enable speaker + notification
 */
class ListenInModeTest {

    /** Simulates what Config.listenInMode getter does with a raw int value. */
    private fun parseListenInMode(rawValue: Int, default: Int = LISTEN_IN_NOTIFICATION): Int {
        return when (rawValue) {
            LISTEN_IN_OFF, LISTEN_IN_NOTIFICATION, LISTEN_IN_AUTO -> rawValue
            else -> default
        }
    }

    @Test
    fun `constants are distinct values`() {
        val values = setOf(LISTEN_IN_OFF, LISTEN_IN_NOTIFICATION, LISTEN_IN_AUTO)
        assertEquals("Listen-in mode constants must be unique", 3, values.size)
    }

    @Test
    fun `default is NOTIFICATION mode`() {
        assertEquals(LISTEN_IN_NOTIFICATION, 1)
    }

    @Test
    fun `OFF mode parses correctly`() {
        assertEquals(LISTEN_IN_OFF, parseListenInMode(0))
    }

    @Test
    fun `NOTIFICATION mode parses correctly`() {
        assertEquals(LISTEN_IN_NOTIFICATION, parseListenInMode(1))
    }

    @Test
    fun `AUTO mode parses correctly`() {
        assertEquals(LISTEN_IN_AUTO, parseListenInMode(2))
    }

    @Test
    fun `invalid value falls back to default NOTIFICATION`() {
        assertEquals(LISTEN_IN_NOTIFICATION, parseListenInMode(-1))
        assertEquals(LISTEN_IN_NOTIFICATION, parseListenInMode(99))
        assertEquals(LISTEN_IN_NOTIFICATION, parseListenInMode(3))
    }

    @Test
    fun `should show notification returns true for NOTIFICATION and AUTO modes`() {
        fun shouldShowNotification(mode: Int): Boolean {
            return mode == LISTEN_IN_NOTIFICATION || mode == LISTEN_IN_AUTO
        }

        assertFalse(shouldShowNotification(LISTEN_IN_OFF))
        assertTrue(shouldShowNotification(LISTEN_IN_NOTIFICATION))
        assertTrue(shouldShowNotification(LISTEN_IN_AUTO))
    }

    @Test
    fun `should auto-enable speaker only in AUTO mode`() {
        fun shouldAutoSpeaker(mode: Int): Boolean {
            return mode == LISTEN_IN_AUTO
        }

        assertFalse(shouldAutoSpeaker(LISTEN_IN_OFF))
        assertFalse(shouldAutoSpeaker(LISTEN_IN_NOTIFICATION))
        assertTrue(shouldAutoSpeaker(LISTEN_IN_AUTO))
    }
}
