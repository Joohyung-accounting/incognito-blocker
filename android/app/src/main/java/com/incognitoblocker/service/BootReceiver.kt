package com.incognitoblocker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the watchdog back after a reboot or an app update.
 *
 * The accessibility service itself is restored by the system, but the
 * foreground service that shows the status notification is not.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED ->
                GuardForegroundService.start(context)
        }
    }
}
