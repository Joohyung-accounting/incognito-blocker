package com.incognitoblocker.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.incognitoblocker.R

/**
 * Full-screen block overlay.
 *
 * Adding the view from an AccessibilityService context with
 * TYPE_ACCESSIBILITY_OVERLAY needs no "draw over other apps" permission at all.
 * If that is refused it falls back to TYPE_APPLICATION_OVERLAY, which does need
 * SYSTEM_ALERT_WINDOW — so the fallback is only attempted when the permission is
 * actually held, otherwise it would just fail silently a second time.
 */
class BlockOverlay(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private var container: View? = null
    private var timer: CountDownTimer? = null

    val isShowing: Boolean get() = container != null

    fun show(browserLabel: String, holdSeconds: Int = 6) {
        handler.post { showInternal(browserLabel, holdSeconds) }
    }

    fun dismiss() {
        handler.post { dismissInternal() }
    }

    // ------------------------------------------------------------------

    private fun showInternal(browserLabel: String, holdSeconds: Int) {
        if (container != null) return

        val wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val view = buildView(browserLabel)
        if (runCatching { wm.addView(view, buildParams()) }.isSuccess) {
            container = view
            startCountdown(view, holdSeconds)
            return
        }

        // Accessibility overlay refused. The fallback needs a real permission.
        if (!canDrawOverlays(service)) {
            Log.w(TAG, "Cannot show overlay - no draw-over-other-apps permission")
            return
        }

        // The first view may have been partially attached, so build a fresh one.
        val retryView = buildView(browserLabel)
        if (!runCatching { wm.addView(retryView, buildParams(forceApplicationOverlay = true)) }
                .isSuccess
        ) {
            Log.w(TAG, "Overlay fallback failed as well")
            return
        }
        container = retryView
        startCountdown(retryView, holdSeconds)
    }

    private fun dismissInternal() {
        timer?.cancel()
        timer = null
        val view = container ?: return
        container = null
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        runCatching { wm.removeView(view) }
    }

    private fun startCountdown(view: View, holdSeconds: Int) {
        val counter = view.findViewWithTag<TextView>(TAG_COUNTDOWN) ?: return
        timer?.cancel()
        timer = object : CountDownTimer(holdSeconds * 1000L, 1000L) {
            override fun onTick(msLeft: Long) {
                counter.text = service.getString(
                    R.string.overlay_countdown,
                    (msLeft / 1000).toInt() + 1
                )
            }

            override fun onFinish() = dismissInternal()
        }.also { it.start() }
    }

    private fun buildParams(forceApplicationOverlay: Boolean = false): WindowManager.LayoutParams {
        val type = if (forceApplicationOverlay) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    private fun buildView(browserLabel: String): View {
        val ctx = service
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = object : LinearLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                // Swallow Back so the overlay cannot simply be dismissed.
                if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0B1120"))
            setPadding(dp(32), dp(32), dp(32), dp(32))
            isClickable = true
            isFocusableInTouchMode = true
        }

        root.addView(TextView(ctx).apply {
            text = "🛡"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 56f)
            gravity = Gravity.CENTER
        })

        root.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.overlay_title)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(8))
        })

        root.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.overlay_body, browserLabel)
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
        })

        root.addView(TextView(ctx).apply {
            tag = TAG_COUNTDOWN
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, 0)
        })

        root.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.overlay_hint)
            setTextColor(Color.parseColor("#475569"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })

        return root
    }

    companion object {
        private const val TAG = "IncognitoGuardOverlay"
        private const val TAG_COUNTDOWN = "countdown"

        /** Whether the TYPE_APPLICATION_OVERLAY fallback is usable. */
        fun canDrawOverlays(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)
    }
}
