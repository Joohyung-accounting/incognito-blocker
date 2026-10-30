package com.incognitoblocker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.incognitoblocker.R
import com.incognitoblocker.core.DeviceOwner
import com.incognitoblocker.core.Prefs
import com.incognitoblocker.core.SafeDns
import com.incognitoblocker.ui.MainActivity

/**
 * The always-on watchdog.
 *
 * It exists for two reasons:
 *
 *  1) Transparency. The ongoing notification makes it impossible to be watched
 *     without knowing. That is a Google Play requirement for this category, and
 *     it is also simply the right way to build a tool like this — a monitoring
 *     app that hides itself is spyware.
 *
 *  2) Recovery. When the accessibility service is switched off it dies
 *     instantly and can no longer report anything itself, so something outside
 *     it has to notice and say so.
 *
 *  3) The adult-site filter. It writes Private DNS back when something changes
 *     it (once WRITE_SECURE_SETTINGS is granted) and ends a pause on time.
 */
class GuardForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    /** Last rendered notification content, so it is only re-posted when it changes. */
    private var lastRendered: String? = null

    private val healthCheck = object : Runnable {
        override fun run() {
            SafeDns.enforce(this@GuardForegroundService)
            refreshNotification()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    /**
     * Fires the moment anything — the Settings screen, adb, another app —
     * changes Private DNS, so the adult-site filter is written back at once
     * rather than at the next tick.
     */
    private val dnsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            SafeDns.enforce(this@GuardForegroundService)
            refreshNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startInForeground()
        if (SafeDns.isSupported()) {
            for (key in listOf(SafeDns.KEY_MODE, SafeDns.KEY_SPECIFIER)) {
                runCatching {
                    contentResolver.registerContentObserver(
                        Settings.Global.getUriFor(key), false, dnsObserver
                    )
                }
            }
        }
        handler.post(healthCheck)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(healthCheck)
        runCatching { contentResolver.unregisterContentObserver(dnsObserver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------

    private fun startInForeground() {
        val notification = buildNotification()
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.isSuccess

        if (started) {
            lastRendered = renderKey()
            return
        }

        // Android 12+ throws ForegroundServiceStartNotAllowedException when a
        // foreground service is started from the background without an
        // exemption. Falling back to a plain notification at least tells the
        // user that protection is not running, instead of failing silently.
        Log.w(TAG, "Could not start in foreground - posting a plain notification instead")
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        }
        stopSelf()
    }

    private fun refreshNotification() {
        val key = renderKey()
        if (key == lastRendered) return
        lastRendered = key
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    /**
     * Everything the notification text depends on. Comparing this rather than
     * just the health flag means the block count and the paused state actually
     * show up.
     */
    private fun renderKey(): String = listOf(
        GuardAccessibilityService.isEnabled(this),
        Prefs.isProtectionEnabled(this),
        Prefs.blockCount(this),
        DeviceOwner.isDeviceOwner(this),
        dnsLine()
    ).joinToString("|")

    /** The adult-site line of the notification, or null when the filter was never wanted. */
    private fun dnsLine(): String? {
        if (!SafeDns.isSupported()) return null
        val active = SafeDns.activeProvider(this)
        return when {
            SafeDns.isPaused(this) ->
                getString(R.string.notif_dns_paused, SafeDns.pauseMinutesLeft(this))
            active != null -> getString(R.string.notif_dns_on, getString(active.labelRes))
            Prefs.isDnsWanted(this) == true -> getString(R.string.notif_dns_off)
            else -> null
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val healthy = GuardAccessibilityService.isEnabled(this)
        val protectionOn = Prefs.isProtectionEnabled(this)
        val dnsFiltering = SafeDns.activeProvider(this) != null
        val tier = getString(
            if (DeviceOwner.isDeviceOwner(this)) R.string.tier_strong else R.string.tier_basic
        )

        // One line per guard, so the adult-site filter running on its own does
        // not read as "protection has stopped".
        val incognitoLine = when {
            !healthy -> getString(R.string.notif_stopped_text)
            !protectionOn -> getString(R.string.notif_paused_text)
            else -> getString(R.string.notif_active_text, Prefs.blockCount(this))
        }
        val title = when {
            (healthy && protectionOn) || dnsFiltering -> getString(R.string.notif_active_title, tier)
            !healthy -> getString(R.string.notif_stopped_title)
            else -> getString(R.string.notif_paused_title)
        }
        val text = listOfNotNull(incognitoLine, dnsLine()).joinToString("\n")

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val TAG = "IncognitoGuardFgs"
        private const val CHANNEL_ID = "guard_status"
        private const val NOTIFICATION_ID = 1001
        private const val CHECK_INTERVAL_MS = 15_000L

        fun start(ctx: Context) {
            val intent = Intent(ctx, GuardForegroundService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            }
        }
    }
}
