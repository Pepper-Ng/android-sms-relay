package com.hermans.onssmsrelay.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

data class PortalOption(
    val portalId: String,
    val name: String,
    val loginUrl: String,
    val logoUrl: String,
    val isSelected: Boolean = false,
)

data class MobileConfig(
    val publicBaseUrl: String,
    val defaultPortalId: String,
    val portals: List<PortalOption>,
)

data class BackendSyncStatus(
    val statusCode: String,
    val currentPhase: String,
    val authReady: Boolean,
    val lastMessage: String,
    val lastError: String,
    val lastSuccessAt: String,
)

data class BackendStatus(
    val publicBaseUrl: String,
    val loginUrl: String,
    val username: String,
    val fcmConfigured: Boolean,
    val portalId: String,
    val portalName: String,
    val portalLogoUrl: String,
    val paired: Boolean,
    val connected: Boolean,
    val deviceId: String,
    val portals: List<PortalOption>,
    val sync: BackendSyncStatus,
)

data class SetupResponse(
    val apiToken: String?,
    val message: String,
    val status: BackendStatus,
)

class BackendApi(
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {

    suspend fun submitSetup(
        backendBaseUrl: String,
        loginUrl: String,
        portalId: String,
        username: String,
        password: String,
        fcmToken: String,
        deviceLabel: String,
        setupSecret: String,
        apiToken: String,
    ): SetupResponse = withContext(Dispatchers.IO) {
        // The initial setup request is the only moment where the password passes through the app.
        val payload = JSONObject().apply {
            if (portalId.isNotBlank()) {
                put("portal_id", portalId)
            }
            if (loginUrl.isNotBlank()) {
                put("login_url", loginUrl)
            }
            put("username", username)
            put("password", password)
            put("fcm_token", fcmToken)
            put("device_label", deviceLabel)
            if (setupSecret.isNotBlank()) {
                put("setup_secret", setupSecret)
            }
        }
        val responseJson = executeJsonRequest(
            method = "POST",
            baseUrl = backendBaseUrl,
            path = "/api/v1/mobile/setup",
            body = payload,
            apiToken = apiToken,
        )

        SetupResponse(
            apiToken = responseJson.optNullableNonBlankString("api_token"),
            message = responseJson.optString("message").ifBlank { "De gegevens zijn opgeslagen." },
            status = parseStatus(responseJson.optJSONObject("status") ?: JSONObject()),
        )
    }

    suspend fun fetchMobileConfig(backendBaseUrl: String): MobileConfig = withContext(Dispatchers.IO) {
        val responseJson = executeJsonRequest(
            method = "GET",
            baseUrl = backendBaseUrl,
            path = "/api/v1/mobile/config",
        )
        parseMobileConfig(responseJson)
    }

    suspend fun fetchStatus(backendBaseUrl: String, apiToken: String): BackendStatus = withContext(Dispatchers.IO) {
        val responseJson = executeJsonRequest(
            method = "GET",
            baseUrl = backendBaseUrl,
            path = "/api/v1/mobile/status",
            apiToken = apiToken,
        )
        parseStatus(responseJson)
    }

    suspend fun checkHealth(backendBaseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val responseJson = executeJsonRequest(
            method = "GET",
            baseUrl = backendBaseUrl,
            path = "/healthz",
        )
        responseJson.optString("status") == "ok"
    }

    suspend fun updateFcmToken(
        backendBaseUrl: String,
        apiToken: String,
        fcmToken: String,
        deviceLabel: String,
    ) = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("fcm_token", fcmToken)
            put("device_label", deviceLabel)
        }
        executeJsonRequest(
            method = "POST",
            baseUrl = backendBaseUrl,
            path = "/api/v1/mobile/tokens/fcm",
            body = payload,
            apiToken = apiToken,
        )
    }

    suspend fun removeDevice(backendBaseUrl: String, apiToken: String) = withContext(Dispatchers.IO) {
        executeJsonRequest(
            method = "DELETE",
            baseUrl = backendBaseUrl,
            path = "/api/v1/mobile/device",
            apiToken = apiToken,
        )
    }

    suspend fun submitSmsCode(
        backendBaseUrl: String,
        apiToken: String,
        challengeId: String,
        sender: String,
        code: String,
    ) = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("sender", sender)
            put("code", code)
        }
        executeJsonRequest(
            method = "POST",
            baseUrl = backendBaseUrl,
            path = "/api/v1/mobile/challenges/$challengeId/sms-code",
            body = payload,
            apiToken = apiToken,
        )
    }

    fun openStatusWebSocket(
        backendBaseUrl: String,
        apiToken: String,
        onOpen: () -> Unit,
        onStatus: (BackendStatus) -> Unit,
        onClosed: (String?) -> Unit,
        onFailure: (String) -> Unit,
    ): WebSocket {
        val request = Request.Builder()
            .url(toWebSocketUrl(backendBaseUrl, "/api/v1/mobile/live"))
            .header("Authorization", "Bearer $apiToken")
            .build()

        return httpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        onStatus(parseStatus(JSONObject(text)))
                    } catch (error: Exception) {
                        onFailure(error.message ?: "Onleesbare live status ontvangen.")
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    onClosed(reason.takeIf { it.isNotBlank() })
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    onFailure(t.message ?: "Live verbinding met de backend mislukt.")
                }
            },
        )
    }

    private fun executeJsonRequest(
        method: String,
        baseUrl: String,
        path: String,
        body: JSONObject? = null,
        apiToken: String = "",
    ): JSONObject {
        // All backend traffic goes through this single helper so auth and error handling stay consistent.
        val requestBuilder = Request.Builder()
            .url(normalizeBaseUrl(baseUrl) + path)
            .header("Accept", "application/json")

        if (apiToken.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiToken")
        }

        if (body != null) {
            requestBuilder.method(
                method,
                body.toString().toRequestBody(JSON_MEDIA_TYPE),
            )
        } else {
            requestBuilder.method(method, null)
        }

        try {
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val message = responseBody.ifBlank { "De backend antwoordde met ${response.code}." }
                    throw IOException(message)
                }
                return if (responseBody.isBlank()) JSONObject() else JSONObject(responseBody)
            }
        } catch (exception: SSLHandshakeException) {
            throw IOException(
                "HTTPS-validatie mislukt voor de backendverbinding. Controleer het certificaat of probeer een nieuwer Android-apparaat.",
                exception,
            )
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        private fun JSONObject.optNullableNonBlankString(key: String): String? {
            if (!has(key) || isNull(key)) {
                return null
            }
            return optString(key).trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        }

        private fun parsePortals(array: JSONArray?): List<PortalOption> {
            if (array == null) {
                return emptyList()
            }
            return buildList {
                for (index in 0 until array.length()) {
                    val portalJson = array.optJSONObject(index) ?: continue
                    add(
                        PortalOption(
                            portalId = portalJson.optNullableNonBlankString("portal_id").orEmpty(),
                            name = portalJson.optNullableNonBlankString("name").orEmpty(),
                            loginUrl = portalJson.optNullableNonBlankString("login_url").orEmpty(),
                            logoUrl = portalJson.optNullableNonBlankString("logo_url").orEmpty(),
                            isSelected = portalJson.optBoolean("is_selected", false),
                        ),
                    )
                }
            }
        }

        fun normalizeBaseUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim().removeSuffix("/")
            return when {
                trimmed.startsWith("https://") || trimmed.startsWith("http://") -> trimmed
                trimmed.isEmpty() -> ""
                else -> "https://$trimmed"
            }
        }

        private fun toWebSocketUrl(baseUrl: String, path: String): String {
            val normalized = normalizeBaseUrl(baseUrl)
            return when {
                normalized.startsWith("https://") -> "wss://${normalized.removePrefix("https://")}$path"
                normalized.startsWith("http://") -> "ws://${normalized.removePrefix("http://")}$path"
                else -> "$normalized$path"
            }
        }

        fun parseMobileConfig(json: JSONObject): MobileConfig {
            return MobileConfig(
                publicBaseUrl = json.optString("public_base_url"),
                defaultPortalId = json.optString("default_portal_id"),
                portals = parsePortals(json.optJSONArray("portals")),
            )
        }

        fun parseStatus(json: JSONObject): BackendStatus {
            val sync = json.optJSONObject("sync") ?: JSONObject()
            return BackendStatus(
                publicBaseUrl = json.optNullableNonBlankString("public_base_url").orEmpty(),
                loginUrl = json.optNullableNonBlankString("login_url").orEmpty(),
                username = json.optNullableNonBlankString("username").orEmpty(),
                fcmConfigured = json.optBoolean("fcm_configured"),
                portalId = json.optNullableNonBlankString("portal_id").orEmpty(),
                portalName = json.optNullableNonBlankString("portal_name").orEmpty(),
                portalLogoUrl = json.optNullableNonBlankString("portal_logo_url").orEmpty(),
                paired = json.optBoolean("paired", false),
                connected = json.optBoolean("connected", false),
                deviceId = json.optNullableNonBlankString("device_id").orEmpty(),
                portals = parsePortals(json.optJSONArray("portals")),
                sync = BackendSyncStatus(
                    statusCode = sync.optString("status", "idle"),
                    currentPhase = sync.optString("current_phase", "idle"),
                    authReady = sync.optBoolean("auth_ready", false),
                    lastMessage = sync.optNullableNonBlankString("last_message").orEmpty(),
                    lastError = sync.optNullableNonBlankString("last_error").orEmpty(),
                    lastSuccessAt = sync.optNullableNonBlankString("last_success_at").orEmpty(),
                ),
            )
        }
    }
}
