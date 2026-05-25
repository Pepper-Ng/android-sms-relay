package com.hermans.onssmsrelay.ui

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import com.hermans.onssmsrelay.R
import com.hermans.onssmsrelay.data.AppSettings
import com.hermans.onssmsrelay.data.AppSettingsStore
import com.hermans.onssmsrelay.data.BackendApi
import com.hermans.onssmsrelay.databinding.ActivityMainBinding
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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
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
        showLoginFields = initialSettings.apiToken.isBlank()
        populateForm(initialSettings)
        binding.tvInstructions.text = getString(R.string.ui_about_body, appVersionName())

        binding.topAppBar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        binding.navView.setNavigationItemSelectedListener { item ->
            handleNavigation(item)
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
        }

        binding.btnSaveSettings.setOnClickListener {
            saveAdvancedSettings(showToast = true)
        }

        binding.btnRefreshStatus.setOnClickListener {
            refreshStatus(showErrors = true)
        }

        showPage(if (initialSettings.apiToken.isNotBlank()) Page.STATUS else Page.LOGIN, closeDrawer = false)
        refreshUi()
        refreshStatus(showErrors = false)
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
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
        val isLoggedIn = hasSavedLogin && settings.statusCode == "success" && settings.currentPhase == "ready"
        if (!hasSavedLogin) {
            showLoginFields = true
        }

        binding.loginStateCard.isVisible = hasSavedLogin && !showLoginFields
        binding.loginFormContainer.isVisible = !hasSavedLogin || showLoginFields
        binding.btnSaveSetup.text = getString(if (hasSavedLogin) R.string.ui_btn_relogin else R.string.ui_btn_save_setup)
        binding.tvLoginStateTitle.text = getString(if (isLoggedIn) R.string.ui_login_state_logged_in else R.string.ui_login_state_paired)
        binding.tvLoginStateBody.text = if (isLoggedIn) {
            getString(R.string.ui_login_state_logged_in_body, settings.lastSuccessAt.ifBlank { "-" })
        } else {
            getString(R.string.ui_login_state_paired_body, settings.lastMessage.ifBlank { "-" })
        }

        binding.tvSavedConfiguration.text = getString(
            R.string.ui_saved_configuration_value,
            settings.backendBaseUrl,
            settings.loginUrl,
            mask(settings.username),
            if (settings.apiToken.isNotBlank()) getString(R.string.ui_yes) else getString(R.string.ui_no),
        )
        binding.tvBackendStatus.text = getString(
            R.string.ui_backend_status_value,
            mapStatusCode(settings.statusCode),
            mapPhase(settings.currentPhase),
            settings.lastMessage.ifBlank { "-" },
            settings.lastError.ifBlank { "-" },
            settings.lastSuccessAt.ifBlank { "-" },
            if (settings.fcmConfigured) getString(R.string.ui_yes) else getString(R.string.ui_no),
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

    private fun submitSetup() {
        val loginUrl = binding.etLoginUrl.text?.toString().orEmpty().trim()
        val username = binding.etUsername.text?.toString().orEmpty().trim()
        val password = binding.etPassword.text?.toString().orEmpty()

        if (!hasRequiredPermissions()) {
            permissionsPrompt = getString(R.string.ui_permissions_needed_before_login)
            showPage(Page.PERMISSIONS)
            refreshUi()
            return
        }

        if (loginUrl.isBlank() || username.isBlank() || password.isBlank()) {
            Toast.makeText(this, R.string.toast_missing_form_fields, Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch {
            setBusy(true)
            try {
                // The app fetches the current FCM token on demand so the backend always stores a fresh callback target.
                val fcmToken = fetchFcmToken()
                val currentSettings = settingsStore.load()
                val backendUrl = binding.etBackendUrl.text?.toString().orEmpty().trim()
                    .ifBlank { currentSettings.backendBaseUrl }
                val setupSecret = binding.etSetupSecret.text?.toString().orEmpty().trim()
                val deviceLabel = binding.etDeviceLabel.text?.toString().orEmpty().trim()
                    .ifBlank { currentSettings.deviceLabel }
                settingsStore.saveSetupFields(
                    backendBaseUrl = BackendApi.normalizeBaseUrl(backendUrl),
                    loginUrl = loginUrl,
                    username = username,
                    setupSecret = setupSecret,
                    deviceLabel = deviceLabel,
                )
                settingsStore.saveFcmToken(fcmToken)
                refreshUi()
                val response = backendApi.submitSetup(
                    backendBaseUrl = backendUrl,
                    loginUrl = loginUrl,
                    username = username,
                    password = password,
                    fcmToken = fcmToken,
                    deviceLabel = deviceLabel,
                    setupSecret = setupSecret,
                    apiToken = currentSettings.apiToken,
                )

                response.apiToken?.let(settingsStore::saveApiToken)
                settingsStore.saveStatus(response.status)
                settingsStore.saveLastMessage(response.message)
                binding.etPassword.setText("")
                permissionsPrompt = null
                showLoginFields = false
                Toast.makeText(this@MainActivity, response.message, Toast.LENGTH_LONG).show()
                refreshUi()
                showPage(Page.STATUS)
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
        settingsStore.saveSetupFields(
            backendBaseUrl = BackendApi.normalizeBaseUrl(
                binding.etBackendUrl.text?.toString().orEmpty().trim().ifBlank { currentSettings.backendBaseUrl },
            ),
            loginUrl = binding.etLoginUrl.text?.toString().orEmpty().trim().ifBlank { currentSettings.loginUrl },
            username = binding.etUsername.text?.toString().orEmpty().trim().ifBlank { currentSettings.username },
            setupSecret = binding.etSetupSecret.text?.toString().orEmpty().trim(),
            deviceLabel = binding.etDeviceLabel.text?.toString().orEmpty().trim().ifBlank { currentSettings.deviceLabel },
        )
        populateForm(settingsStore.load())
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
                // The latest backend state is reloaded explicitly because login completion happens remotely.
                val status = backendApi.fetchStatus(
                    backendBaseUrl = settings.backendBaseUrl,
                    apiToken = settings.apiToken,
                )
                settingsStore.saveStatus(status)
                settingsStore.saveLastMessage(status.sync.lastMessage, status.sync.lastError)
                refreshUi()
            } catch (exception: Exception) {
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

    private fun populateForm(settings: AppSettings) {
        binding.etBackendUrl.setText(settings.backendBaseUrl)
        binding.etLoginUrl.setText(settings.loginUrl)
        binding.etUsername.setText(settings.username)
        binding.etSetupSecret.setText(settings.setupSecret)
        binding.etDeviceLabel.setText(settings.deviceLabel)
    }

    private suspend fun fetchFcmToken(): String = suspendCancellableCoroutine { continuation ->
        // Firebase still owns token refreshes, so the UI bridges the callback API into coroutines here.
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

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}
