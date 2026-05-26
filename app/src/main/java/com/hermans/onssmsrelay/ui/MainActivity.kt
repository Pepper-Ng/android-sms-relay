package com.hermans.onssmsrelay.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.firebase.messaging.FirebaseMessaging
import com.hermans.onssmsrelay.R
import com.hermans.onssmsrelay.data.AppSettings
import com.hermans.onssmsrelay.data.AppSettingsStore
import com.hermans.onssmsrelay.data.BackendApi
import com.hermans.onssmsrelay.data.BackendStatus
import com.hermans.onssmsrelay.data.MobileConfig
import com.hermans.onssmsrelay.data.PortalOption
import com.hermans.onssmsrelay.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.WebSocket
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MainActivity : AppCompatActivity() {

    private enum class Page(val menuItemId: Int, val titleRes: Int) {
        LOGIN(R.id.nav_login, R.string.menu_login),
        STATUS(R.id.nav_status, R.string.menu_status),
        PERMISSIONS(R.id.nav_permissions, R.string.menu_permissions),
        SETTINGS(R.id.nav_settings, R.string.menu_settings),
        ABOUT(R.id.nav_about, R.string.menu_about),
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsStore: AppSettingsStore
    private val backendApi = BackendApi()
    private var currentPage = Page.LOGIN
    private var permissionsPrompt: String? = null
    private var showLoginFields = false
    private var portalOptions: List<PortalOption> = emptyList()
    private var selectedPortalId = ""
    private var backendAvailable: Boolean? = null
    private var loginHelperMessage: String? = null
    private var statusSocket: WebSocket? = null
    private var socketConnected = false
    private var allowSocketReconnect = false
    private var healthCheckJob: Job? = null
    private var healthCheckBaseUrl = ""
    private var reconnectJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        if (requiredPermissions().all(::hasPermission)) {
            permissionsPrompt = null
            Toast.makeText(this, R.string.toast_permission_granted, Toast.LENGTH_SHORT).show()
            showPage(Page.LOGIN)
        } else {
            Toast.makeText(this, R.string.toast_permission_denied, Toast.LENGTH_LONG).show()
            showPage(Page.PERMISSIONS)
        }
        refreshUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settingsStore = AppSettingsStore(this)

        val initialSettings = settingsStore.load()
        portalOptions = settingsStore.availablePortals(initialSettings)
        selectedPortalId = initialSettings.selectedPortalId.ifBlank { portalOptions.firstOrNull()?.portalId.orEmpty() }
        showLoginFields = initialSettings.apiToken.isBlank()
        populateForm(initialSettings)
        binding.tvInstructions.text = getString(R.string.ui_about_body, appVersionName())

        binding.topAppBar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        binding.navView.setNavigationItemSelectedListener { item ->
            handleNavigation(item)
        }

        binding.etPortalSelector.setOnItemClickListener { _, _, position, _ ->
            portalOptions.getOrNull(position)?.let { portal ->
                applyPortalSelection(portal, persist = true)
                refreshUi()
            }
        }

        binding.btnRequestPermissions.setOnClickListener {
            permissionLauncher.launch(requiredPermissions().toTypedArray())
        }

        binding.btnBatteryOptimization.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } else {
                Toast.makeText(this, R.string.ui_battery_not_needed, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCopyToken.setOnClickListener {
            val token = binding.tvFcmToken.text.toString()
            if (token.isNotEmpty() && token != getString(R.string.ui_token_loading)) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("FCM token", token))
                Toast.makeText(this, R.string.ui_token_copied, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSaveSetup.setOnClickListener {
            submitSetup()
        }

        binding.btnRedoLogin.setOnClickListener {
            showLoginFields = true
            permissionsPrompt = null
            refreshUi()
            showPage(Page.LOGIN)
        }

        binding.btnUnpairDevice.setOnClickListener {
            unpairDevice()
        }

        binding.btnSaveSettings.setOnClickListener {
            saveAdvancedSettings(showToast = true)
        }

        binding.btnRefreshStatus.setOnClickListener {
            refreshStatus(showErrors = true)
        }

        showPage(if (initialSettings.apiToken.isNotBlank()) Page.STATUS else Page.LOGIN, closeDrawer = false)
        refreshUi()
        refreshPortalConfig(showErrors = false)
        refreshStatus(showErrors = false)
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        syncStatusRealtimeState(forceReconnect = currentPage == Page.STATUS)
    }

    override fun onPause() {
        super.onPause()
        stopStatusRealtimeState()
    }

    private fun refreshUi() {
        val smsOk = hasPermission(Manifest.permission.RECEIVE_SMS) &&
            hasPermission(Manifest.permission.READ_SMS)
        val notificationsOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
        val permissionsGranted = smsOk && notificationsOk

        binding.tvPermissionsStatus.text = when {
            smsOk && notificationsOk -> getString(R.string.ui_all_permissions_granted)
            !smsOk && !notificationsOk -> getString(R.string.ui_permissions_missing)
            !smsOk -> getString(R.string.ui_sms_permission_missing)
            else -> getString(R.string.ui_notif_permission_missing)
        }
        binding.tvPermissionsGateMessage.isVisible = !permissionsGranted && !permissionsPrompt.isNullOrBlank()
        binding.tvPermissionsGateMessage.text = permissionsPrompt.orEmpty()

        val settings = settingsStore.load()
        val hasSavedLogin = settings.apiToken.isNotBlank()
        val isLoggedIn = settings.paired && settings.statusCode == "success" && settings.currentPhase == "ready"
        if (!hasSavedLogin) {
            showLoginFields = true
        }

        binding.tvLoginHelper.isVisible = !loginHelperMessage.isNullOrBlank()
        binding.tvLoginHelper.text = loginHelperMessage.orEmpty()

        portalOptions = settingsStore.availablePortals(settings)
        if (selectedPortalId.isBlank()) {
            selectedPortalId = settings.selectedPortalId.ifBlank { portalOptions.firstOrNull()?.portalId.orEmpty() }
        }
        val selectedPortal = currentSelectedPortal(settings)
        renderPortalSelection(selectedPortal)

        val navHeaderView = binding.navView.getHeaderView(0)
        val statusBadgeIndicator = navHeaderView.findViewById<View>(R.id.status_badge_indicator)
        val statusBadgeText = navHeaderView.findViewById<TextView>(R.id.status_badge_text)
        val statusBadgeSubtext = navHeaderView.findViewById<TextView>(R.id.status_badge_subtext)

        val menuConnectionState = when (backendAvailable) {
            true -> true
            false -> false
            null -> if (settings.paired || hasSavedLogin) null else false
        }
        val connectionLabel = when (menuConnectionState) {
            true -> getString(R.string.status_badge_connected)
            false -> getString(R.string.status_badge_disconnected)
            null -> getString(R.string.status_badge_checking)
        }
        val pairedLabel = if (settings.paired || hasSavedLogin) {
            getString(R.string.status_badge_paired)
        } else {
            getString(R.string.status_badge_not_paired)
        }
        statusBadgeText.text = getString(R.string.status_badge_combined, connectionLabel, pairedLabel)
        statusBadgeSubtext.text = selectedPortal?.name ?: getString(R.string.status_badge_subtext_waiting)
        val indicatorColor = when (menuConnectionState) {
            true -> android.R.color.holo_green_light
            false -> if (settings.paired || hasSavedLogin) android.R.color.holo_red_light else android.R.color.darker_gray
            null -> android.R.color.holo_orange_light
        }
        statusBadgeIndicator.setBackgroundColor(ContextCompat.getColor(this, indicatorColor))

        binding.loginStateCard.isVisible = hasSavedLogin && !showLoginFields
        binding.loginFormContainer.isVisible = !hasSavedLogin || showLoginFields
        binding.btnSaveSetup.text = getString(if (hasSavedLogin) R.string.ui_btn_relogin else R.string.ui_btn_save_setup)
        binding.tvLoginStateTitle.text = getString(if (isLoggedIn) R.string.ui_login_state_logged_in else R.string.ui_login_state_paired)
        binding.tvLoginStateBody.text = if (isLoggedIn) {
            getString(R.string.ui_login_state_logged_in_body, formatTimestampForDisplay(settings.lastSuccessAt))
        } else {
            getString(R.string.ui_login_state_paired_body, settings.lastMessage.ifBlank { "-" })
        }

        binding.tvSavedConfiguration.text = getString(
            R.string.ui_saved_configuration_value,
            settings.backendBaseUrl,
            selectedPortal?.name ?: settings.selectedPortalName.ifBlank { getString(R.string.ui_unknown_portal) },
            mask(settings.username),
            if (settings.apiToken.isNotBlank()) getString(R.string.ui_yes) else getString(R.string.ui_no),
        )
        binding.tvBackendStatus.text = getString(
            R.string.ui_backend_status_value,
            mapStatusCode(settings.statusCode),
            mapPhase(settings.currentPhase),
            settings.lastMessage.ifBlank { "-" },
            settings.lastError.ifBlank { "-" },
            formatTimestampForDisplay(settings.lastSuccessAt),
            if (settings.fcmConfigured) getString(R.string.ui_yes) else getString(R.string.ui_no),
        )
        val realtimeLabel = when {
            socketConnected -> getString(R.string.ui_realtime_connected)
            statusSocket != null -> getString(R.string.ui_realtime_connecting)
            else -> getString(R.string.ui_realtime_disconnected)
        }
        binding.tvRealtimeStatus.text = getString(
            R.string.ui_realtime_status_value,
            realtimeLabel,
            pairedLabel,
        )
        binding.tvBackendLiveness.text = getString(
            R.string.ui_backend_liveness_value,
            when (backendAvailable) {
                true -> getString(R.string.ui_backend_available)
                false -> getString(R.string.ui_backend_unavailable)
                null -> getString(R.string.ui_backend_checking)
            },
        )

        if (settings.fcmToken.isNotBlank()) {
            binding.tvFcmToken.text = settings.fcmToken
        } else {
            binding.tvFcmToken.text = getString(R.string.ui_token_loading)
            lifecycleScope.launch {
                try {
                    val token = fetchFcmToken()
                    settingsStore.saveFcmToken(token)
                    binding.tvFcmToken.text = token
                } catch (_: Exception) {
                    binding.tvFcmToken.text = getString(R.string.ui_token_loading)
                }
            }
        }
    }

    private fun refreshPortalConfig(showErrors: Boolean) {
        val settings = settingsStore.load()
        val backendUrl = binding.etBackendUrl.text?.toString().orEmpty().trim().ifBlank { settings.backendBaseUrl }
        lifecycleScope.launch {
            try {
                val config = backendApi.fetchMobileConfig(backendUrl)
                loginHelperMessage = null
                applyPortalConfig(config)
                refreshUi()
            } catch (exception: Exception) {
                if (settings.apiToken.isBlank()) {
                    loginHelperMessage = getString(R.string.ui_login_helper_missing_configuration)
                    refreshUi()
                }
                if (showErrors) {
                    Toast.makeText(
                        this@MainActivity,
                        exception.message ?: getString(R.string.toast_portal_config_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun applyPortalConfig(config: MobileConfig) {
        settingsStore.savePortalCatalog(config)
        portalOptions = config.portals
        val settings = settingsStore.load()
        val selectedPortal = config.portals.firstOrNull { it.portalId == selectedPortalId }
            ?: config.portals.firstOrNull { it.portalId == settings.selectedPortalId }
            ?: config.portals.firstOrNull { it.portalId == config.defaultPortalId }
            ?: config.portals.firstOrNull()
        if (selectedPortal != null) {
            applyPortalSelection(selectedPortal, persist = true)
        }
    }

    private fun submitSetup() {
        val selectedPortal = currentSelectedPortal() ?: run {
            Toast.makeText(this, R.string.toast_missing_form_fields, Toast.LENGTH_LONG).show()
            return
        }
        val username = binding.etUsername.text?.toString().orEmpty().trim()
        val password = binding.etPassword.text?.toString().orEmpty()

        if (!hasRequiredPermissions()) {
            permissionsPrompt = getString(R.string.ui_permissions_needed_before_login)
            showPage(Page.PERMISSIONS)
            refreshUi()
            return
        }

        if (username.isBlank() || password.isBlank()) {
            Toast.makeText(this, R.string.toast_missing_form_fields, Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch {
            setBusy(true)
            try {
                val fcmToken = fetchFcmToken()
                val currentSettings = settingsStore.load()
                val backendUrl = binding.etBackendUrl.text?.toString().orEmpty().trim()
                    .ifBlank { currentSettings.backendBaseUrl }
                val setupSecret = binding.etSetupSecret.text?.toString().orEmpty().trim()
                val deviceLabel = binding.etDeviceLabel.text?.toString().orEmpty().trim()
                    .ifBlank { currentSettings.deviceLabel }

                settingsStore.saveSetupFields(
                    backendBaseUrl = BackendApi.normalizeBaseUrl(backendUrl),
                    loginUrl = selectedPortal.loginUrl,
                    username = username,
                    setupSecret = setupSecret,
                    deviceLabel = deviceLabel,
                    selectedPortalId = selectedPortal.portalId,
                    selectedPortalName = selectedPortal.name,
                    selectedPortalLogoUrl = selectedPortal.logoUrl,
                )
                settingsStore.saveFcmToken(fcmToken)
                refreshUi()

                val response = backendApi.submitSetup(
                    backendBaseUrl = backendUrl,
                    loginUrl = selectedPortal.loginUrl,
                    portalId = selectedPortal.portalId,
                    username = username,
                    password = password,
                    fcmToken = fcmToken,
                    deviceLabel = deviceLabel,
                    setupSecret = setupSecret,
                    apiToken = currentSettings.apiToken,
                )

                response.apiToken?.let(settingsStore::saveApiToken)
                settingsStore.saveStatus(response.status)
                settingsStore.savePortalCatalog(
                    MobileConfig(
                        publicBaseUrl = response.status.publicBaseUrl,
                        defaultPortalId = response.status.portalId,
                        portals = response.status.portals.ifEmpty { portalOptions },
                    ),
                )
                settingsStore.saveLastMessage(response.message)
                backendAvailable = true
                binding.etPassword.setText("")
                permissionsPrompt = null
                showLoginFields = false
                Toast.makeText(this@MainActivity, response.message, Toast.LENGTH_LONG).show()
                refreshUi()
                showPage(Page.STATUS)
                syncStatusRealtimeState(forceReconnect = true)
            } catch (exception: Exception) {
                settingsStore.saveLastMessage(
                    getString(R.string.toast_setup_failed),
                    exception.message.orEmpty(),
                )
                refreshUi()
                Toast.makeText(
                    this@MainActivity,
                    exception.message ?: getString(R.string.toast_setup_failed),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                setBusy(false)
            }
        }
    }

    private fun saveAdvancedSettings(showToast: Boolean) {
        val currentSettings = settingsStore.load()
        val selectedPortal = currentSelectedPortal(currentSettings)
        settingsStore.saveSetupFields(
            backendBaseUrl = BackendApi.normalizeBaseUrl(
                binding.etBackendUrl.text?.toString().orEmpty().trim().ifBlank { currentSettings.backendBaseUrl },
            ),
            loginUrl = selectedPortal?.loginUrl ?: currentSettings.loginUrl,
            username = binding.etUsername.text?.toString().orEmpty().trim().ifBlank { currentSettings.username },
            setupSecret = binding.etSetupSecret.text?.toString().orEmpty().trim(),
            deviceLabel = binding.etDeviceLabel.text?.toString().orEmpty().trim().ifBlank { currentSettings.deviceLabel },
            selectedPortalId = selectedPortal?.portalId ?: currentSettings.selectedPortalId,
            selectedPortalName = selectedPortal?.name ?: currentSettings.selectedPortalName,
            selectedPortalLogoUrl = selectedPortal?.logoUrl ?: currentSettings.selectedPortalLogoUrl,
        )
        populateForm(settingsStore.load())
        refreshPortalConfig(showErrors = false)
        refreshUi()
        if (showToast) {
            Toast.makeText(this, R.string.toast_settings_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshStatus(showErrors: Boolean) {
        val settings = settingsStore.load()
        if (settings.backendBaseUrl.isBlank() || settings.apiToken.isBlank()) {
            if (showErrors) {
                Toast.makeText(this, R.string.toast_status_requires_login, Toast.LENGTH_LONG).show()
                showPage(Page.LOGIN)
            }
            refreshUi()
            return
        }

        lifecycleScope.launch {
            try {
                val status = backendApi.fetchStatus(
                    backendBaseUrl = settings.backendBaseUrl,
                    apiToken = settings.apiToken,
                )
                settingsStore.saveStatus(status)
                settingsStore.savePortalCatalog(
                    MobileConfig(
                        publicBaseUrl = status.publicBaseUrl,
                        defaultPortalId = status.portalId,
                        portals = status.portals.ifEmpty { portalOptions },
                    ),
                )
                settingsStore.saveLastMessage(status.sync.lastMessage, status.sync.lastError)
                backendAvailable = true
                refreshUi()
            } catch (exception: Exception) {
                if (shouldClearPairingAfterFailure(exception.message)) {
                    invalidatePairingFromBackend()
                    return@launch
                }
                backendAvailable = false
                refreshUi()
                if (showErrors) {
                    Toast.makeText(
                        this@MainActivity,
                        exception.message ?: getString(R.string.toast_status_refresh_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun unpairDevice() {
        AlertDialog.Builder(this)
            .setTitle("Ontkoppelen?")
            .setMessage("Weet je zeker dat je dit apparaat wilt ontkoppelen? De koppeling wordt ook op de backend verwijderd.")
            .setPositiveButton("Ja, ontkoppelen") { _, _ ->
                lifecycleScope.launch {
                    setBusy(true)
                    val settings = settingsStore.load()
                    try {
                        if (settings.backendBaseUrl.isNotBlank() && settings.apiToken.isNotBlank()) {
                            backendApi.removeDevice(settings.backendBaseUrl, settings.apiToken)
                        }
                        settingsStore.clearPairing()
                        backendAvailable = null
                        showLoginFields = true
                        permissionsPrompt = null
                        stopStatusRealtimeState()
                        refreshUi()
                        showPage(Page.LOGIN)
                        Toast.makeText(this@MainActivity, R.string.toast_unpair_success, Toast.LENGTH_LONG).show()
                    } catch (exception: Exception) {
                        if (shouldClearPairingAfterFailure(exception.message)) {
                            settingsStore.clearPairing()
                            backendAvailable = null
                            showLoginFields = true
                            permissionsPrompt = null
                            stopStatusRealtimeState()
                            refreshUi()
                            showPage(Page.LOGIN)
                            Toast.makeText(this@MainActivity, R.string.toast_unpair_success, Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                exception.message ?: getString(R.string.toast_unpair_failed),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    } finally {
                        setBusy(false)
                    }
                }
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun populateForm(settings: AppSettings) {
        binding.etBackendUrl.setText(settings.backendBaseUrl)
        binding.etUsername.setText(settings.username)
        binding.etSetupSecret.setText(settings.setupSecret)
        binding.etDeviceLabel.setText(settings.deviceLabel)
        renderPortalSelection(currentSelectedPortal(settings))
    }

    private fun renderPortalSelection(portal: PortalOption?) {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            portalOptions.map { it.name },
        )
        binding.etPortalSelector.setAdapter(adapter)
        binding.etPortalSelector.setText(portal?.name.orEmpty(), false)
        binding.ivPortalLogo.isVisible = !portal?.logoUrl.isNullOrBlank()
        if (portal?.logoUrl.isNullOrBlank()) {
            binding.ivPortalLogo.setImageDrawable(null)
        } else {
            binding.ivPortalLogo.load(portal?.logoUrl)
        }
    }

    private fun applyPortalSelection(portal: PortalOption, persist: Boolean) {
        selectedPortalId = portal.portalId
        if (persist) {
            settingsStore.saveSelectedPortal(portal)
        }
        renderPortalSelection(portal)
    }

    private fun currentSelectedPortal(settings: AppSettings = settingsStore.load()): PortalOption? {
        val effectivePortals = if (portalOptions.isNotEmpty()) portalOptions else settingsStore.availablePortals(settings)
        return effectivePortals.firstOrNull { it.portalId == selectedPortalId }
            ?: effectivePortals.firstOrNull { it.portalId == settings.selectedPortalId }
            ?: effectivePortals.firstOrNull()
    }

    private fun syncStatusRealtimeState(forceReconnect: Boolean = false) {
        val settings = settingsStore.load()
        val canCheckBackend = settings.backendBaseUrl.isNotBlank() &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

        if (!canCheckBackend) {
            stopStatusRealtimeState()
            refreshUi()
            return
        }

        startHealthChecks(settings.backendBaseUrl)
        val shouldRunSocket = currentPage == Page.STATUS && settings.apiToken.isNotBlank()
        allowSocketReconnect = shouldRunSocket
        if (!shouldRunSocket) {
            stopStatusSocket()
            refreshUi()
            return
        }

        if (forceReconnect) {
            stopStatusSocket()
        }
        if (statusSocket == null) {
            startStatusSocket(settings.backendBaseUrl, settings.apiToken)
        }
    }

    private fun stopStatusRealtimeState() {
        allowSocketReconnect = false
        stopHealthChecks()
        stopStatusSocket()
    }

    private fun startHealthChecks(backendBaseUrl: String) {
        val normalizedBaseUrl = BackendApi.normalizeBaseUrl(backendBaseUrl)
        if (healthCheckJob != null && healthCheckBaseUrl == normalizedBaseUrl) {
            return
        }

        stopHealthChecks()
        healthCheckBaseUrl = normalizedBaseUrl
        backendAvailable = null
        refreshUi()

        healthCheckJob = lifecycleScope.launch {
            while (isActive && healthCheckBaseUrl == normalizedBaseUrl) {
                backendAvailable = try {
                    backendApi.checkHealth(normalizedBaseUrl)
                } catch (_: Exception) {
                    false
                }
                refreshUi()
                delay(if (backendAvailable == true) 15_000L else 5_000L)
            }
        }
    }

    private fun stopHealthChecks() {
        healthCheckJob?.cancel()
        healthCheckJob = null
        healthCheckBaseUrl = ""
    }

    private fun startStatusSocket(backendBaseUrl: String, apiToken: String) {
        reconnectJob?.cancel()
        reconnectJob = null
        socketConnected = false
        refreshUi()
        var expectedSocket: WebSocket? = null
        val socket = backendApi.openStatusWebSocket(
            backendBaseUrl = backendBaseUrl,
            apiToken = apiToken,
            onOpen = {
                runOnUiThread {
                    if (statusSocket !== expectedSocket) {
                        return@runOnUiThread
                    }
                    socketConnected = true
                    backendAvailable = true
                    refreshUi()
                }
            },
            onStatus = { status ->
                runOnUiThread {
                    if (statusSocket !== expectedSocket) {
                        return@runOnUiThread
                    }
                    handleLiveStatus(status)
                }
            },
            onClosed = { _ ->
                runOnUiThread {
                    if (statusSocket !== expectedSocket) {
                        return@runOnUiThread
                    }
                    socketConnected = false
                    statusSocket = null
                    refreshUi()
                    scheduleStatusSocketReconnect()
                }
            },
            onFailure = { message ->
                runOnUiThread {
                    if (statusSocket !== expectedSocket) {
                        return@runOnUiThread
                    }
                    if (shouldClearPairingAfterFailure(message)) {
                        invalidatePairingFromBackend()
                        return@runOnUiThread
                    }
                    socketConnected = false
                    statusSocket = null
                    backendAvailable = false
                    settingsStore.saveLastMessage(
                        settingsStore.load().lastMessage.ifBlank { getString(R.string.toast_status_refresh_failed) },
                        message,
                    )
                    refreshUi()
                    scheduleStatusSocketReconnect()
                }
            },
        )
        expectedSocket = socket
        statusSocket = socket
    }

    private fun stopStatusSocket() {
        reconnectJob?.cancel()
        reconnectJob = null
        val socket = statusSocket
        statusSocket = null
        socket?.close(1000, "closing")
        socketConnected = false
    }

    private fun scheduleStatusSocketReconnect() {
        if (!allowSocketReconnect || reconnectJob != null) {
            return
        }

        reconnectJob = lifecycleScope.launch {
            delay(2_000L)
            reconnectJob = null
            if (allowSocketReconnect) {
                syncStatusRealtimeState(forceReconnect = true)
            }
        }
    }

    private fun handleLiveStatus(status: BackendStatus) {
        settingsStore.saveStatus(status)
        settingsStore.savePortalCatalog(
            MobileConfig(
                publicBaseUrl = status.publicBaseUrl,
                defaultPortalId = status.portalId,
                portals = status.portals.ifEmpty { portalOptions },
            ),
        )
        if (status.portals.isNotEmpty()) {
            portalOptions = status.portals
        }
        if (status.portalId.isNotBlank()) {
            val selectedPortal = status.portals.firstOrNull { it.portalId == status.portalId }
                ?: currentSelectedPortal()
            if (selectedPortal != null) {
                applyPortalSelection(selectedPortal, persist = true)
            }
        }
        backendAvailable = true
        refreshUi()
    }

    private fun invalidatePairingFromBackend() {
        settingsStore.clearPairing()
        backendAvailable = false
        showLoginFields = true
        permissionsPrompt = null
        stopStatusRealtimeState()
        refreshUi()
        showPage(Page.LOGIN)
        Toast.makeText(this, R.string.toast_pairing_invalidated, Toast.LENGTH_LONG).show()
    }

    private fun shouldClearPairingAfterFailure(message: String?): Boolean {
        val normalized = message.orEmpty().lowercase(Locale.ROOT)
        return normalized.contains("bestaat niet meer") ||
            normalized.contains("app-token") ||
            normalized.contains("401") ||
            normalized.contains("unauthorized") ||
            normalized.contains("ongeldig")
    }

    private suspend fun fetchFcmToken(): String = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (continuation.isActive) {
                    continuation.resume(token)
                }
            }
            .addOnFailureListener { error ->
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }
    }

    private fun setBusy(isBusy: Boolean) {
        binding.btnSaveSetup.isEnabled = !isBusy
        binding.btnSaveSettings.isEnabled = !isBusy
        binding.btnRefreshStatus.isEnabled = !isBusy
        binding.btnRequestPermissions.isEnabled = !isBusy
        binding.btnCopyToken.isEnabled = !isBusy
        binding.btnUnpairDevice.isEnabled = !isBusy
        binding.etPortalSelector.isEnabled = !isBusy
    }

    private fun handleNavigation(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_login -> showPage(Page.LOGIN)
            R.id.nav_status -> showPage(Page.STATUS)
            R.id.nav_permissions -> showPage(Page.PERMISSIONS)
            R.id.nav_settings -> showPage(Page.SETTINGS)
            R.id.nav_about -> showPage(Page.ABOUT)
            else -> return false
        }
        return true
    }

    private fun showPage(page: Page, closeDrawer: Boolean = true) {
        currentPage = page
        binding.pageLogin.isVisible = page == Page.LOGIN
        binding.pageStatus.isVisible = page == Page.STATUS
        binding.pagePermissions.isVisible = page == Page.PERMISSIONS
        binding.pageSettings.isVisible = page == Page.SETTINGS
        binding.pageAbout.isVisible = page == Page.ABOUT
        binding.topAppBar.title = getString(page.titleRes)
        binding.navView.setCheckedItem(page.menuItemId)
        if (closeDrawer) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
        syncStatusRealtimeState(forceReconnect = page == Page.STATUS)
        refreshUi()
    }

    private fun hasRequiredPermissions(): Boolean = requiredPermissions().all(::hasPermission)

    private fun requiredPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions
    }

    private fun appVersionName(): String {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        return packageInfo.versionName ?: "-"
    }

    private fun formatTimestampForDisplay(value: String): String {
        if (value.isBlank()) {
            return "-"
        }

        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        )

        for (pattern in patterns) {
            try {
                val parser = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val parsed = parser.parse(value) ?: continue
                return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(parsed)
            } catch (_: Exception) {
                // Try the next timestamp shape before falling back to the raw backend value.
            }
        }

        return value
    }

    private fun mapStatusCode(statusCode: String): String {
        return when (statusCode) {
            "running" -> getString(R.string.ui_status_running)
            "success" -> getString(R.string.ui_status_success)
            "error" -> getString(R.string.ui_status_error)
            else -> getString(R.string.ui_status_idle)
        }
    }

    private fun mapPhase(phase: String): String {
        return when (phase) {
            "starting" -> getString(R.string.ui_phase_starting)
            "waiting_for_sms" -> getString(R.string.ui_phase_waiting_for_sms)
            "sms_received" -> getString(R.string.ui_phase_sms_received)
            "ready" -> getString(R.string.ui_phase_ready)
            "error" -> getString(R.string.ui_phase_error)
            else -> getString(R.string.ui_phase_idle)
        }
    }

    private fun mask(value: String): String {
        if (value.length <= 4) {
            return value.replace(Regex("."), "*")
        }
        return value.take(2) + "*".repeat(value.length - 4) + value.takeLast(2)
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
