package com.example.hddshare

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                try {
                    context.startForegroundService(Intent(context, ShareService::class.java))
                } catch (e: Exception) {
                    // Android 12+ arka plan kısıtlaması vb.
                    Log.e("HDDShare", "Servis başlatılamadı", e)
                }
        }
    }
}
