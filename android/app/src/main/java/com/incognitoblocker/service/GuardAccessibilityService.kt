package com.incognitoblocker.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.incognitoblocker.R
import com.incognitoblocker.core.BrowserRules
import com.incognitoblocker.core.EventLog
import com.incognitoblocker.core.IncognitoDetector
import com.incognitoblocker.core.Prefs

/**
 * The core detection service.
 *
 * Whenever a watched browser comes to the foreground the screen tree is read,
 * scored for private-mode signals, and — if it scores high enough — the window
 * is closed and a full-screen notice is shown.
 */
class GuardAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: BlockOverlay? = null

    /** Cooldown so that one situation does not fire repeatedly. */
    private var lastActionAt = 0L
    private var lastActionPackage: String? = null
    private var lastDiagnosticAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = BlockOverlay(this)

        // Set the config at runtime as well as in XML: some OEM ROMs ignore
        // parts of the XML declaration.
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            // FLAG_REPORT_VIEW_IDS is mandatory: without it
            // getViewIdResourceName() always returns null and every view-ID
            // based signal silently stops working.
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 200
            packageNames = BrowserRules.allPackages.toTypedArray()
        }

        GuardForegroundService.start(this)
        Log.i(TAG, "Guard connected - watching ${BrowserRules.allPackages.size} packages")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!Prefs.isProtectionEnabled(this)) return

        val pkg = event.packageName?.toString() ?: return
        val rule = BrowserRules.ruleFor(pkg) ?: return

        // elapsedRealtime is monotonic since boot, so moving the system clock
        // backwards cannot be used to disable enforcement. currentTimeMillis
        // would let anyone stop the guard just by changing the date.
        val now = SystemClock.elapsedRealtime()
        if (pkg == lastActionPackage && now - lastActionAt < ACTION_COOLDOWN_MS) return
        if (overlay?.isShowing == true) return

        // Diagnostic mode records what is readable regardless of the verdict,
        // because tuning the rules for a device requires seeing the real strings.
        if (Prefs.isDiagnosticMode(this) &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            now - lastDiagnosticAt > DIAGNOSTIC_COOLDOWN_MS
        ) {
            lastDiagnosticAt = now
            EventLog.append(
                ctx = this,
                packageName = pkg,
                browserLabel = getString(R.string.log_label_diagnostic, rule.label),
                signal = IncognitoDetector.sample(this, rootInActiveWindow),
                isDiagnostic = true
            )
        }

        val result = IncognitoDetector.detect(rootInActiveWindow, rule)
        if (!result.detected) return

        lastActionAt = now
        lastActionPackage = pkg
        enforce(rule, pkg, result)
    }

    override fun onInterrupt() {
        overlay?.dismiss()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        overlay?.dismiss()
        overlay = null
        return super.onUnbind(intent)
    }

    // ------------------------------------------------------------------

    private fun enforce(rule: BrowserRules.Rule, pkg: String, result: IncognitoDetector.Result) {
        val strict = Prefs.isStrictMode(this)

        // 1) Back out of the private window or tab.
        performGlobalAction(GLOBAL_ACTION_BACK)

        if (strict) {
            // 2) In strict mode push the browser itself to the background.
            handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 250)
        }

        // 3) Say plainly what happened. Nothing is blocked silently.
        handler.postDelayed({
            overlay?.show(rule.label, holdSeconds = if (strict) STRICT_HOLD_S else GENTLE_HOLD_S)
        }, if (strict) 400 else 150)

        Prefs.incrementBlockCount(this)
        EventLog.append(
            ctx = this,
            packageName = pkg,
            browserLabel = rule.label,
            signal = result.matchedSignals.joinToString(", ").ifBlank { "score=${result.score}" }
        )
        Log.i(TAG, "Blocked ${rule.label} ($pkg) via ${result.matchedSignals}")
    }

    companion object {
        private const val TAG = "IncognitoGuard"

        private const val STRICT_HOLD_S = 6
        private const val GENTLE_HOLD_S = 3

        /**
         * Must exceed how long the overlay stays up, otherwise enforcement
         * re-fires before the overlay closes and the two loop against each other.
         */
        private const val ACTION_COOLDOWN_MS = 10_000L
        private const val DIAGNOSTIC_COOLDOWN_MS = 1_500L

        /** Whether the user has enabled this service in Settings. */
        fun isEnabled(ctx: Context): Boolean {
            val component = ComponentName(ctx, GuardAccessibilityService::class.java)
            val expectedLong = component.flattenToString()
            val expectedShort = component.flattenToShortString()

            val enabled = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) {
                val entry = splitter.next()
                // Some ROMs store the short form instead of the long one.
                if (entry.equals(expectedLong, ignoreCase = true) ||
                    entry.equals(expectedShort, ignoreCase = true)
                ) return true
            }
            return false
        }
    }
}
