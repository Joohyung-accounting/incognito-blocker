package com.incognitoblocker.core

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Settings storage.
 *
 * The guardian PIN is never stored in plain text — it is hashed with PBKDF2.
 * A rooted device defeats this anyway, but it does stop someone from simply
 * opening the preferences file and reading the number.
 */
object Prefs {

    private const val FILE = "incognito_blocker_prefs"

    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_PROTECTION_ON = "protection_on"
    private const val KEY_CONSENT_GIVEN = "consent_given"
    private const val KEY_SETUP_DONE = "setup_done"
    private const val KEY_BLOCK_COUNT = "block_count"
    private const val KEY_STRICT_MODE = "strict_mode"
    private const val KEY_DIAGNOSTIC = "diagnostic_mode"

    private const val PBKDF2_ITERATIONS = 120_000
    private const val PBKDF2_KEY_BITS = 256

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- PIN ---------------------------------------------------------------

    fun hasPin(ctx: Context): Boolean = prefs(ctx).contains(KEY_PIN_HASH)

    fun setPin(ctx: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt)
        prefs(ctx).edit()
            .putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
    }

    fun verifyPin(ctx: Context, pin: String): Boolean {
        val p = prefs(ctx)
        val saltB64 = p.getString(KEY_PIN_SALT, null) ?: return false
        val hashB64 = p.getString(KEY_PIN_HASH, null) ?: return false
        val salt = Base64.decode(saltB64, Base64.NO_WRAP)
        val expected = Base64.decode(hashB64, Base64.NO_WRAP)
        return constantTimeEquals(expected, derive(pin, salt))
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        // PBKDF2WithHmacSHA1 is available on every supported API level.
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    // ---- Flags -------------------------------------------------------------

    fun isProtectionEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_PROTECTION_ON, true)
    fun setProtectionEnabled(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_PROTECTION_ON, v).apply()

    fun hasConsent(ctx: Context) = prefs(ctx).getBoolean(KEY_CONSENT_GIVEN, false)
    fun setConsent(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_CONSENT_GIVEN, v).apply()

    fun isSetupDone(ctx: Context) = prefs(ctx).getBoolean(KEY_SETUP_DONE, false)
    fun setSetupDone(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SETUP_DONE, v).apply()

    /** Strict mode sends the browser to the background as well as closing the window. */
    fun isStrictMode(ctx: Context) = prefs(ctx).getBoolean(KEY_STRICT_MODE, true)
    fun setStrictMode(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_STRICT_MODE, v).apply()

    /**
     * Diagnostic mode.
     *
     * Records what is actually readable on a browser screen. Detection rules
     * differ per browser version and language, so when nothing is caught on a
     * particular device this is how to find out what to add — Samsung Internet
     * especially, since it publishes no policy and screen detection is all there is.
     */
    fun isDiagnosticMode(ctx: Context) = prefs(ctx).getBoolean(KEY_DIAGNOSTIC, false)
    fun setDiagnosticMode(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_DIAGNOSTIC, v).apply()

    fun blockCount(ctx: Context) = prefs(ctx).getInt(KEY_BLOCK_COUNT, 0)
    fun incrementBlockCount(ctx: Context): Int {
        val n = blockCount(ctx) + 1
        prefs(ctx).edit().putInt(KEY_BLOCK_COUNT, n).apply()
        return n
    }
}
