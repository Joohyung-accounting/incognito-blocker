package com.incognitoblocker.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import com.incognitoblocker.R

/**
 * Device admin receiver.
 *
 * It plays two roles:
 *
 *  1) Ordinary mode — registering as a device admin means the app cannot be
 *     uninstalled in one step. Since Android 7.0 an admin cannot block its own
 *     deactivation, so this is a speed bump, not a lock.
 *
 *  2) Device owner mode — this is the component named in
 *     `adb shell dpm set-device-owner`. In that case it becomes a real lock the
 *     user cannot undo from Settings.
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin enabled")
        Toast.makeText(
            context,
            context.getString(R.string.admin_enabled_toast),
            Toast.LENGTH_SHORT
        ).show()
        GuardForegroundService.start(context)
    }

    /**
     * Warning shown when the user tries to deactivate the admin. It cannot be
     * prevented, but what is about to happen is stated plainly.
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.admin_disable_warning)

    override fun onDisabled(context: Context, intent: Intent) {
        Log.w(TAG, "Device admin disabled")
        GuardForegroundService.start(context)
    }

    companion object {
        private const val TAG = "IncognitoGuardAdmin"
    }
}
