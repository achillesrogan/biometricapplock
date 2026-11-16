package com.achillesrogan.applock

/**
 * One decision for "is this app actually locked right now?"
 * The list switches and the accessibility service both use [visibleLocks].
 * A missing prefs file means storage was cleared; a leftover in-memory set is ignored.
 */
object LockHealth {

    enum class Problem { NONE, SERVICE_OFF, KEY_BROKEN }

    fun problem(serviceEnabled: Boolean, keyBroken: Boolean): Problem = when {
        !serviceEnabled -> Problem.SERVICE_OFF
        keyBroken -> Problem.KEY_BROKEN
        else -> Problem.NONE
    }

    fun visibleLocks(prefsFileExists: Boolean, persisted: Set<String>): Set<String> =
        if (prefsFileExists) persisted else emptySet()

    /** What the service will cover. Session unlocks are in-memory only. */
    fun enforces(
        serviceEnabled: Boolean,
        locked: Set<String>,
        pkg: String,
        sessionUnlocked: Boolean,
    ): Boolean = serviceEnabled && pkg in locked && !sessionUnlocked
}

/** How often the Recents cover may walk window nodes. */
object RecentsScanGate {
    const val MIN_INTERVAL_MS = 350L

    fun allow(nowMs: Long, lastScanMs: Long): Boolean =
        lastScanMs < 0L || nowMs - lastScanMs >= MIN_INTERVAL_MS
}

/**
 * A cover failure skips the rest of this overview. It does not survive the next
 * one: [coverAllowed] is false only while [retired] is still set, and the service
 * clears that on a new overview, screen off/on, and lock/unlock.
 */
object RecentsCoverSession {
    fun coverAllowed(retired: Boolean): Boolean = !retired
}
