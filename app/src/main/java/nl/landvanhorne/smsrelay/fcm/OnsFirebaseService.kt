package nl.landvanhorne.smsrelay.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.*
import nl.landvanhorne.smsrelay.R
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Replaces the persistent WebSocket service.
 *
 * Flow:
 *  1. The server sends an FCM push "listen_sms".
 *  2. Android OS wakes this service.
 *  3. A temporary SMS BroadcastReceiver is registered (maximum SMS_TIMEOUT_MS).
 *  4. When an SMS arrives, the code is extracted and sent via HTTP POST to the server.
 *  5. The receiver is unregistered and the device returns to sleep.
 */
class OnsFirebaseService : FirebaseMessagingService() {

    companion object {
        const val TAG = "OnsFirebaseService"
        // Wait for a maximum of 2 minutes for the SMS.
        const val SMS_TIMEOUT_MS = 120_000L

        // Set the server address here (HTTP, not WebSocket).
        const val SERVER_CALLBACK_URL = "http://backend-server.local:8080/sms_code"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Temporary SMS receiver, only active during listening mode.
    private var smsReceiver: BroadcastReceiver? = null

    // ── FCM messages ────────────────────────────────────────────────────────

    override fun onMessageReceived(message: RemoteMessage) {
        val type = message.data["type"] ?: message.notification?.title
        Log.d(TAG, "FCM received: type=$type")

        when (type) {
            "listen_sms" -> {
                showStatusNotification(getString(R.string.status_waiting_sms))
                startSmsListening()
            }
            else -> Log.w(TAG, "Unknown FCM type: $type")
        }
    }

    /**
     * Called when FCM generates a new device token.
     * The server needs this token to send push messages.
     * It is automatically forwarded to the server.
     */
    override fun onNewToken(token: String) {
        Log.d(TAG, "New FCM token: $token")
        // Save to SharedPreferences so MainActivity can display it.
        getSharedPreferences("relay", MODE_PRIVATE)
            .edit()
            .putString("fcm_token", token)
            .apply()

        // Forward to server if it is reachable.
        scope.launch {
            try {
                val body = JSONObject().apply {
                    put("type", "register_token")
                    put("token", token)
                }.toString().toRequestBody("application/json".toMediaType())

                val registerUrl = SERVER_CALLBACK_URL.replace("/sms_code", "/register_token")
                http.newCall(
                    Request.Builder().url(registerUrl)
                        .post(body).build()
                ).execute().close()
            } catch (e: Exception) {
                Log.w(TAG, "Token forwarding failed (server might be unreachable): ${e.message}")
            }
        }
    }

    // ── SMS Listening ────────────────────────────────────────────────────────

    private fun startSmsListening() {
        // Prevent multiple registrations.
        stopSmsListening()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                    ?: return
                val sender = messages[0].displayOriginatingAddress
                val body   = messages.joinToString("") { it.messageBody }

                Log.d(TAG, "SMS from $sender: $body")
                val code = extractCode(body) ?: body.trim()

                // Stop listening immediately once the SMS is received.
                stopSmsListening()
                showStatusNotification(getString(R.string.status_sms_received))
                sendCodeToServer(sender, code)
            }
        }

        val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        registerReceiver(receiver, filter)
        smsReceiver = receiver

        Log.d(TAG, "SMS listener active for ${SMS_TIMEOUT_MS / 1000}s")

        // Automatic timeout if no SMS is received.
        scope.launch {
            delay(SMS_TIMEOUT_MS)
            if (smsReceiver != null) {
                Log.w(TAG, "Timeout: no SMS received")
                stopSmsListening()
                showStatusNotification(getString(R.string.status_error_timeout))
            }
        }
    }

    private fun stopSmsListening() {
        smsReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            smsReceiver = null
            Log.d(TAG, "SMS listener stopped")
        }
    }

    // ── Send code to server ─────────────────────────────────────────────────

    private fun sendCodeToServer(sender: String, code: String) {
        scope.launch {
            val maxRetries = 5
            var attempt = 0
            var delayMs = 3_000L

            while (attempt < maxRetries) {
                attempt++
                try {
                    val body = JSONObject().apply {
                        put("type", "sms_code")
                        put("sender", sender)
                        put("code", code)
                    }.toString().toRequestBody("application/json".toMediaType())

                    val response = http.newCall(
                        Request.Builder().url(SERVER_CALLBACK_URL).post(body).build()
                    ).execute()

                    if (response.isSuccessful) {
                        Log.d(TAG, "Code successfully forwarded (attempt $attempt)")
                        showStatusNotification(getString(R.string.status_processed))
                        response.close()
                        return@launch
                    } else {
                        Log.w(TAG, "Server responded with ${response.code}, retrying...")
                        response.close()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Attempt $attempt failed: ${e.message}")
                }

                if (attempt < maxRetries) {
                    Log.d(TAG, "Waiting ${delayMs / 1000}s before retry...")
                    delay(delayMs)
                    // Increase the delay up to 30 seconds.
                    delayMs = (delayMs * 2).coerceAtMost(30_000L)
                }
            }

            Log.e(TAG, "All $maxRetries attempts failed")
            showStatusNotification(getString(R.string.status_error_failed))
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun showStatusNotification(text: String) {
        val channelId = "ons_relay_status"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW)
            )
        }

        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_relay)
            .setAutoCancel(true)
            .build()

        manager.notify(42, notif)
    }

    // ── Helper functions ─────────────────────────────────────────────────────

    private fun extractCode(body: String): String? =
        Regex("\\b(\\d{4,8})\\b").find(body)?.groupValues?.get(1)

    override fun onDestroy() {
        super.onDestroy()
        stopSmsListening()
        scope.cancel()
    }
}
