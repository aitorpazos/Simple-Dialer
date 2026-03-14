package com.simplemobiletools.dialer.helpers

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests the auto-answer decision matrix.
 *
 * The logic in CallService.handleAutoAnswer:
 *   - AUTO_ANSWER_NONE → never answer
 *   - AUTO_ANSWER_ALL  → always answer (known or unknown)
 *   - AUTO_ANSWER_UNKNOWN → answer only when caller is unknown
 *
 * "Unknown" means: contact.name is empty OR contact.name == contact.number
 */
class AutoAnswerLogicTest {

    /**
     * Pure-logic replica of the auto-answer decision.
     * Extracted so we can test without Android framework.
     */
    private fun shouldAutoAnswer(
        autoAnswerMode: Int,
        contactName: String,
        contactNumber: String,
        isOutgoing: Boolean = false
    ): Boolean {
        // Never auto-answer outgoing calls
        if (isOutgoing) return false

        return when (autoAnswerMode) {
            AUTO_ANSWER_NONE -> false
            AUTO_ANSWER_ALL -> true
            AUTO_ANSWER_UNKNOWN -> {
                val isUnknown = contactName.isEmpty() || contactName == contactNumber
                isUnknown
            }
            else -> false
        }
    }

    @Test
    fun `mode NONE never auto-answers`() {
        assertFalse(shouldAutoAnswer(AUTO_ANSWER_NONE, "", "+1234567890"))
        assertFalse(shouldAutoAnswer(AUTO_ANSWER_NONE, "Alice", "+1234567890"))
    }

    @Test
    fun `mode ALL answers known contacts`() {
        assertTrue(shouldAutoAnswer(AUTO_ANSWER_ALL, "Alice", "+1234567890"))
    }

    @Test
    fun `mode ALL answers unknown callers`() {
        assertTrue(shouldAutoAnswer(AUTO_ANSWER_ALL, "", "+1234567890"))
        assertTrue(shouldAutoAnswer(AUTO_ANSWER_ALL, "+1234567890", "+1234567890"))
    }

    @Test
    fun `mode UNKNOWN answers when name is empty`() {
        assertTrue(shouldAutoAnswer(AUTO_ANSWER_UNKNOWN, "", "+1234567890"))
    }

    @Test
    fun `mode UNKNOWN answers when name equals number`() {
        assertTrue(shouldAutoAnswer(AUTO_ANSWER_UNKNOWN, "+1234567890", "+1234567890"))
    }

    @Test
    fun `mode UNKNOWN does NOT answer known contacts`() {
        assertFalse(shouldAutoAnswer(AUTO_ANSWER_UNKNOWN, "Alice", "+1234567890"))
        assertFalse(shouldAutoAnswer(AUTO_ANSWER_UNKNOWN, "Bob Smith", "+0987654321"))
    }

    @Test
    fun `outgoing calls are never auto-answered regardless of mode`() {
        assertFalse(shouldAutoAnswer(AUTO_ANSWER_ALL, "Alice", "+1234567890", isOutgoing = true))
        assertFalse(shouldAutoAnswer(AUTO_ANSWER_UNKNOWN, "", "+1234567890", isOutgoing = true))
    }

    @Test
    fun `invalid mode falls back to no auto-answer`() {
        assertFalse(shouldAutoAnswer(99, "Alice", "+1234567890"))
        assertFalse(shouldAutoAnswer(-1, "", "+1234567890"))
    }
}
