package com.achillesrogan.applock

import android.content.Context
import java.io.File

/**
 * The only store of locked package names. The list and the accessibility
 * service both call [load]. A wiped prefs file is an empty set, even if this
 * process still remembers an older copy.
 */
object LockStore {
    private const val PREFS = "app_lock_prefs"
    private const val KEY_LOCKED = "locked_packages"

    private var snapshot: Set<String> = emptySet()
    private var loaded = false
    private var fileStamp = Long.MIN_VALUE
    private val listeners = mutableListOf<() -> Unit>()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun prefsFile(context: Context): File =
        File(context.applicationInfo.dataDir, "shared_prefs/$PREFS.xml")

    fun load(context: Context): Set<String> {
        val app = context.applicationContext
        val file = prefsFile(app)
        if (!file.exists()) {
            snapshot = emptySet()
            loaded = true
            fileStamp = 0L
            return snapshot
        }
        val stamp = file.lastModified()
        if (loaded && stamp == fileStamp) return snapshot
        snapshot = prefs(app).getStringSet(KEY_LOCKED, emptySet()).orEmpty().toSet()
        fileStamp = stamp
        loaded = true
        return snapshot
    }

    fun lockedPackages(context: Context): Set<String> = load(context)

    fun isLocked(context: Context, pkg: String): Boolean = pkg in load(context)

    fun setLocked(context: Context, pkg: String, locked: Boolean) {
        val app = context.applicationContext
        val updated = load(app).toMutableSet()
        if (locked) updated += pkg else updated -= pkg
        val written = HashSet(updated)
        // commit, not apply, so a following load sees the file that was just written.
        prefs(app).edit().putStringSet(KEY_LOCKED, written).commit()
        snapshot = written
        loaded = true
        fileStamp = prefsFile(app).lastModified()
        listeners.toList().forEach { it() }
    }

    fun addListener(listener: () -> Unit) {
        if (listener !in listeners) listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
}
