package com.achillesrogan.applock

/**
 * In-memory session state shared by the service and the lock screen (same process).
 * An app stays unlocked until the user leaves it or the screen turns off.
 */
object LockState {
    private val unlocked = mutableSetOf<String>()
    private var currentForeground: String? = null

    /** Package the lock screen is currently showing for, to avoid launching it twice. */
    @Volatile
    var lockScreenFor: String? = null

    @Synchronized
    fun isUnlocked(pkg: String) = pkg in unlocked

    @Synchronized
    fun unlock(pkg: String) {
        unlocked += pkg
    }

    @Synchronized
    fun relockAll() {
        unlocked.clear()
    }

    @Synchronized
    fun foreground(): String? = currentForeground

    /** Called whenever a different app comes to the front; relocks the app that was left. */
    @Synchronized
    fun onForegroundChanged(pkg: String) {
        val previous = currentForeground
        if (previous != null && previous != pkg) unlocked -= previous
        currentForeground = pkg
    }
}
