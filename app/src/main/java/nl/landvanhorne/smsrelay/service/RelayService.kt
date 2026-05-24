package nl.landvanhorne.smsrelay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import nl.landvanhorne.smsrelay.R
import nl.landvanhorne.smsrelay.ui.MainActivity
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RelayService : Service() {

    companion object {
        const val TAG = "RelayService"
        const val CHANNEL_ID = "relay_channel"
        const val NOTIF_ID = 1

        // The URL of the backend server.
        // Use ws:// for local networks, or wss:// if a domain with TLS is configured.
        const val SERVER_URL = "ws://backend-server.local:8765"

        // A shared flag that the SmsReceiver monitors.
        @Volatile
        var isListeningForSms = false

        // A callback invoked by SmsReceiver when a code is received.
        var onSmsReceived: ((sender: String, code: String) -> Unit)? = null
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        // Maintain the connection through NAT using a ping interval.
        .pingInterval(30, TimeUnit.SECONDS)
        // No read timeout for persistent WebSockets.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val notification = buildNotification(getString(R.string.status_connecting))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        connectWebSocket()

        // When SmsReceiver returns a code, it is forwarded via the WebSocket.
        onSmsReceived = { sender, code ->
            Log.d(TAG, "SMS received from $sender, code: $code")
            sendToBackend(sender, code)
            isListeningForSms = false
            updateNotification(getString(R.string.status_connected_waiting))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android restarts the service if it is killed.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        webSocket?.close(1000, "Service stopped")
        onSmsReceived = null
    }

    // -----------------------------------------------------------------------
    // WebSocket connection
    // -----------------------------------------------------------------------

    private fun connectWebSocket() {
        val request = Request.Builder().url(SERVER_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                updateNotification(getString(R.string.status_connected_waiting))
                // Identify this client to the backend.
                webSocket.send(JSONObject().apply {
                    put("type", "identify")
                    put("client", "android-sms-relay")
                }.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Message received: $text")
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                updateNotification(getString(R.string.status_disconnected_reconnecting))
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                if (code != 1000) {
                    scheduleReconnect()
                }
            }
        })
    }

    private fun handleMessage(raw: String) {
        try {
            val json = JSONObject(raw)
            when (json.getString("type")) {

                // The backend triggers the SMS listening mode.
                "listen_sms" -> {
                    Log.d(TAG, "Trigger received: SMS listening mode enabled")
                    isListeningForSms = true
                    updateNotification(getString(R.string.status_waiting_sms))
                }

                // The backend provides an optional acknowledgement.
                "ack" -> {
                    Log.d(TAG, "Backend confirmation: ${json.optString("message")}")
                }

                else -> Log.w(TAG, "Unknown message type: ${json.getString("type")}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing message: ${e.message}")
        }
    }

    private fun sendToBackend(sender: String, code: String) {
        val payload = JSONObject().apply {
            put("type", "sms_code")
            put("sender", sender)
            put("code", code)
        }.toString()

        val sent = webSocket?.send(payload)
        Log.d(TAG, "SMS code sent to backend: $sent")
    }

    // -----------------------------------------------------------------------
    // Reconnection with exponential backoff
    // -----------------------------------------------------------------------

    // Starting delay of 5 seconds.
    private var reconnectDelay = 5_000L

    private fun scheduleReconnect() {
        serviceScope.launch {
            Log.d(TAG, "Reconnecting in ${reconnectDelay / 1000}s...")
            delay(reconnectDelay)
            // Double the delay up to a maximum of 60 seconds.
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(60_000L)
            connectWebSocket()
        }
    }

    // -----------------------------------------------------------------------
    // Notifications (required for ForegroundService)
    // -----------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_relay)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIF_ID, buildNotification(status))
    }
}
