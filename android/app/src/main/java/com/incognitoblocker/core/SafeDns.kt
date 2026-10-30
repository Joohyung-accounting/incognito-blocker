package com.incognitoblocker.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import com.incognitoblocker.R
import com.incognitoblocker.service.PauseEndReceiver

/**
 * Adult-site blocking through Android's Private DNS (DNS-over-TLS, Android 9+).
 *
 * The app never resolves anything itself — it has no INTERNET permission and
 * keeps it that way. It points the system's Private DNS at a family-filter
 * resolver, and the resolver refuses adult domains for every app on the device:
 * incognito tabs, Samsung Internet and in-app browsers included. Which domains
 * are adult is the resolver's job; there is deliberately no list in this app.
 *
 * Three ways to hold the setting, strongest last:
 *  - [Control.MANUAL]: the user types the hostname into Settings and the app
 *    only reports whether it is still there.
 *  - [Control.SECURE_SETTINGS]: after `pm grant ... WRITE_SECURE_SETTINGS` over
 *    adb the app writes the setting itself, and the watchdog writes it back
 *    whenever something changes it.
 *  - [Control.DEVICE_OWNER]: the same grant, plus DISALLOW_CONFIG_PRIVATE_DNS,
 *    which greys out the Settings screen and refuses adb's writes as well.
 *
 * DevicePolicyManager.setGlobalPrivateDnsModeSpecifiedHost is deliberately not
 * used: it checks the resolver with a TLS connection opened inside the calling
 * app, which fails with EPERM in an app without INTERNET — every time.
 *
 * Strict Private DNS fails closed: on a network that blocks port 853, or behind
 * a captive portal, nothing resolves at all. That is why a PIN-gated pause
 * exists, and why it ends by itself.
 */
object SafeDns {

    private const val TAG = "IncognitoGuardDns"

    /**
     * Family-filter resolvers that speak DNS-over-TLS.
     *
     * Measured 2026-10-30 by asking each one's plain-DNS address for the search
     * engines: all three rewrite Google, Bing and DuckDuckGo to their safe-search
     * hosts, and only the first two also put YouTube into Restricted Mode.
     */
    enum class Provider(val host: String, val labelRes: Int, val descRes: Int) {
        CLEANBROWSING(
            "family-filter-dns.cleanbrowsing.org",
            R.string.dns_cleanbrowsing,
            R.string.dns_cleanbrowsing_desc
        ),
        ADGUARD(
            "family.adguard-dns.com",
            R.string.dns_adguard,
            R.string.dns_adguard_desc
        ),
        CLOUDFLARE(
            "family.cloudflare-dns.com",
            R.string.dns_cloudflare,
            R.string.dns_cloudflare_desc
        );

        companion object {
            val DEFAULT = CLEANBROWSING

            fun forHost(host: String?): Provider? {
                val h = host?.trim()?.trimEnd('.') ?: return null
                return entries.firstOrNull { it.host.equals(h, ignoreCase = true) }
            }
        }
    }

    /** Who holds the setting. */
    enum class Control { UNSUPPORTED, MANUAL, SECURE_SETTINGS, DEVICE_OWNER }

    /** What a change came to, for the screen to explain. */
    enum class Outcome { DONE, MANUAL_STEP, FAILED }

    // Settings.Global keys. Hidden from the SDK, but unchanged since Android 9
    // and the same ones every Private DNS toggle writes.
    const val KEY_MODE = "private_dns_mode"
    const val KEY_SPECIFIER = "private_dns_specifier"
    private const val MODE_OPPORTUNISTIC = "opportunistic"
    private const val MODE_HOSTNAME = "hostname"

    /**
     * A write the app made itself triggers the settings observer, which calls
     * [enforce] again. If the keys ever become unreadable the comparison could
     * not settle, so blind writes are spaced out instead of looping. Readable
     * keys are never throttled — a change is written back the moment it lands.
     */
    private const val MIN_WRITE_GAP_MS = 5_000L

    @Volatile private var lastWriteAt = 0L

    fun isSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /**
     * Writing needs the grant; the device-owner lock is an extra on top of it.
     * A device owner without the grant is MANUAL: a lock it could not write
     * past would freeze whatever the setting happened to be.
     */
    fun control(ctx: Context): Control = when {
        !isSupported() -> Control.UNSUPPORTED
        !canWriteSettings(ctx) -> Control.MANUAL
        canLock(ctx) -> Control.DEVICE_OWNER
        else -> Control.SECURE_SETTINGS
    }

    fun canWriteSettings(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    private fun canLock(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && DeviceOwner.isDeviceOwner(ctx)

    /** The adb command that unlocks [Control.SECURE_SETTINGS], shown verbatim in the UI. */
    fun grantCommand(ctx: Context): String =
        "adb shell pm grant ${ctx.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}"

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    private class Raw(val mode: String?, val host: String?)

    /** Null when this Android build does not let the app read the keys. */
    private fun readRaw(ctx: Context): Raw? = runCatching {
        val cr = ctx.contentResolver
        Raw(Settings.Global.getString(cr, KEY_MODE), Settings.Global.getString(cr, KEY_SPECIFIER))
    }.onFailure { Log.w(TAG, "Private DNS setting is not readable", it) }.getOrNull()

    /** The strict-mode host the system is set to, or null when Private DNS is off or automatic. */
    fun configuredHost(ctx: Context): String? {
        if (!isSupported()) return null
        val raw = readRaw(ctx) ?: return networkHost(ctx)
        return if (raw.mode == MODE_HOSTNAME) raw.host?.takeIf { it.isNotBlank() } else null
    }

    /** The strict-mode host on the active network, as the network stack reports it. */
    private fun networkHost(ctx: Context): String? = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getLinkProperties(cm.activeNetwork)?.privateDnsServerName
    }.getOrNull()

    /**
     * Whether the active network passed the system's own internet check. With
     * strict Private DNS an unreachable resolver fails it, so this is the
     * clearest sign the filter is what cut the connection. Null with no network.
     */
    fun networkValidated(ctx: Context): Boolean? = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return@runCatching null
        cm.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrNull()

    /** The filter the system is using right now, or null when it is not filtering. */
    fun activeProvider(ctx: Context): Provider? = Provider.forHost(configuredHost(ctx))

    fun isPaused(ctx: Context) = Prefs.dnsPausedUntil(ctx) > System.currentTimeMillis()

    fun pauseMinutesLeft(ctx: Context): Int {
        val ms = Prefs.dnsPausedUntil(ctx) - System.currentTimeMillis()
        return if (ms <= 0) 0 else ((ms + 59_999) / 60_000).toInt()
    }

    /** Whether the device-owner lock on the Private DNS screen is in place. */
    fun isLocked(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && runCatching {
            ctx.getSystemService(UserManager::class.java)
                .hasUserRestriction(UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
        }.getOrDefault(false)

    // ------------------------------------------------------------------
    // Changing
    // ------------------------------------------------------------------

    /** Turns filtering on with [provider], or switches to it. */
    fun enable(ctx: Context, provider: Provider, done: (Outcome) -> Unit) {
        val app = ctx.applicationContext
        Prefs.setDnsProvider(app, provider)
        Prefs.setDnsWanted(app, true)
        Prefs.setDnsPausedUntil(app, 0L)
        when (control(app)) {
            Control.UNSUPPORTED -> done(Outcome.FAILED)
            Control.MANUAL ->
                done(if (activeProvider(app) == provider) Outcome.DONE else Outcome.MANUAL_STEP)
            else -> {
                val ok = unlocked(app) { writeHost(app, provider.host) }
                if (ok) lock(app, true)
                done(if (ok) Outcome.DONE else Outcome.FAILED)
            }
        }
    }

    /** Turns filtering off and puts back what was there before. */
    fun disable(ctx: Context, done: (Outcome) -> Unit) {
        val app = ctx.applicationContext
        Prefs.setDnsWanted(app, false)
        Prefs.setDnsPausedUntil(app, 0L)
        // Also clears a lock left from before the grant was revoked.
        lock(app, false)
        when (control(app)) {
            Control.UNSUPPORTED -> done(Outcome.DONE)
            Control.MANUAL ->
                done(if (activeProvider(app) != null) Outcome.MANUAL_STEP else Outcome.DONE)
            else -> done(if (restoreOriginal(app)) Outcome.DONE else Outcome.FAILED)
        }
    }

    /**
     * Lets DNS through unfiltered for [minutes] — for a captive portal, or a
     * network that blocks port 853 and so has no internet at all under strict
     * mode. [enforce] turns the filter back on when the time is up, which is
     * why this needs the WRITE_SECURE_SETTINGS grant.
     */
    fun pause(ctx: Context, minutes: Int, done: (Outcome) -> Unit) {
        val app = ctx.applicationContext
        if (control(app) == Control.MANUAL || control(app) == Control.UNSUPPORTED) {
            return done(Outcome.FAILED)
        }
        val until = System.currentTimeMillis() + minutes * 60_000L
        Prefs.setDnsPausedUntil(app, until)
        // A device owner's lock goes straight back on: the pause must not become
        // a window for typing in another host.
        val ok = unlocked(app) { writeMode(app, MODE_OPPORTUNISTIC) }
        if (ok) PauseEndReceiver.schedule(app, until) else Prefs.setDnsPausedUntil(app, 0L)
        done(if (ok) Outcome.DONE else Outcome.FAILED)
    }

    /**
     * Brings the system back to what was asked for. Cheap when nothing differs,
     * so the watchdog calls it on every tick and on every settings change.
     */
    fun enforce(ctx: Context) {
        val app = ctx.applicationContext
        val control = control(app)
        if (control == Control.UNSUPPORTED) return

        // A filter typed into Settings by hand before the app was ever asked:
        // take it as the choice, so the app can say when it disappears.
        val active = activeProvider(app)
        if (Prefs.isDnsWanted(app) == null && active != null) {
            Prefs.setDnsWanted(app, true)
            Prefs.setDnsProvider(app, active)
        }
        if (control == Control.MANUAL && active != null && active != Prefs.dnsProvider(app)) {
            Prefs.setDnsProvider(app, active)
        }

        val pausedUntil = Prefs.dnsPausedUntil(app)
        if (pausedUntil != 0L) {
            if (System.currentTimeMillis() < pausedUntil) return
            Log.i(TAG, "Pause is over - filtering again")
            Prefs.setDnsPausedUntil(app, 0L)
            if (Prefs.isDnsWanted(app) == true) enable(app, Prefs.dnsProvider(app)) {}
            return
        }

        if (Prefs.isDnsWanted(app) != true || control == Control.MANUAL) return
        val wanted = Prefs.dnsProvider(app)
        if (active != wanted) {
            Log.i(TAG, "Private DNS was changed - writing the filter back")
            if (unlocked(app) { writeHost(app, wanted.host, throttled = true) }) lock(app, true)
        } else if (control == Control.DEVICE_OWNER && !isLocked(app)) {
            // Device owner granted after the filter was already on.
            lock(app, true)
        }
    }

    // ------------------------------------------------------------------
    // WRITE_SECURE_SETTINGS
    // ------------------------------------------------------------------

    /** [throttled] is for the watchdog only; a tap on the screen always writes. */
    private fun writeHost(ctx: Context, host: String, throttled: Boolean = false): Boolean {
        val raw = readRaw(ctx)
        if (raw != null && raw.mode == MODE_HOSTNAME && raw.host == host) return true
        // Readable keys settle on the check above, so only blind writes can loop.
        if (throttled && raw == null && !writeAllowedNow()) return false
        lastWriteAt = SystemClock.elapsedRealtime()

        // Remember what was there first, so turning the filter off can put it
        // back. Never record a filter of our own as the thing to return to.
        if (raw != null && Prefs.dnsOriginal(ctx) == null &&
            Provider.forHost(if (raw.mode == MODE_HOSTNAME) raw.host else null) == null
        ) {
            Prefs.setDnsOriginal(ctx, raw.mode, raw.host)
        }

        return runCatching {
            val cr = ctx.contentResolver
            // Host first: the moment the mode flips, the system reads the host.
            // putString reports a refused write by returning false, not by throwing.
            Settings.Global.putString(cr, KEY_SPECIFIER, host) &&
                Settings.Global.putString(cr, KEY_MODE, MODE_HOSTNAME)
        }.onFailure { Log.w(TAG, "Could not write Private DNS", it) }.getOrDefault(false)
    }

    private fun writeMode(ctx: Context, mode: String): Boolean {
        if (readRaw(ctx)?.mode == mode) return true
        return runCatching {
            lastWriteAt = SystemClock.elapsedRealtime()
            Settings.Global.putString(ctx.contentResolver, KEY_MODE, mode)
        }.onFailure { Log.w(TAG, "Could not write Private DNS mode", it) }.getOrDefault(false)
    }

    private fun restoreOriginal(ctx: Context): Boolean {
        val original = Prefs.dnsOriginal(ctx)
        val ok = runCatching {
            val cr = ctx.contentResolver
            val hostOk = original?.second == null ||
                Settings.Global.putString(cr, KEY_SPECIFIER, original.second)
            hostOk && Settings.Global.putString(cr, KEY_MODE, original?.first ?: MODE_OPPORTUNISTIC)
        }.onFailure { Log.w(TAG, "Could not restore Private DNS", it) }.getOrDefault(false)
        if (ok) Prefs.clearDnsOriginal(ctx)
        return ok
    }

    private fun writeAllowedNow(): Boolean =
        lastWriteAt == 0L || SystemClock.elapsedRealtime() - lastWriteAt >= MIN_WRITE_GAP_MS

    // ------------------------------------------------------------------
    // Device owner
    // ------------------------------------------------------------------

    /**
     * Device owner only: greys out the Private DNS screen in Settings, and turns
     * Chrome's own secure-DNS setting off — a custom DoH provider chosen there
     * would route Chrome around Private DNS entirely.
     */
    private fun lock(ctx: Context, on: Boolean) {
        if (!canLock(ctx)) return
        setRestriction(ctx, on)
        DeviceOwner.setChromeSecureDnsOff(ctx, on)
    }

    private fun setRestriction(ctx: Context, on: Boolean) {
        runCatching {
            val dpm = DeviceOwner.dpm(ctx)
            val admin = DeviceOwner.admin(ctx)
            if (on) dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
            else dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
        }.onFailure { Log.w(TAG, "Private DNS restriction failed", it) }
    }

    /**
     * DISALLOW_CONFIG_PRIVATE_DNS refuses this app's own writes to the setting
     * too, so the lock is lifted around them and put straight back.
     */
    private inline fun unlocked(ctx: Context, write: () -> Boolean): Boolean {
        val relock = canLock(ctx) && isLocked(ctx)
        if (relock) setRestriction(ctx, false)
        try {
            return write()
        } finally {
            if (relock) setRestriction(ctx, true)
        }
    }
}
