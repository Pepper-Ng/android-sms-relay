package nl.landvanhorne.smsrelay.data

import android.content.Context
import android.os.Build

data class AppSettings(
    val backendBaseUrl: String,
    val loginUrl: String,
    val username: String,
    val setupSecret: String,
    val deviceLabel: String,
    val apiToken: String,
    val fcmToken: String,
    val statusCode: String,
    val currentPhase: String,
    val lastMessage: String,
    val lastError: String,
    val lastSuccessAt: String,
    val fcmConfigured: Boolean,
)

class AppSettingsStore(context: Context) {

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings {
        return AppSettings(
            backendBaseUrl = preferences.getString(KEY_BACKEND_BASE_URL, DEFAULT_BACKEND_BASE_URL).orEmpty(),
            loginUrl = preferences.getString(KEY_LOGIN_URL, DEFAULT_LOGIN_URL).orEmpty(),
            username = preferences.getString(KEY_USERNAME, "").orEmpty(),
            setupSecret = preferences.getString(KEY_SETUP_SECRET, "").orEmpty(),
            deviceLabel = preferences.getString(KEY_DEVICE_LABEL, buildDefaultDeviceLabel()).orEmpty(),
            apiToken = preferences.getString(KEY_API_TOKEN, "").orEmpty(),
            fcmToken = preferences.getString(KEY_FCM_TOKEN, "").orEmpty(),
            statusCode = preferences.getString(KEY_STATUS_CODE, "idle").orEmpty(),
            currentPhase = preferences.getString(KEY_CURRENT_PHASE, "idle").orEmpty(),
            lastMessage = preferences.getString(KEY_LAST_MESSAGE, "").orEmpty(),
            lastError = preferences.getString(KEY_LAST_ERROR, "").orEmpty(),
            lastSuccessAt = preferences.getString(KEY_LAST_SUCCESS_AT, "").orEmpty(),
            fcmConfigured = preferences.getBoolean(KEY_FCM_CONFIGURED, false),
        )
    }

    fun saveSetupFields(
        backendBaseUrl: String,
        loginUrl: String,
        username: String,
        setupSecret: String,
        deviceLabel: String,
    ) {
        // The app stores only the reusable routing data locally. The password stays on the backend.
        preferences.edit()
            .putString(KEY_BACKEND_BASE_URL, backendBaseUrl)
            .putString(KEY_LOGIN_URL, loginUrl)
            .putString(KEY_USERNAME, username)
            .putString(KEY_SETUP_SECRET, setupSecret)
            .putString(KEY_DEVICE_LABEL, deviceLabel)
            .apply()
    }

    fun saveApiToken(apiToken: String) {
        preferences.edit().putString(KEY_API_TOKEN, apiToken).apply()
    }

    fun saveFcmToken(fcmToken: String) {
        preferences.edit().putString(KEY_FCM_TOKEN, fcmToken).apply()
    }

    fun saveStatus(status: BackendStatus) {
        // The latest backend state is mirrored locally so the UI can render it immediately on startup.
        preferences.edit()
            .putString(KEY_STATUS_CODE, status.sync.statusCode)
            .putString(KEY_CURRENT_PHASE, status.sync.currentPhase)
            .putString(KEY_LAST_MESSAGE, status.sync.lastMessage)
            .putString(KEY_LAST_ERROR, status.sync.lastError)
            .putString(KEY_LAST_SUCCESS_AT, status.sync.lastSuccessAt)
            .putBoolean(KEY_FCM_CONFIGURED, status.fcmConfigured)
            .apply()
    }

    fun saveLastMessage(message: String, error: String = "") {
        preferences.edit()
            .putString(KEY_LAST_MESSAGE, message)
            .putString(KEY_LAST_ERROR, error)
            .apply()
    }

    private fun buildDefaultDeviceLabel(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        return listOf(manufacturer, model)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { "Android-telefoon" }
    }

    companion object {
        private const val PREFERENCES_NAME = "ons_rooster_settings"
        private const val DEFAULT_BACKEND_BASE_URL = "https://onsrooster.stefhermans.nl"
        private const val DEFAULT_LOGIN_URL = "https://landvanhorne.hasmoves.com"

        private const val KEY_BACKEND_BASE_URL = "backend_base_url"
        private const val KEY_LOGIN_URL = "login_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_SETUP_SECRET = "setup_secret"
        private const val KEY_DEVICE_LABEL = "device_label"
        private const val KEY_API_TOKEN = "api_token"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_STATUS_CODE = "status_code"
        private const val KEY_CURRENT_PHASE = "current_phase"
        private const val KEY_LAST_MESSAGE = "last_message"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_LAST_SUCCESS_AT = "last_success_at"
        private const val KEY_FCM_CONFIGURED = "fcm_configured"
    }
}
