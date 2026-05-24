package nl.landvanhorne.smsrelay.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

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
    val sync: BackendSyncStatus,
)

data class SetupResponse(
    val apiToken: String?,
    val message: String,
    val status: BackendStatus,
)

class BackendApi(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {

    suspend fun submitSetup(
        backendBaseUrl: String,
        loginUrl: String,
        username: String,
        password: String,
        fcmToken: String,
        deviceLabel: String,
        setupSecret: String,
        apiToken: String,
    ): SetupResponse = withContext(Dispatchers.IO) {
        // The initial setup request is the only moment where the password passes through the app.
        val payload = JSONObject().apply {
            put("login_url", loginUrl)
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
            apiToken = responseJson.optString("api_token").takeIf { it.isNotBlank() },
            message = responseJson.optString("message"),
            status = parseStatus(responseJson.optJSONObject("status") ?: JSONObject()),
        )
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

        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = responseBody.ifBlank { "De backend antwoordde met ${response.code}." }
                throw IOException(message)
            }
            return if (responseBody.isBlank()) JSONObject() else JSONObject(responseBody)
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun normalizeBaseUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim().removeSuffix("/")
            return when {
                trimmed.startsWith("https://") || trimmed.startsWith("http://") -> trimmed
                trimmed.isEmpty() -> ""
                else -> "https://$trimmed"
            }
        }

        fun parseStatus(json: JSONObject): BackendStatus {
            val sync = json.optJSONObject("sync") ?: JSONObject()
            return BackendStatus(
                publicBaseUrl = json.optString("public_base_url"),
                loginUrl = json.optString("login_url"),
                username = json.optString("username"),
                fcmConfigured = json.optBoolean("fcm_configured"),
                sync = BackendSyncStatus(
                    statusCode = sync.optString("status", "idle"),
                    currentPhase = sync.optString("current_phase", "idle"),
                    authReady = sync.optBoolean("auth_ready", false),
                    lastMessage = sync.optString("last_message"),
                    lastError = sync.optString("last_error"),
                    lastSuccessAt = sync.optString("last_success_at"),
                ),
            )
        }
    }
}
