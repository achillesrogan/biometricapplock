package com.achillesrogan.applock

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Keeps Android from uninstalling this app while uninstall protection is on.
 * No password, lock, wipe, or camera policy is requested.
 * Deactivation from system Settings is covered by [AppLockService]; this
 * message is the system dialog if that cover does not appear first.
 */
class AppDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.device_admin_disable_warning)
}
