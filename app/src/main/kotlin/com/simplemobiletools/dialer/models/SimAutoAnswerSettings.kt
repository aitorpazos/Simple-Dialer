package com.simplemobiletools.dialer.models

/**
 * Per-SIM auto-answer settings.
 * @param language BCP-47 language tag (e.g. "en-US", "es-ES") or empty for global default
 * @param greeting Custom greeting text or empty to use global default
 */
data class SimAutoAnswerSettings(
    val language: String = "",
    val greeting: String = ""
)
