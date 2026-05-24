package nl.landvanhorne.smsrelay.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import nl.landvanhorne.smsrelay.service.RelayService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Start the RelayService automatically when the device reboots.
            Log.d("BootReceiver", "Device rebooted — starting RelayService")
            val serviceIntent = Intent(context, RelayService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
