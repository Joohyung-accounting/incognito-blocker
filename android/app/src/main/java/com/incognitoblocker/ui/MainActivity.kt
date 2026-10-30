package com.incognitoblocker.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.incognitoblocker.R
import com.incognitoblocker.core.BrowserRules
import com.incognitoblocker.core.DeviceOwner
import com.incognitoblocker.core.EventLog
import com.incognitoblocker.core.Prefs
import com.incognitoblocker.core.SafeDns
import com.incognitoblocker.service.GuardAccessibilityService
import com.incognitoblocker.service.GuardForegroundService
import com.incognitoblocker.service.NotificationGuardService

/**
 * Setup and status dashboard.
 *
 * Everything lives on one screen. A monitoring app only earns trust if what it
 * is doing is visible at a glance, so this screen shows the full state rather
 * than hiding it behind menus.
 */
class MainActivity : Activity() {

    private lateinit var content: LinearLayout

    /** Tracked so it can be dismissed in onPause — otherwise rotation leaks the Activity. */
    private var visibleDialog: Dialog? = null

    private val handler = Handler(Looper.getMainLooper())

    /**
     * The adult-site state the screen was last drawn with. Private DNS changes
     * underneath an open screen — a pause ends, Settings is edited, the
     * network drops — so it is re-checked while visible and the screen is
     * redrawn only when something actually differs.
     */
    private var shownDnsState: String? = null

    private val dnsRefresh = object : Runnable {
        override fun run() {
            if (dnsStateKey() != shownDnsState) render()
            handler.postDelayed(this, DNS_REFRESH_MS)
        }
    }

    private val bg = Color.parseColor("#0B1120")
    private val card = Color.parseColor("#151E31")
    private val fg = Color.WHITE
    private val muted = Color.parseColor("#94A3B8")
    private val accent = Color.parseColor("#38BDF8")
    private val good = Color.parseColor("#4ADE80")
    private val warn = Color.parseColor("#FBBF24")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply { setBackgroundColor(bg) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(40))
        }
        scroll.addView(content)
        setContentView(scroll)

        GuardForegroundService.start(this)
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        SafeDns.enforce(this)
        render()
        handler.postDelayed(dnsRefresh, DNS_REFRESH_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(dnsRefresh)
        // Dismiss any open dialog so a configuration change cannot leak this Activity.
        visibleDialog?.takeIf { it.isShowing }?.dismiss()
        visibleDialog = null
        super.onPause()
    }

    // ==================================================================
    // Screen
    // ==================================================================

    private fun render() {
        content.removeAllViews()
        shownDnsState = dnsStateKey()

        val a11yOn = GuardAccessibilityService.isEnabled(this)
        val isOwner = DeviceOwner.isDeviceOwner(this)
        val protectionOn = Prefs.isProtectionEnabled(this)
        val hasConsent = Prefs.hasConsent(this)

        // ---- Header ---------------------------------------------------
        content.addView(title(getString(R.string.app_name)))
        content.addView(body(getString(R.string.app_tagline)))

        val statusText = when {
            !hasConsent -> getString(R.string.status_setup_needed)
            !a11yOn -> getString(R.string.status_need_accessibility)
            !protectionOn -> getString(R.string.status_paused)
            isOwner -> getString(R.string.status_active_strong)
            else -> getString(R.string.status_active_basic)
        }
        val statusColor = when {
            !a11yOn || !hasConsent -> warn
            !protectionOn -> muted
            else -> good
        }
        content.addView(statusBanner(statusText, statusColor))

        // ---- Consent gate ---------------------------------------------
        if (!hasConsent) {
            content.addView(
                cardView(
                    getString(R.string.consent_title),
                    getString(R.string.consent_body),
                    getString(R.string.consent_agree) to {
                        Prefs.setConsent(this, true)
                        render()
                    }
                )
            )
            return
        }

        // ---- Adult-site filter ----------------------------------------
        renderDnsSection()

        // ---- Tier 1 ---------------------------------------------------
        content.addView(sectionTitle(getString(R.string.section_tier1)))

        content.addView(
            stepCard(
                done = a11yOn,
                titleText = getString(R.string.step_accessibility_title),
                desc = getString(R.string.step_accessibility_desc),
                actionLabel = getString(
                    if (a11yOn) R.string.step_accessibility_action_reopen
                    else R.string.step_accessibility_action_on
                ),
                action = { openAccessibilitySettings() }
            )
        )

        if (!a11yOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            content.addView(
                hintCard(
                    getString(R.string.hint_restricted_title),
                    getString(R.string.hint_restricted_body),
                    getString(R.string.hint_restricted_action) to { openAppInfo() }
                )
            )
        }

        content.addView(
            stepCard(
                done = Prefs.hasPin(this),
                titleText = getString(R.string.step_pin_title),
                desc = getString(R.string.step_pin_desc),
                actionLabel = getString(
                    if (Prefs.hasPin(this)) R.string.step_pin_action_change
                    else R.string.step_pin_action_create
                ),
                action = { showPinSetupDialog() }
            )
        )

        val adminActive = DeviceOwner.isAdminActive(this)
        content.addView(
            stepCard(
                done = adminActive,
                titleText = getString(R.string.step_admin_title),
                desc = getString(R.string.step_admin_desc),
                actionLabel = getString(
                    if (adminActive) R.string.step_admin_action_done
                    else R.string.step_admin_action_on
                ),
                action = { requestDeviceAdmin() },
                enabled = !adminActive
            )
        )

        content.addView(
            stepCard(
                done = NotificationGuardService.isEnabled(this),
                titleText = getString(R.string.step_notif_title),
                desc = getString(R.string.step_notif_desc),
                actionLabel = getString(R.string.step_notif_action),
                action = { openNotificationAccessSettings() }
            )
        )

        content.addView(
            stepCard(
                done = isIgnoringBatteryOptimizations(),
                titleText = getString(R.string.step_battery_title),
                desc = getString(R.string.step_battery_desc),
                actionLabel = getString(R.string.step_battery_action),
                action = { requestIgnoreBatteryOptimizations() }
            )
        )

        // ---- Tier 2 ---------------------------------------------------
        content.addView(sectionTitle(getString(R.string.section_tier2)))

        if (isOwner) {
            content.addView(
                cardView(
                    getString(R.string.strong_active_title),
                    getString(R.string.strong_active_body),
                    getString(R.string.strong_active_action) to { applyDeviceOwnerPolicies() }
                )
            )
            content.addView(
                dangerCard(
                    getString(R.string.strong_release_title),
                    getString(R.string.strong_release_body),
                    getString(R.string.strong_release_action) to {
                        confirmWithPin { releaseDeviceOwner() }
                    }
                )
            )
        } else {
            val cmd = DeviceOwner.adbCommand(this)
            content.addView(
                cardView(
                    getString(R.string.strong_why_title),
                    getString(R.string.strong_why_body, cmd),
                    getString(R.string.strong_why_action) to {
                        copyToClipboard(cmd)
                        toast(getString(R.string.toast_copied))
                    }
                )
            )
        }

        // ---- Watched browsers -----------------------------------------
        val installed = DeviceOwner.installedBrowsers(this)
        content.addView(sectionTitle(getString(R.string.section_browsers)))
        content.addView(
            cardView(
                getString(R.string.browsers_summary, installed.size, BrowserRules.allPackages.size),
                if (installed.isEmpty()) getString(R.string.browsers_none)
                else installed.joinToString("\n") { pkg ->
                    "· " + (BrowserRules.ruleFor(pkg)?.label ?: pkg) + "  ($pkg)"
                },
                null
            )
        )

        // ---- Block history --------------------------------------------
        val events = EventLog.readAll(this).take(15)
        content.addView(sectionTitle(getString(R.string.section_log, Prefs.blockCount(this))))
        content.addView(
            cardView(
                if (events.isEmpty()) getString(R.string.log_empty_title)
                else getString(R.string.log_recent, events.size),
                if (events.isEmpty()) getString(R.string.log_empty_body)
                else events.joinToString("\n\n") {
                    if (it.isDiagnostic) "${it.formattedTime()}  ${it.browserLabel}\n${it.signal}"
                    else "${it.formattedTime()}  ${it.browserLabel}"
                },
                if (events.isEmpty()) null else getString(R.string.log_clear) to {
                    confirmWithPin {
                        EventLog.clear(this)
                        toast(getString(R.string.toast_log_cleared))
                        render()
                    }
                }
            )
        )

        // ---- Diagnostics ----------------------------------------------
        val diagOn = Prefs.isDiagnosticMode(this)
        content.addView(sectionTitle(getString(R.string.section_troubleshoot)))
        content.addView(
            cardView(
                getString(if (diagOn) R.string.diag_title_on else R.string.diag_title_off),
                getString(R.string.diag_body),
                getString(if (diagOn) R.string.diag_action_off else R.string.diag_action_on) to {
                    Prefs.setDiagnosticMode(this, !diagOn)
                    render()
                }
            )
        )

        // ---- Pause / resume -------------------------------------------
        content.addView(
            dangerCard(
                getString(if (protectionOn) R.string.pause_title else R.string.resume_title),
                getString(if (protectionOn) R.string.pause_body else R.string.resume_body),
                getString(if (protectionOn) R.string.pause_action else R.string.resume_action) to {
                    if (protectionOn) {
                        confirmWithPin {
                            Prefs.setProtectionEnabled(this, false)
                            GuardForegroundService.start(this)
                            render()
                        }
                    } else {
                        Prefs.setProtectionEnabled(this, true)
                        GuardForegroundService.start(this)
                        render()
                    }
                }
            )
        )

        content.addView(body(getString(R.string.footer_limits)))
    }

    // ==================================================================
    // Adult-site filter
    // ==================================================================

    private fun dnsStateKey(): String = listOf(
        Prefs.isDnsWanted(this),
        SafeDns.control(this),
        SafeDns.configuredHost(this),
        SafeDns.pauseMinutesLeft(this),
        SafeDns.networkValidated(this)
    ).joinToString("|")

    private fun renderDnsSection() {
        content.addView(sectionTitle(getString(R.string.section_dns)))

        val control = SafeDns.control(this)
        if (control == SafeDns.Control.UNSUPPORTED) {
            content.addView(
                cardView(getString(R.string.dns_status_off), getString(R.string.dns_unsupported), null)
            )
            return
        }

        val wanted = Prefs.isDnsWanted(this) == true
        val chosen = Prefs.dnsProvider(this)
        val active = SafeDns.activeProvider(this)
        val paused = SafeDns.isPaused(this)
        val otherHost = SafeDns.configuredHost(this)?.takeIf { active == null }

        val heading = when {
            paused -> getString(R.string.dns_status_paused, SafeDns.pauseMinutesLeft(this))
            active != null -> getString(R.string.dns_status_on, getString(active.labelRes))
            wanted -> getString(R.string.dns_status_lost)
            otherHost != null -> getString(R.string.dns_status_other, otherHost)
            else -> getString(R.string.dns_status_off)
        }
        val headingColor = when {
            active != null -> good
            wanted || paused -> warn
            else -> fg
        }

        val shown = active ?: chosen
        val lines = mutableListOf<String>()
        if (active == null && !paused) lines += getString(R.string.dns_off_body)
        lines += getString(R.string.dns_filter_line, getString(shown.labelRes), getString(shown.descRes))
        lines += getString(
            when (control) {
                SafeDns.Control.DEVICE_OWNER -> R.string.dns_control_owner
                SafeDns.Control.SECURE_SETTINGS -> R.string.dns_control_secure
                else -> R.string.dns_control_manual
            }
        )
        // Strict Private DNS fails closed. Say so when it is the likely reason
        // the network has no internet, instead of leaving the user to guess.
        if (active != null && SafeDns.networkValidated(this) == false) {
            lines += getString(R.string.dns_network_broken)
        }

        val actions = mutableListOf<Pair<String, () -> Unit>>()
        when {
            paused -> actions += getString(R.string.dns_action_resume) to { enableDns(chosen) }
            active != null -> {
                actions += getString(R.string.dns_action_change) to {
                    confirmWithPin { pickDnsProvider() }
                }
                if (control != SafeDns.Control.MANUAL) {
                    actions += getString(R.string.dns_action_pause, DNS_PAUSE_MINUTES) to {
                        confirmWithPin { pauseDns() }
                    }
                }
                actions += getString(R.string.dns_action_off) to { confirmWithPin { disableDns() } }
            }
            wanted -> {
                actions += getString(R.string.dns_action_on) to { enableDns(chosen) }
                actions += getString(R.string.dns_action_off) to { confirmWithPin { disableDns() } }
            }
            else -> actions += getString(R.string.dns_action_on) to { pickDnsProvider() }
        }
        content.addView(buildCard(heading, lines.joinToString("\n"), actions, headingColor, card))

        if (control == SafeDns.Control.MANUAL) {
            val cmd = SafeDns.grantCommand(this)
            content.addView(
                cardView(
                    getString(R.string.dns_grant_title),
                    getString(R.string.dns_grant_body, cmd),
                    getString(R.string.dns_grant_action) to {
                        copyToClipboard(cmd)
                        toast(getString(R.string.toast_copied))
                    }
                )
            )
        }
    }

    private fun pickDnsProvider() {
        val providers = SafeDns.Provider.entries
        val labels = providers
            .map { getString(it.labelRes) + "\n" + getString(it.descRes) }
            .toTypedArray()
        showDialog(
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dns_pick_title))
                .setSingleChoiceItems(labels, providers.indexOf(Prefs.dnsProvider(this))) { d, which ->
                    d.dismiss()
                    enableDns(providers[which])
                }
                .setNegativeButton(getString(R.string.pin_cancel), null)
        )
    }

    private fun enableDns(provider: SafeDns.Provider) {
        SafeDns.enable(this, provider) { outcome ->
            when (outcome) {
                SafeDns.Outcome.DONE -> toast(getString(R.string.dns_toast_on))
                SafeDns.Outcome.MANUAL_STEP -> showManualDnsDialog(provider)
                SafeDns.Outcome.FAILED -> toast(getString(R.string.dns_toast_failed))
            }
            GuardForegroundService.start(this)
            render()
        }
    }

    private fun showManualDnsDialog(provider: SafeDns.Provider) {
        showDialog(
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dns_manual_title))
                .setMessage(getString(R.string.dns_manual_msg, provider.host))
                .setPositiveButton(getString(R.string.dns_manual_open)) { _, _ ->
                    copyToClipboard(provider.host)
                    toast(getString(R.string.toast_copied))
                    openNetworkSettings()
                }
                .setNegativeButton(getString(R.string.pin_cancel), null)
        )
    }

    private fun disableDns() {
        SafeDns.disable(this) { outcome ->
            when (outcome) {
                SafeDns.Outcome.DONE -> toast(getString(R.string.dns_toast_off))
                SafeDns.Outcome.MANUAL_STEP -> showDialog(
                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.dns_manual_title))
                        .setMessage(getString(R.string.dns_manual_off_msg))
                        .setPositiveButton(getString(R.string.dns_manual_open)) { _, _ ->
                            openNetworkSettings()
                        }
                        .setNegativeButton(getString(R.string.pin_cancel), null)
                )
                else -> toast(getString(R.string.dns_toast_failed))
            }
            GuardForegroundService.start(this)
            render()
        }
    }

    private fun pauseDns() {
        SafeDns.pause(this, DNS_PAUSE_MINUTES) { outcome ->
            if (outcome == SafeDns.Outcome.DONE) {
                toast(getString(R.string.dns_toast_paused, DNS_PAUSE_MINUTES))
            } else {
                toast(getString(R.string.dns_toast_failed))
            }
            GuardForegroundService.start(this)
            render()
        }
    }

    /** No public intent opens the Private DNS screen itself; this lands a tap or two away. */
    private fun openNetworkSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            .recoverCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            .onFailure { toast(getString(R.string.toast_cannot_open_settings)) }
    }

    // ==================================================================
    // Actions
    // ==================================================================

    private fun openAccessibilitySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure { toast(getString(R.string.toast_cannot_open_settings)) }
    }

    private fun openAppInfo() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        }.onFailure { toast(getString(R.string.toast_cannot_open_settings)) }
    }

    private fun openNotificationAccessSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            .onFailure { toast(getString(R.string.toast_not_supported)) }
    }

    private fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, DeviceOwner.admin(this@MainActivity))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.admin_explanation)
            )
        }
        runCatching { startActivity(intent) }
            .onFailure { toast(getString(R.string.toast_cannot_open_admin)) }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        // Send the user to the battery optimisation list rather than firing the
        // direct request intent — REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is a
        // Play-flagged permission and the list screen needs no permission at all.
        runCatching {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.onFailure { toast(getString(R.string.toast_cannot_open_settings)) }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
    }

    private fun applyDeviceOwnerPolicies() {
        val report = DeviceOwner.applyAll(
            ctx = this,
            suspendUnmanageable = true,
            lockdownDevice = false
        )
        val msg = buildString {
            appendLine(getString(R.string.apply_result_heading))
            appendLine()
            appendLine(getString(R.string.apply_result_managed, report.managedBrowsers.size))
            report.managedBrowsers.forEach { appendLine("  · $it") }
            appendLine()
            appendLine(getString(R.string.apply_result_hidden, report.suspendedBrowsers.size))
            report.suspendedBrowsers.forEach { appendLine("  · $it") }
            if (report.errors.isNotEmpty()) {
                appendLine()
                appendLine(getString(R.string.apply_result_errors))
                report.errors.forEach { appendLine("  · $it") }
            }
        }
        showDialog(
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.apply_result_title))
                .setMessage(msg)
                .setPositiveButton(getString(R.string.pin_ok)) { _, _ -> render() }
        )
    }

    private fun releaseDeviceOwner() {
        val ok = DeviceOwner.releaseEverything(this)
        toast(getString(if (ok) R.string.release_ok else R.string.release_fail))
        render()
    }

    // ---- PIN ---------------------------------------------------------

    private fun showPinSetupDialog() {
        val proceed = {
            val input = pinInput()
            showDialog(
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.pin_setup_title))
                    .setMessage(getString(R.string.pin_setup_msg))
                    .setView(input)
                    .setPositiveButton(getString(R.string.pin_save)) { _, _ ->
                        val pin = input.text.toString()
                        if (pin.length < MIN_PIN_LENGTH) {
                            toast(getString(R.string.pin_too_short))
                        } else {
                            Prefs.setPin(this, pin)
                            toast(getString(R.string.pin_saved))
                            render()
                        }
                    }
                    .setNegativeButton(getString(R.string.pin_cancel), null)
            )
        }
        if (Prefs.hasPin(this)) confirmWithPin { proceed() } else proceed()
    }

    /**
     * Runs an action that must be protected by the guardian PIN.
     *
     * When no PIN exists this must NOT fall through. Otherwise pausing
     * protection, clearing the history and releasing device-owner rights are
     * all one tap away for the person being monitored.
     */
    private fun confirmWithPin(onSuccess: () -> Unit) {
        if (!Prefs.hasPin(this)) {
            showDialog(
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.pin_required_title))
                    .setMessage(getString(R.string.pin_required_msg))
                    .setPositiveButton(getString(R.string.step_pin_action_create)) { _, _ ->
                        showPinSetupDialog()
                    }
                    .setNegativeButton(getString(R.string.pin_cancel), null)
            )
            return
        }
        val input = pinInput()
        showDialog(
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.pin_confirm_title))
                .setView(input)
                .setPositiveButton(getString(R.string.pin_ok)) { _, _ ->
                    if (Prefs.verifyPin(this, input.text.toString())) onSuccess()
                    else toast(getString(R.string.pin_wrong))
                }
                .setNegativeButton(getString(R.string.pin_cancel), null)
        )
    }

    private fun pinInput() = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        hint = getString(R.string.pin_hint)
        setPadding(dp(24), dp(16), dp(24), dp(16))
    }

    private fun showDialog(builder: AlertDialog.Builder) {
        visibleDialog?.takeIf { it.isShowing }?.dismiss()
        visibleDialog = builder.show()
    }

    // ==================================================================
    // View helpers
    // ==================================================================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(topMargin: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, dp(topMargin), 0, 0) }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        layoutParams = lp()
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        layoutParams = lp(4)
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(accent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        layoutParams = lp(28)
    }

    private fun statusBanner(text: String, color: Int) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(18), dp(16), dp(18))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(card)
            setStroke(dp(1), color)
        }
        layoutParams = lp(20)
    }

    private fun cardView(heading: String, text: String, action: Pair<String, () -> Unit>?): View =
        buildCard(heading, text, action, fg, card)

    private fun hintCard(heading: String, text: String, action: Pair<String, () -> Unit>?): View =
        buildCard(heading, text, action, warn, Color.parseColor("#211B0B"))

    private fun dangerCard(heading: String, text: String, action: Pair<String, () -> Unit>?): View =
        buildCard(heading, text, action, Color.parseColor("#FCA5A5"), Color.parseColor("#20131A"))

    private fun stepCard(
        done: Boolean,
        titleText: String,
        desc: String,
        actionLabel: String,
        action: () -> Unit,
        enabled: Boolean = true
    ): View = buildCard(
        heading = (if (done) "✓  " else "○  ") + titleText,
        text = desc,
        action = if (enabled) actionLabel to action else null,
        headingColor = if (done) good else fg,
        bgColor = card
    )

    private fun buildCard(
        heading: String,
        text: String,
        action: Pair<String, () -> Unit>?,
        headingColor: Int,
        bgColor: Int
    ): View = buildCard(heading, text, listOfNotNull(action), headingColor, bgColor)

    private fun buildCard(
        heading: String,
        text: String,
        actions: List<Pair<String, () -> Unit>>,
        headingColor: Int,
        bgColor: Int
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(bgColor)
            }
            layoutParams = lp(10)
        }

        box.addView(TextView(this).apply {
            this.text = heading
            setTextColor(headingColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        })

        if (text.isNotBlank()) {
            box.addView(TextView(this).apply {
                this.text = text
                setTextColor(muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, dp(8), 0, 0)
            })
        }

        actions.forEachIndexed { i, (label, handler) ->
            box.addView(Button(this).apply {
                this.text = label
                setTextColor(Color.parseColor("#0B1120"))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(accent)
                }
                setOnClickListener { handler() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dp(if (i == 0) 14 else 8), 0, 0) }
            })
        }

        return box
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("adb", text))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        const val MIN_PIN_LENGTH = 4
        const val DNS_PAUSE_MINUTES = 10
        const val DNS_REFRESH_MS = 5_000L
    }
}
