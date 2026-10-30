package com.incognitoblocker.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.incognitoblocker.core.SafeDns

/**
 * Ends an adult-site filter pause even if the watchdog is not running.
 *
 * The watchdog normally ends it on its next tick, but One UI kills background
 * services. Without this backstop a pause meant to last ten minutes would last
 * until the app was next opened — the one moment the filter is off. The alarm
 * is inexact (the emulator gave it a 7.5-minute window), so it is a backstop,
 * not the clock.
 */
class PauseEndReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        SafeDns.enforce(context)
        GuardForegroundService.start(context)
    }

    companion object {
        /** Fires through Doze and needs no exact-alarm permission. */
        fun schedule(ctx: Context, atMillis: Long) {
            val pi = PendingIntent.getBroadcast(
                ctx,
                0,
                Intent(ctx, PauseEndReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            runCatching {
                ctx.getSystemService(AlarmManager::class.java)
                    .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            }
        }
    }
}
