package com.hermans.onssmsrelay.data

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

data class AppSettings(
    val backendBaseUrl: String,
    val loginUrl: String,
    val username: String,
    val setupSecret: String,
    val deviceLabel: String,
    val selectedPortalId: String,
    val selectedPortalName: String,
    val selectedPortalLogoUrl: String,
    val portalCatalogJson: String,
    val apiToken: String,
    val fcmToken: String,
    val statusCode: String,
    val currentPhase: String,
    val lastMessage: String,
    val lastError: String,
    val lastSuccessAt: String,
    val fcmConfigured: Boolean,
    val paired: Boolean,
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
            selectedPortalId = preferences.getString(KEY_SELECTED_PORTAL_ID, "").orEmpty(),
            selectedPortalName = preferences.getString(KEY_SELECTED_PORTAL_NAME, "").orEmpty(),
            selectedPortalLogoUrl = preferences.getString(KEY_SELECTED_PORTAL_LOGO_URL, "").orEmpty(),
            portalCatalogJson = preferences.getString(KEY_PORTAL_CATALOG_JSON, "").orEmpty(),
            apiToken = preferences.getString(KEY_API_TOKEN, "").orEmpty(),
            fcmToken = preferences.getString(KEY_FCM_TOKEN, "").orEmpty(),
            statusCode = preferences.getString(KEY_STATUS_CODE, "idle").orEmpty(),
            currentPhase = preferences.getString(KEY_CURRENT_PHASE, "idle").orEmpty(),
            lastMessage = preferences.getString(KEY_LAST_MESSAGE, "").orEmpty(),
            lastError = preferences.getString(KEY_LAST_ERROR, "").orEmpty(),
            lastSuccessAt = preferences.getString(KEY_LAST_SUCCESS_AT, "").orEmpty(),
            fcmConfigured = preferences.getBoolean(KEY_FCM_CONFIGURED, false),
            paired = preferences.getBoolean(KEY_PAIRED, false),
        )
    }

    fun saveSetupFields(
        backendBaseUrl: String,
        loginUrl: String,
        username: String,
        setupSecret: String,
        deviceLabel: String,
        selectedPortalId: String,
        selectedPortalName: String,
        selectedPortalLogoUrl: String,
    ) {
        // The app stores only the reusable routing data locally. The password stays on the backend.
        preferences.edit()
            .putString(KEY_BACKEND_BASE_URL, backendBaseUrl)
            .putString(KEY_LOGIN_URL, loginUrl)
            .putString(KEY_USERNAME, username)
            .putString(KEY_SETUP_SECRET, setupSecret)
            .putString(KEY_DEVICE_LABEL, deviceLabel)
            .putString(KEY_SELECTED_PORTAL_ID, selectedPortalId)
            .putString(KEY_SELECTED_PORTAL_NAME, selectedPortalName)
            .putString(KEY_SELECTED_PORTAL_LOGO_URL, selectedPortalLogoUrl)
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
        val editor = preferences.edit()
            .putString(KEY_STATUS_CODE, status.sync.statusCode)
            .putString(KEY_CURRENT_PHASE, status.sync.currentPhase)
            .putString(KEY_LAST_MESSAGE, status.sync.lastMessage)
            .putString(KEY_LAST_ERROR, status.sync.lastError)
            .putString(KEY_LAST_SUCCESS_AT, status.sync.lastSuccessAt)
            .putBoolean(KEY_FCM_CONFIGURED, status.fcmConfigured)
            .putBoolean(KEY_PAIRED, status.paired)
            .putString(KEY_LOGIN_URL, status.loginUrl)
            .putString(KEY_SELECTED_PORTAL_ID, status.portalId)
            .putString(KEY_SELECTED_PORTAL_NAME, status.portalName)
            .putString(KEY_SELECTED_PORTAL_LOGO_URL, status.portalLogoUrl)

        if (status.portals.isNotEmpty()) {
            editor.putString(KEY_PORTAL_CATALOG_JSON, serializePortals(status.portals))
        }

        editor.apply()
    }

    fun saveLastMessage(message: String, error: String = "") {
        preferences.edit()
            .putString(KEY_LAST_MESSAGE, message)
            .putString(KEY_LAST_ERROR, error)
            .apply()
    }

    fun savePortalCatalog(config: MobileConfig) {
        preferences.edit()
            .putString(KEY_PORTAL_CATALOG_JSON, serializePortals(config.portals))
            .apply()
    }

    fun saveSelectedPortal(portal: PortalOption) {
        preferences.edit()
            .putString(KEY_SELECTED_PORTAL_ID, portal.portalId)
            .putString(KEY_SELECTED_PORTAL_NAME, portal.name)
            .putString(KEY_SELECTED_PORTAL_LOGO_URL, portal.logoUrl)
            .putString(KEY_LOGIN_URL, portal.loginUrl)
            .apply()
    }

    fun availablePortals(settings: AppSettings = load()): List<PortalOption> {
        val rawJson = settings.portalCatalogJson
        if (rawJson.isBlank()) {
            return listOf(
                PortalOption(
                    portalId = settings.selectedPortalId.ifBlank { "land-van-horne" },
                    name = settings.selectedPortalName.ifBlank { "Land van Horne" },
                    loginUrl = settings.loginUrl.ifBlank { DEFAULT_LOGIN_URL },
                    logoUrl = settings.selectedPortalLogoUrl,
                    isSelected = true,
                ),
            )
        }

        val selectedPortalId = settings.selectedPortalId
        val portals = try {
            deserializePortals(rawJson)
        } catch (_: Exception) {
            emptyList()
        }
        return portals.map { portal ->
            portal.copy(isSelected = portal.portalId == selectedPortalId)
        }
    }

    fun clearPairing() {
        // Clear the pairing state while keeping reusable routing and portal settings.
        preferences.edit()
            .putString(KEY_API_TOKEN, "")
            .putString(KEY_STATUS_CODE, "idle")
            .putString(KEY_CURRENT_PHASE, "idle")
            .putString(KEY_LAST_MESSAGE, "")
            .putString(KEY_LAST_ERROR, "")
            .putString(KEY_LAST_SUCCESS_AT, "")
            .putBoolean(KEY_FCM_CONFIGURED, false)
            .putBoolean(KEY_PAIRED, false)
            .apply()
    }

    private fun serializePortals(portals: List<PortalOption>): String {
        val array = JSONArray()
        portals.forEach { portal ->
            array.put(
                JSONObject().apply {
                    put("portal_id", portal.portalId)
                    put("name", portal.name)
                    put("login_url", portal.loginUrl)
                    put("logo_url", portal.logoUrl)
                    put("is_selected", portal.isSelected)
                },
            )
        }
        return array.toString()
    }

    private fun deserializePortals(rawJson: String): List<PortalOption> {
        val array = JSONArray(rawJson)
        return buildList {
            for (index in 0 until array.length()) {
                val portalJson = array.optJSONObject(index) ?: continue
                add(
                    PortalOption(
                        portalId = portalJson.optString("portal_id"),
                        name = portalJson.optString("name"),
                        loginUrl = portalJson.optString("login_url"),
                        logoUrl = portalJson.optString("logo_url"),
                        isSelected = portalJson.optBoolean("is_selected", false),
                    ),
                )
            }
        }
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
        private const val KEY_SELECTED_PORTAL_ID = "selected_portal_id"
        private const val KEY_SELECTED_PORTAL_NAME = "selected_portal_name"
        private const val KEY_SELECTED_PORTAL_LOGO_URL = "selected_portal_logo_url"
        private const val KEY_PORTAL_CATALOG_JSON = "portal_catalog_json"
        private const val KEY_API_TOKEN = "api_token"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_STATUS_CODE = "status_code"
        private const val KEY_CURRENT_PHASE = "current_phase"
        private const val KEY_LAST_MESSAGE = "last_message"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_LAST_SUCCESS_AT = "last_success_at"
        private const val KEY_FCM_CONFIGURED = "fcm_configured"
        private const val KEY_PAIRED = "paired"
    }
}
