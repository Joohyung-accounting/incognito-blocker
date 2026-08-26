package com.incognitoblocker.core

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import com.incognitoblocker.R
import com.incognitoblocker.service.AdminReceiver

/**
 * Tier 2 — the hard block.
 *
 * Once the app is promoted to device owner it stops guessing from the screen
 * and writes policy straight into the OS, so incognito mode ceases to exist.
 *
 * Promotion (once, from a PC, right after a factory reset and before any
 * account is added):
 *
 *   adb shell dpm set-device-owner com.incognitoblocker/.service.AdminReceiver
 *
 * Note that a device owner cannot be removed from Settings. The only exit is
 * "remove all protection" inside this app, which calls [releaseEverything].
 */
object DeviceOwner {

    private const val TAG = "IncognitoGuardDO"

    /**
     * Browsers whose private mode can be switched off through managed
     * configuration. These are the only two that publish such a key.
     */
    private val MANAGED_BROWSERS = mapOf(
        // Chrome: IncognitoModeAvailability 1 = incognito disabled
        "com.android.chrome" to Pair("IncognitoModeAvailability", 1),
        // Edge: InPrivateModeAvailability 1 = InPrivate disabled
        "com.microsoft.emmx" to Pair("InPrivateModeAvailability", 1)
    )

    /**
     * Browsers with no managed configuration at all.
     *
     * Firefox for Android and Samsung Internet simply do not expose a key for
     * disabling private mode — Samsung's Knox BrowserPolicy covers cookies,
     * JavaScript and popups but nothing about secret mode. They can only be
     * hidden or suspended.
     */
    val UNMANAGEABLE_BROWSERS = listOf(
        "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix",
        "org.mozilla.focus", "org.mozilla.klar",
        "com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta",
        "com.duckduckgo.mobile.android",
        "com.opera.browser", "com.opera.gx", "com.opera.mini.native",
        "org.torproject.torbrowser",
        "com.brave.browser",
        "com.naver.whale",
        "com.kiwibrowser.browser",
        "com.vivaldi.browser"
    )

    fun admin(ctx: Context): ComponentName = ComponentName(ctx, AdminReceiver::class.java)

    fun dpm(ctx: Context): DevicePolicyManager =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isDeviceOwner(ctx: Context): Boolean =
        runCatching { dpm(ctx).isDeviceOwnerApp(ctx.packageName) }.getOrDefault(false)

    fun isAdminActive(ctx: Context): Boolean =
        runCatching { dpm(ctx).isAdminActive(admin(ctx)) }.getOrDefault(false)

    // ------------------------------------------------------------------
    // Applying policy
    // ------------------------------------------------------------------

    data class ApplyReport(
        val managedBrowsers: List<String> = emptyList(),
        val suspendedBrowsers: List<String> = emptyList(),
        val restrictionsApplied: List<String> = emptyList(),
        val selfProtected: Boolean = false,
        val errors: List<String> = emptyList()
    )

    /**
     * Applies every lock available to a device owner.
     *
     * @param suspendUnmanageable hide browsers that cannot be configured by
     *        policy. Firefox, Samsung Internet and friends disappear from the
     *        launcher.
     * @param lockdownDevice also block adb, safe mode and factory reset. Hard to
     *        undo, so it defaults to false and is never enabled from the UI.
     */
    fun applyAll(
        ctx: Context,
        suspendUnmanageable: Boolean,
        lockdownDevice: Boolean
    ): ApplyReport {
        if (!isDeviceOwner(ctx)) {
            return ApplyReport(errors = listOf(ctx.getString(R.string.do_not_owner)))
        }

        val dpm = dpm(ctx)
        val admin = admin(ctx)
        val errors = mutableListOf<String>()
        val managed = mutableListOf<String>()
        val suspended = mutableListOf<String>()
        val restrictions = mutableListOf<String>()

        // 1) Turn incognito off outright where a policy exists.
        for ((pkg, policy) in MANAGED_BROWSERS) {
            if (!isInstalled(ctx, pkg)) continue
            runCatching {
                val bundle = Bundle().apply { putInt(policy.first, policy.second) }
                dpm.setApplicationRestrictions(admin, pkg, bundle)
                managed.add("$pkg (${policy.first}=${policy.second})")
            }.onFailure { errors.add(ctx.getString(R.string.do_err_policy, pkg, it.message ?: "")) }
        }

        // 2) Hide the browsers policy cannot touch.
        //
        //    But never leave the device with no usable browser at all. Making
        //    the web unreachable is not the goal, and recovery requires opening
        //    this app — during which the user cannot even follow a link.
        if (suspendUnmanageable) {
            val willRemain = MANAGED_BROWSERS.keys.filter { isInstalled(ctx, it) }
            if (willRemain.isEmpty()) {
                errors.add(ctx.getString(R.string.do_skip_hide))
            } else {
                for (pkg in UNMANAGEABLE_BROWSERS) {
                    if (!isInstalled(ctx, pkg)) continue
                    runCatching {
                        dpm.setApplicationHidden(admin, pkg, true)
                        suspended.add(pkg)
                    }.onFailure {
                        errors.add(ctx.getString(R.string.do_err_hide, pkg, it.message ?: ""))
                    }
                }
            }
        }

        // 3) Close the route to installing a fresh browser.
        runCatching {
            dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            restrictions.add(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        }.onFailure {
            errors.add(ctx.getString(R.string.do_err_unknown_sources, it.message ?: ""))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
                restrictions.add(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
            }.onFailure { /* not supported on some devices */ }
        }

        // 4) Make this app un-removable and un-killable.
        var selfProtected = false
        runCatching {
            dpm.setUninstallBlocked(admin, ctx.packageName, true)
            selfProtected = true
        }.onFailure {
            errors.add(ctx.getString(R.string.do_err_uninstall_block, it.message ?: ""))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                dpm.setUserControlDisabledPackages(admin, listOf(ctx.packageName))
            }.onFailure {
                errors.add(ctx.getString(R.string.do_err_force_stop_block, it.message ?: ""))
            }
        }

        // 5) Stop another accessibility service from being used to override us.
        //
        //    NOTE: this also prevents the user from enabling any other
        //    third-party accessibility tool. If someone on this device relies on
        //    an assistive app, do not use this policy.
        runCatching {
            dpm.setPermittedAccessibilityServices(admin, listOf(ctx.packageName))
        }.onFailure { /* system services are always permitted, so this is not fatal */ }

        // 6) Hard-to-undo locks, only when explicitly requested.
        if (lockdownDevice) {
            for (key in listOf(
                UserManager.DISALLOW_DEBUGGING_FEATURES,
                UserManager.DISALLOW_SAFE_BOOT,
                UserManager.DISALLOW_FACTORY_RESET,
                UserManager.DISALLOW_ADD_USER
            )) {
                runCatching {
                    dpm.addUserRestriction(admin, key)
                    restrictions.add(key)
                }.onFailure {
                    errors.add(ctx.getString(R.string.do_err_restriction, key, it.message ?: ""))
                }
            }
        }

        Log.i(TAG, "Applied: managed=$managed hidden=$suspended restrictions=$restrictions")
        return ApplyReport(managed, suspended, restrictions, selfProtected, errors)
    }

    /**
     * Undoes everything [applyAll] can set and gives up device-owner rights.
     * This is the only way to uninstall the app once it is device owner.
     */
    fun releaseEverything(ctx: Context): Boolean {
        if (!isDeviceOwner(ctx)) return false
        val dpm = dpm(ctx)
        val admin = admin(ctx)

        for (pkg in MANAGED_BROWSERS.keys) {
            runCatching { dpm.setApplicationRestrictions(admin, pkg, Bundle()) }
        }
        for (pkg in UNMANAGEABLE_BROWSERS) {
            runCatching { dpm.setApplicationHidden(admin, pkg, false) }
        }
        for (key in listOf(
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
            UserManager.DISALLOW_DEBUGGING_FEATURES,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_ADD_USER
        )) {
            runCatching { dpm.clearUserRestriction(admin, key) }
        }
        runCatching { dpm.setUninstallBlocked(admin, ctx.packageName, false) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { dpm.setUserControlDisabledPackages(admin, emptyList()) }
        }
        runCatching { dpm.setPermittedAccessibilityServices(admin, null) }

        @Suppress("DEPRECATION")
        return runCatching { dpm.clearDeviceOwnerApp(ctx.packageName); true }.getOrDefault(false)
    }

    // ------------------------------------------------------------------

    fun isInstalled(ctx: Context, pkg: String): Boolean =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    /** Watched browsers that are actually present on this device. */
    fun installedBrowsers(ctx: Context): List<String> =
        BrowserRules.allPackages.filter { isInstalled(ctx, it) }

    /** The adb command used to promote this app, shown verbatim in the UI. */
    fun adbCommand(ctx: Context): String =
        "adb shell dpm set-device-owner ${ctx.packageName}/.service.AdminReceiver"
}
