package com.example.fivethreeonelifter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) RestTimer.stop(context)
            else RestTimer.reschedule(context)
            NotificationScheduler.scheduleAll(context)
        }
    }
}
