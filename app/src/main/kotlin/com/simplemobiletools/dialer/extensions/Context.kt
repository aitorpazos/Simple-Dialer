package com.simplemobiletools.dialer.extensions

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
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
        // Build a map of subscription ID -> user-given display name from SubscriptionManager
        val subscriptionDisplayNames = getSubscriptionDisplayNames()

        telecomManager.callCapablePhoneAccounts.forEachIndexed { index, account ->
            val phoneAccount = telecomManager.getPhoneAccount(account)
            var label = phoneAccount.label.toString()
            var address = phoneAccount.address.toString()
            val phoneNumber = if (address.startsWith("tel:") && address.substringAfter("tel:").isNotEmpty()) {
                Uri.decode(address.substringAfter("tel:"))
            } else {
                ""
            }

            if (phoneNumber.isNotEmpty()) {
                label += " ($phoneNumber)"
            }

            // Try to find the user-given SIM name from SubscriptionManager.
            // Match by phone number or by subscription ID embedded in the account handle.
            val userGivenName = resolveUserGivenName(account, phoneNumber, subscriptionDisplayNames)

            val SIM = SIMAccount(index + 1, phoneAccount.accountHandle, label, phoneNumber, userGivenName)
            SIMAccounts.add(SIM)
        }
    } catch (ignored: Exception) {
    }
    return SIMAccounts
}

/**
 * Retrieves the user-configured display names for active SIM subscriptions.
 * Returns a map of subscription ID to display name, plus a secondary map of phone number to display name.
 */
@SuppressLint("MissingPermission")
private fun Context.getSubscriptionDisplayNames(): Map<String, String> {
    val names = mutableMapOf<String, String>()
    try {
        val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            ?: return names
        val subscriptions = subscriptionManager.activeSubscriptionInfoList ?: return names
        for (sub in subscriptions) {
            val displayName = sub.displayName?.toString() ?: continue
            // Only store the name if it's non-empty. Users often rename SIMs to "Personal", "Work", etc.
            if (displayName.isNotEmpty()) {
                // Key by subscription ID
                names["sub_${sub.subscriptionId}"] = displayName
                // Also key by phone number for fallback matching
                val number = getSubscriptionNumber(subscriptionManager, sub)
                if (!number.isNullOrEmpty()) {
                    names["num_$number"] = displayName
                }
            }
        }
    } catch (ignored: Exception) {
    }
    return names
}

/**
 * Gets the phone number for a subscription, handling the API 33 deprecation of SubscriptionInfo.getNumber().
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun getSubscriptionNumber(subscriptionManager: SubscriptionManager, sub: SubscriptionInfo): String? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            subscriptionManager.getPhoneNumber(sub.subscriptionId)
        } else {
            sub.number
        }
    } catch (ignored: Exception) {
        null
    }
}

/**
 * Resolves the user-given SIM name by matching the phone account handle to a subscription.
 */
@SuppressLint("MissingPermission")
private fun Context.resolveUserGivenName(
    accountHandle: android.telecom.PhoneAccountHandle,
    phoneNumber: String,
    subscriptionNames: Map<String, String>
): String {
    try {
        // The account handle ID is often the subscription ID for SIM-based accounts
        val handleId = accountHandle.id
        val subIdName = subscriptionNames["sub_$handleId"]
        if (!subIdName.isNullOrEmpty()) return subIdName

        // Fallback: match by phone number
        if (phoneNumber.isNotEmpty()) {
            // Try exact match first
            val byNumber = subscriptionNames["num_$phoneNumber"]
            if (!byNumber.isNullOrEmpty()) return byNumber

            // Try matching by suffix (last 8+ digits) to handle country code differences
            val normalizedNumber = phoneNumber.replace(Regex("[^0-9]"), "")
            if (normalizedNumber.length >= 8) {
                val suffix = normalizedNumber.takeLast(8)
                for ((key, name) in subscriptionNames) {
                    if (key.startsWith("num_")) {
                        val candidateNumber = key.removePrefix("num_").replace(Regex("[^0-9]"), "")
                        if (candidateNumber.endsWith(suffix)) return name
                    }
                }
            }
        }
    } catch (ignored: Exception) {
    }
    return ""
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
 *
 * Priority order:
 * 1. User-given name + phone number, e.g. "Work (+34612345678)"
 * 2. User-given name alone, e.g. "Work"
 * 3. Carrier label with phone number, e.g. "Vodafone (+34612345678)"
 * 4. Fallback: "SIM 1", "SIM 2" etc.
 */
fun Context.getSIMDisplayLabel(simId: Int): String {
    val accounts = getAvailableSIMCardLabels()
    val sim = accounts.firstOrNull { it.id == simId }
    return sim?.let { buildSIMDisplayLabel(it) } ?: "SIM $simId"
}

/**
 * Returns a short display label for a SIM card, suitable for small UI elements (badges, chips).
 *
 * Priority order:
 * 1. User-given name, e.g. "Work"
 * 2. Phone number, e.g. "+34612345678"
 * 3. Fallback: "SIM 1" etc.
 */
fun Context.getSIMShortLabel(simId: Int): String {
    val accounts = getAvailableSIMCardLabels()
    val sim = accounts.firstOrNull { it.id == simId }
    if (sim != null) {
        if (sim.userGivenName.isNotEmpty()) return sim.userGivenName
        if (sim.phoneNumber.isNotEmpty()) return sim.phoneNumber
    }
    return "SIM $simId"
}

/**
 * Builds a display label from a [SIMAccount].
 *
 * If the user has configured a custom name for the SIM (e.g. "Work", "Personal"),
 * that name is shown, optionally with the phone number.
 * Otherwise falls back to the carrier label (which already includes the phone number
 * if available from [getAvailableSIMCardLabels]).
 */
private fun buildSIMDisplayLabel(sim: SIMAccount): String {
    if (sim.userGivenName.isNotEmpty()) {
        return if (sim.phoneNumber.isNotEmpty()) {
            "${sim.userGivenName} (${sim.phoneNumber})"
        } else {
            sim.userGivenName
        }
    }
    return sim.label.ifEmpty { "SIM ${sim.id}" }
}
