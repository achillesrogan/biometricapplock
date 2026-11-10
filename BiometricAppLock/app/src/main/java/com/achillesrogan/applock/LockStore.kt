package com.achillesrogan.applock

import android.content.Context

/** Persists which packages the user has chosen to lock. */
object LockStore {
    private const val PREFS = "app_lock_prefs"
    private const val KEY_LOCKED = "locked_packages"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lockedPackages(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_LOCKED, emptySet())?.toSet() ?: emptySet()

    fun isLocked(context: Context, pkg: String): Boolean = pkg in lockedPackages(context)

    fun setLocked(context: Context, pkg: String, locked: Boolean) {
        val updated = lockedPackages(context).toMutableSet()
        if (locked) updated += pkg else updated -= pkg
        // Always write a new Set instance; SharedPreferences must not get the same object back.
        prefs(context).edit().putStringSet(KEY_LOCKED, HashSet(updated)).apply()
    }
}
