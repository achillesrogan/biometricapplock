package com.achillesrogan.applock

import android.content.Context

/**
 * "Hide locked apps in Recents". Missing means on, so an upgrade from 1.5
 * covers overview without a trip through settings first.
 */
object RecentsGuard {
    private const val PREFS = "app_lock_prefs"
    private const val KEY = "hide_locked_in_recents"
    private const val KEY_STRICT = "stricter_recents_cover"

    fun enabledOrDefault(stored: Boolean?): Boolean = stored ?: true

    /** Off unless the user opts in. Home and overview look alike for the first moment, so this flashes a dark cover on Home too. */
    fun stricterOrDefault(stored: Boolean?): Boolean = stored ?: false

    /** Stricter does nothing while Hide locked apps in Recents is off. */
    fun stricterActive(hideEnabled: Boolean, stricterStored: Boolean): Boolean = hideEnabled && stricterStored

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY, enabled).apply()
    }

    fun isStricterStored(context: Context): Boolean = prefs(context).getBoolean(KEY_STRICT, false)

    fun isStricter(context: Context): Boolean = stricterActive(isEnabled(context), isStricterStored(context))

    fun setStricter(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_STRICT, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
