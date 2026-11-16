package com.achillesrogan.applock

import android.content.Context

/**
 * "Hide locked apps in Recents". Missing means on, so an upgrade from 1.5
 * covers overview without a trip through settings first.
 */
object RecentsGuard {
    private const val PREFS = "app_lock_prefs"
    private const val KEY = "hide_locked_in_recents"

    fun enabledOrDefault(stored: Boolean?): Boolean = stored ?: true

    fun isEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY, enabled)
            .apply()
    }
}
