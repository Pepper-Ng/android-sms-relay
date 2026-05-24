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
import com.google.firebase.messaging.FirebaseMessaging
import nl.landvanhorne.smsrelay.R
import nl.landvanhorne.smsrelay.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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

        // Request necessary permissions from the user.
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

        // Guide the user to disable battery optimization for the app.
        binding.btnBatteryOptimization.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            } else {
                Toast.makeText(this, R.string.ui_battery_not_needed, Toast.LENGTH_SHORT).show()
            }
        }

        // Copy the FCM token to the clipboard.
        binding.btnCopyToken.setOnClickListener {
            val token = binding.tvFcmToken.text.toString()
            if (token.isNotEmpty() && token != getString(R.string.ui_token_loading)) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("FCM token", token))
                Toast.makeText(this, R.string.ui_token_copied, Toast.LENGTH_SHORT).show()
            }
        }

        refreshUi()
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

        binding.tvSmsStatus.text = when {
            smsOk && notificationsOk -> getString(R.string.ui_all_permissions_granted)
            !smsOk && !notificationsOk -> getString(R.string.ui_permissions_missing)
            !smsOk -> getString(R.string.ui_sms_permission_missing)
            else -> getString(R.string.ui_notif_permission_missing)
        }

        // Retrieve the FCM token from SharedPreferences or request it again.
        val storedToken = getSharedPreferences("relay", MODE_PRIVATE)
            .getString("fcm_token", null)

        if (storedToken != null) {
            binding.tvFcmToken.text = storedToken
        } else {
            binding.tvFcmToken.text = getString(R.string.ui_token_loading)
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                binding.tvFcmToken.text = token
                getSharedPreferences("relay", MODE_PRIVATE)
                    .edit().putString("fcm_token", token).apply()
            }
        }

        // Update instructions text.
        binding.tvInstructions.text = getString(R.string.ui_instructions)
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}
