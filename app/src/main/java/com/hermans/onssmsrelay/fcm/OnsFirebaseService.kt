package com.hermans.onssmsrelay.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.*
import com.hermans.onssmsrelay.R
import com.hermans.onssmsrelay.data.AppSettingsStore
import com.hermans.onssmsrelay.data.BackendApi
import com.hermans.onssmsrelay.util.SmsCodeExtractor
import okhttp3.*
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
        const val DEFAULT_SMS_TIMEOUT_MS = 120_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val backendApi = BackendApi()
    private val settingsStore by lazy { AppSettingsStore(this) }

    private var smsReceiver: BroadcastReceiver? = null
    private var activeChallengeId: String? = null

    override fun onMessageReceived(message: RemoteMessage) {
        val type = message.data["type"] ?: message.notification?.title
        Log.d(TAG, "FCM received: type=$type")

        when (type) {
            "listen_sms" -> handleListenSms(message.data)
            "auth_result" -> handleAuthResult(message.data)
            else -> Log.w(TAG, "Unknown FCM type: $type")
        }
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "New FCM token: $token")
        settingsStore.saveFcmToken(token)

        // The FCM token is forwarded opportunistically when the app already has a backend token.
        scope.launch {
            try {
                val settings = settingsStore.load()
                if (settings.backendBaseUrl.isBlank() || settings.apiToken.isBlank()) {
                    return@launch
                }
                backendApi.updateFcmToken(
                    backendBaseUrl = settings.backendBaseUrl,
                    apiToken = settings.apiToken,
                    fcmToken = token,
                    deviceLabel = settings.deviceLabel,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Token forwarding failed: ${e.message}")
            }
        }
    }

    private fun handleListenSms(data: Map<String, String>) {
        val challengeId = data["challenge_id"].orEmpty()
        val timeoutMs = data["expires_in_seconds"]?.toLongOrNull()?.times(1_000)
            ?: DEFAULT_SMS_TIMEOUT_MS
        if (challengeId.isBlank()) {
            Log.w(TAG, "Missing challenge identifier in listen_sms push")
            return
        }
        if (!hasSmsPermission()) {
            showStatusNotification(getString(R.string.status_missing_permission))
            return
        }
        showStatusNotification(getString(R.string.status_waiting_sms))
        startSmsListening(challengeId, timeoutMs)
    }

    private fun handleAuthResult(data: Map<String, String>) {
        val message = data["message"].orEmpty().ifBlank {
            if (data["status"] == "success") {
                getString(R.string.status_backend_ready)
            } else {
                getString(R.string.status_backend_failed)
            }
        }
        settingsStore.saveLastMessage(message, if (data["status"] == "failure") message else "")
        showStatusNotification(message)
    }

    private fun startSmsListening(challengeId: String, timeoutMs: Long) {
        // Only one SMS listener may be active at a time because the backend drives a single challenge.
        stopSmsListening()
        activeChallengeId = challengeId

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                    ?: return
                val sender = messages[0].displayOriginatingAddress
                val body = messages.joinToString("") { it.messageBody }

                Log.d(TAG, "SMS from $sender: $body")
                val code = SmsCodeExtractor.extract(body) ?: body.trim()

                stopSmsListening()
                showStatusNotification(getString(R.string.status_sms_received))
                sendCodeToServer(challengeId, sender, code)
            }
        }

        val filter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }

        // Android 13 and newer require the exported state to be declared for dynamic receivers.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        smsReceiver = receiver

        Log.d(TAG, "SMS listener active for ${timeoutMs / 1000}s")

        // The temporary listener is removed again after the challenge timeout.
        scope.launch {
            delay(timeoutMs)
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
            activeChallengeId = null
            Log.d(TAG, "SMS listener stopped")
        }
    }

    private fun sendCodeToServer(challengeId: String, sender: String, code: String) {
        scope.launch {
            val maxRetries = 5
            var attempt = 0
            var delayMs = 3_000L

            while (attempt < maxRetries) {
                attempt++
                try {
                    val settings = settingsStore.load()
                    if (settings.backendBaseUrl.isBlank() || settings.apiToken.isBlank()) {
                        throw IllegalStateException(getString(R.string.status_missing_setup))
                    }
                    backendApi.submitSmsCode(
                        backendBaseUrl = settings.backendBaseUrl,
                        apiToken = settings.apiToken,
                        challengeId = challengeId,
                        sender = sender,
                        code = code,
                    )
                    Log.d(TAG, "Code successfully forwarded (attempt $attempt)")
                    showStatusNotification(getString(R.string.status_processed))
                    settingsStore.saveLastMessage(getString(R.string.status_processed))
                    return@launch
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

    private fun hasSmsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECEIVE_SMS) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSmsListening()
        scope.cancel()
    }
}
