package com.incognitoblocker.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.incognitoblocker.R
import com.incognitoblocker.core.BrowserRules
import com.incognitoblocker.core.EventLog
import com.incognitoblocker.core.IncognitoDetector
import com.incognitoblocker.core.Prefs

/**
 * Second detection path — watching notifications.
 *
 * Chromium browsers post a persistent notification for as long as private tabs
 * are open. Once a page has loaded the on-screen signals get weak, so this is
 * the most reliable indication that a session is still running.
 *
 * Entirely optional: without notification access it simply does nothing and the
 * accessibility-based blocking is unaffected.
 *
 * ── What this deliberately does NOT do ─────────────────────────────────
 *  · It does not cancel notifications.
 *    Cancelling the incognito notification does not end the session, so there
 *    is no upside — while a misfire could delete something the user cares
 *    about, such as a download notification.
 *  · It does not store notification contents, only which browser it came from.
 */
class NotificationGuardService : NotificationListenerService() {

    /** De-duplication so a repeatedly re-posted notification is counted once. */
    private var lastKey: String? = null
    private var lastAt = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!Prefs.isProtectionEnabled(this)) return

        val rule = BrowserRules.ruleFor(sbn.packageName) ?: return
        if (rule.notificationSignals.isEmpty()) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        if (!IncognitoDetector.matchesNotification(rule, title, text)) return

        // A persistent notification is re-posted every time the tab count
        // changes; count it once per cooldown window.
        val now = SystemClock.elapsedRealtime()
        if (sbn.key == lastKey && now - lastAt < DEDUPE_WINDOW_MS) return
        lastKey = sbn.key
        lastAt = now

        Log.i(TAG, "Private session seen via notification: ${rule.label}")
        EventLog.append(
            ctx = this,
            packageName = sbn.packageName,
            browserLabel = getString(R.string.log_label_notification, rule.label),
            signal = getString(R.string.log_signal_notification)
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // The notification disappears when the session ends; reset so the next
        // session is counted afresh.
        if (sbn?.key == lastKey) {
            lastKey = null
            lastAt = 0L
        }
    }

    companion object {
        private const val TAG = "IncognitoGuardNotif"
        private const val DEDUPE_WINDOW_MS = 60_000L

        /** Whether the user has granted notification access. */
        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(
                ctx.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val expected = ComponentName(ctx, NotificationGuardService::class.java)
            return flat.split(":").any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}
