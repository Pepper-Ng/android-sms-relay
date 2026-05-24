package nl.landvanhorne.smsrelay.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import nl.landvanhorne.smsrelay.service.RelayService

class SmsReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "SmsReceiver"

        // Optionally filter by sender to avoid noise.
        // Leave empty ("") to allow all SMS during listening mode.
        const val EXPECTED_SENDER = ""
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Only process if the backend has triggered listening mode.
        if (!RelayService.isListeningForSms) {
            Log.d(TAG, "SMS ignored — not in listening mode")
            return
        }

        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        // Concatenate SMS message parts.
        val sender = messages[0].displayOriginatingAddress
        val body = messages.joinToString("") { it.messageBody }

        Log.d(TAG, "SMS from $sender: $body")

        // Filter by sender if configured.
        if (EXPECTED_SENDER.isNotEmpty() &&
            !sender.contains(EXPECTED_SENDER, ignoreCase = true)) {
            Log.d(TAG, "SMS ignored — sender $sender does not match")
            return
        }

        // Extract the numerical code from the SMS text.
        val code = extractCode(body)
        if (code == null) {
            Log.w(TAG, "No code found in SMS: $body")
            // Send the entire text if no code is found; the backend can parse it.
            RelayService.onSmsReceived?.invoke(sender, body)
        } else {
            RelayService.onSmsReceived?.invoke(sender, code)
        }
    }

    /**
     * Attempts to extract a 4-8 digit code from the SMS text.
     */
    private fun extractCode(body: String): String? {
        // Pattern: 4 to 8 consecutive digits.
        val regex = Regex("\\b(\\d{4,8})\\b")
        return regex.find(body)?.groupValues?.get(1)
    }
}
