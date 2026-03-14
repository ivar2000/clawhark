package com.ettlinger.wearrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Auto-restarts recording after watch reboot if the user had recording enabled.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        AppLog.init(context)
        AuthManager.init(context)

        if (!AuthManager.isAuthenticated()) return

        val shouldRecord = context.getSharedPreferences(
            RecordingService.PREF_FILE, Context.MODE_PRIVATE
        ).getBoolean(RecordingService.PREF_SHOULD_RECORD, true)

        if (shouldRecord) {
            AppLog.i("Boot", "Reboot detected — restarting recording")
            val serviceIntent = Intent(context, RecordingService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
