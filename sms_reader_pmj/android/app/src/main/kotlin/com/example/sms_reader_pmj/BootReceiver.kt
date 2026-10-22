package com.example.sms_reader_pmj

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // ✅ Start foreground service to keep SMS receiver alive
            val serviceIntent = Intent(context, SmsForegroundService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}