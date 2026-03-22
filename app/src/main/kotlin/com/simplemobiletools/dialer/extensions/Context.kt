package com.simplemobiletools.dialer.extensions

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import com.simplemobiletools.commons.extensions.telecomManager
import com.simplemobiletools.dialer.helpers.Config
import com.simplemobiletools.dialer.models.SIMAccount

val Context.config: Config get() = Config.newInstance(applicationContext)

val Context.audioManager: AudioManager get() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

val Context.powerManager: PowerManager get() = getSystemService(Context.POWER_SERVICE) as PowerManager

@SuppressLint("MissingPermission")
fun Context.getAvailableSIMCardLabels(): List<SIMAccount> {
    val SIMAccounts = mutableListOf<SIMAccount>()
    try {
        telecomManager.callCapablePhoneAccounts.forEachIndexed { index, account ->
            val phoneAccount = telecomManager.getPhoneAccount(account)
            var label = phoneAccount.label.toString()
            var address = phoneAccount.address.toString()
            if (address.startsWith("tel:") && address.substringAfter("tel:").isNotEmpty()) {
                address = Uri.decode(address.substringAfter("tel:"))
                label += " ($address)"
            }

            val SIM = SIMAccount(index + 1, phoneAccount.accountHandle, label, address.substringAfter("tel:"))
            SIMAccounts.add(SIM)
        }
    } catch (ignored: Exception) {
    }
    return SIMAccounts
}

@SuppressLint("MissingPermission")
fun Context.areMultipleSIMsAvailable(): Boolean {
    return try {
        telecomManager.callCapablePhoneAccounts.size > 1
    } catch (ignored: Exception) {
        false
    }
}

/**
 * Returns a user-friendly display label for a SIM card identified by its 1-based [simId].
 * Prefers the carrier/account name with phone number, e.g. "Vodafone (+34612345678)".
 * Falls back to "SIM 1", "SIM 2" etc. when no information is available.
 */
fun Context.getSIMDisplayLabel(simId: Int): String {
    val accounts = getAvailableSIMCardLabels()
    val sim = accounts.firstOrNull { it.id == simId }
    return sim?.let { buildSIMDisplayLabel(it) } ?: "SIM $simId"
}

/**
 * Returns a short display label for a SIM card, suitable for small UI elements (badges, chips).
 * Shows the phone number if available, otherwise falls back to "SIM 1" etc.
 */
fun Context.getSIMShortLabel(simId: Int): String {
    val accounts = getAvailableSIMCardLabels()
    val sim = accounts.firstOrNull { it.id == simId }
    if (sim != null && sim.phoneNumber.isNotEmpty()) {
        return sim.phoneNumber
    }
    return "SIM $simId"
}

/**
 * Builds a display label from a [SIMAccount]. Returns the carrier label (which already
 * includes the phone number if available from [getAvailableSIMCardLabels]).
 */
private fun buildSIMDisplayLabel(sim: SIMAccount): String {
    return sim.label.ifEmpty { "SIM ${sim.id}" }
}
