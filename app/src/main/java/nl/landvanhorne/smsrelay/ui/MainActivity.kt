package nl.landvanhorne.smsrelay.ui

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import nl.landvanhorne.smsrelay.R
import nl.landvanhorne.smsrelay.data.AppSettings
import nl.landvanhorne.smsrelay.data.AppSettingsStore
import nl.landvanhorne.smsrelay.data.BackendApi
import nl.landvanhorne.smsrelay.databinding.ActivityMainBinding
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsStore: AppSettingsStore
    private val backendApi = BackendApi()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            Toast.makeText(this, R.string.toast_permission_granted, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.toast_permission_denied, Toast.LENGTH_LONG).show()
        }
        refreshUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settingsStore = AppSettingsStore(this)

        populateForm(settingsStore.load())

        binding.btnRequestPermissions.setOnClickListener {
            val permissions = mutableListOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(permissions.toTypedArray())
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

        binding.btnRefreshStatus.setOnClickListener {
            refreshStatus(showErrors = true)
        }

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

        binding.tvPermissionsStatus.text = when {
            smsOk && notificationsOk -> getString(R.string.ui_all_permissions_granted)
            !smsOk && !notificationsOk -> getString(R.string.ui_permissions_missing)
            !smsOk -> getString(R.string.ui_sms_permission_missing)
            else -> getString(R.string.ui_notif_permission_missing)
        }

        val settings = settingsStore.load()
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
        binding.tvInstructions.text = getString(R.string.ui_instructions)
    }

    private fun submitSetup() {
        val backendUrl = binding.etBackendUrl.text?.toString().orEmpty().trim()
        val loginUrl = binding.etLoginUrl.text?.toString().orEmpty().trim()
        val username = binding.etUsername.text?.toString().orEmpty().trim()
        val password = binding.etPassword.text?.toString().orEmpty()
        val setupSecret = binding.etSetupSecret.text?.toString().orEmpty().trim()
        val deviceLabel = binding.etDeviceLabel.text?.toString().orEmpty().trim()

        if (backendUrl.isBlank() || loginUrl.isBlank() || username.isBlank() || password.isBlank()) {
            Toast.makeText(this, R.string.toast_missing_form_fields, Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch {
            setBusy(true)
            try {
                // The app fetches the current FCM token on demand so the backend always stores a fresh callback target.
                val fcmToken = fetchFcmToken()
                val currentSettings = settingsStore.load()
                val response = backendApi.submitSetup(
                    backendBaseUrl = backendUrl,
                    loginUrl = loginUrl,
                    username = username,
                    password = password,
                    fcmToken = fcmToken,
                    deviceLabel = deviceLabel.ifBlank { currentSettings.deviceLabel },
                    setupSecret = setupSecret,
                    apiToken = currentSettings.apiToken,
                )

                settingsStore.saveSetupFields(
                    backendBaseUrl = BackendApi.normalizeBaseUrl(backendUrl),
                    loginUrl = loginUrl,
                    username = username,
                    setupSecret = setupSecret,
                    deviceLabel = deviceLabel.ifBlank { currentSettings.deviceLabel },
                )
                settingsStore.saveFcmToken(fcmToken)
                response.apiToken?.let(settingsStore::saveApiToken)
                settingsStore.saveStatus(response.status)
                settingsStore.saveLastMessage(response.message)
                binding.etPassword.setText("")
                Toast.makeText(this@MainActivity, response.message, Toast.LENGTH_LONG).show()
                refreshUi()
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

    private fun refreshStatus(showErrors: Boolean) {
        val settings = settingsStore.load()
        if (settings.backendBaseUrl.isBlank() || settings.apiToken.isBlank()) {
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
        binding.btnRefreshStatus.isEnabled = !isBusy
        binding.btnRequestPermissions.isEnabled = !isBusy
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

