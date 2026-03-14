package com.simplemobiletools.dialer.helpers

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests the notification action filtering logic used by CallSummaryManager.
 *
 * The logic:
 *   - Actions are a bitmask stored in callEndNotificationActions
 *   - Play Recording, Share, Share Recording require hasRecording = true
 *   - Share Transcription, Show Transcription are always eligible
 *   - Android caps at 3 action buttons per notification
 */
class NotificationActionFilterTest {

    data class ActionEntry(val flag: Int, val label: String, val requiresRecording: Boolean)

    private val allActions = listOf(
        ActionEntry(NOTIF_ACTION_PLAY_RECORDING, "Play Recording", true),
        ActionEntry(NOTIF_ACTION_SHARE, "Share", true),
        ActionEntry(NOTIF_ACTION_SHARE_RECORDING, "Share Recording", true),
        ActionEntry(NOTIF_ACTION_SHARE_TRANSCRIPTION, "Share Transcription", false),
        ActionEntry(NOTIF_ACTION_SHOW_TRANSCRIPTION, "Show Transcription", false),
    )

    /**
     * Pure-logic replica of the filtering in CallSummaryManager.showCallSummary.
     * Returns the labels of actions that would be shown.
     */
    private fun getVisibleActions(enabledMask: Int, hasRecording: Boolean): List<String> {
        return allActions
            .filter { enabledMask and it.flag != 0 }
            .filter { !it.requiresRecording || hasRecording }
            .map { it.label }
    }

    @Test
    fun `default actions are Share and Show Transcription`() {
        assertEquals(
            NOTIF_ACTION_SHARE or NOTIF_ACTION_SHOW_TRANSCRIPTION,
            DEFAULT_NOTIFICATION_ACTIONS
        )
    }

    @Test
    fun `default with recording shows Share and Show Transcription`() {
        val actions = getVisibleActions(DEFAULT_NOTIFICATION_ACTIONS, hasRecording = true)
        assertEquals(listOf("Share", "Show Transcription"), actions)
    }

    @Test
    fun `default without recording shows only Show Transcription`() {
        val actions = getVisibleActions(DEFAULT_NOTIFICATION_ACTIONS, hasRecording = false)
        assertEquals(listOf("Show Transcription"), actions)
    }

    @Test
    fun `all actions enabled with recording shows all 5`() {
        val allMask = NOTIF_ACTION_PLAY_RECORDING or NOTIF_ACTION_SHARE or
                NOTIF_ACTION_SHARE_RECORDING or NOTIF_ACTION_SHARE_TRANSCRIPTION or
                NOTIF_ACTION_SHOW_TRANSCRIPTION
        val actions = getVisibleActions(allMask, hasRecording = true)
        assertEquals(5, actions.size)
        assertTrue(actions.contains("Play Recording"))
        assertTrue(actions.contains("Share"))
        assertTrue(actions.contains("Share Recording"))
        assertTrue(actions.contains("Share Transcription"))
        assertTrue(actions.contains("Show Transcription"))
    }

    @Test
    fun `all actions enabled without recording hides recording-dependent actions`() {
        val allMask = NOTIF_ACTION_PLAY_RECORDING or NOTIF_ACTION_SHARE or
                NOTIF_ACTION_SHARE_RECORDING or NOTIF_ACTION_SHARE_TRANSCRIPTION or
                NOTIF_ACTION_SHOW_TRANSCRIPTION
        val actions = getVisibleActions(allMask, hasRecording = false)
        assertEquals(listOf("Share Transcription", "Show Transcription"), actions)
    }

    @Test
    fun `no actions enabled shows empty list`() {
        val actions = getVisibleActions(0, hasRecording = true)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `play recording only with recording`() {
        val actions = getVisibleActions(NOTIF_ACTION_PLAY_RECORDING, hasRecording = true)
        assertEquals(listOf("Play Recording"), actions)
    }

    @Test
    fun `play recording only without recording shows nothing`() {
        val actions = getVisibleActions(NOTIF_ACTION_PLAY_RECORDING, hasRecording = false)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `share transcription does not require recording`() {
        val actions = getVisibleActions(NOTIF_ACTION_SHARE_TRANSCRIPTION, hasRecording = false)
        assertEquals(listOf("Share Transcription"), actions)
    }

    @Test
    fun `show transcription does not require recording`() {
        val actions = getVisibleActions(NOTIF_ACTION_SHOW_TRANSCRIPTION, hasRecording = false)
        assertEquals(listOf("Show Transcription"), actions)
    }

    @Test
    fun `mixed recording and transcription actions with no recording`() {
        val mask = NOTIF_ACTION_PLAY_RECORDING or NOTIF_ACTION_SHOW_TRANSCRIPTION
        val actions = getVisibleActions(mask, hasRecording = false)
        assertEquals(listOf("Show Transcription"), actions)
    }
}
