package com.achillesrogan.applock

/**
 * The Recents cover must come back every time overview opens.
 * A failure can skip the rest of the current overview. It must not survive
 * leaving overview, the screen turning off or on, or a lock/unlock.
 */
object RecentsRearm {

    data class Flags(
        val failedThisOverview: Boolean = false,
        val overviewOpen: Boolean = false,
        val burstAtMs: Long = -1L,
        val scanAtMs: Long = -1L,
    )

    fun rearm(flags: Flags): Flags = Flags()

    /** First detection of a new overview. Clears a failure from the previous one. */
    fun onOverviewOpened(flags: Flags): Flags {
        if (flags.overviewOpen && !flags.failedThisOverview) return flags
        return Flags(overviewOpen = true)
    }

    fun onOverviewClosed(flags: Flags): Flags = Flags()

    fun onCoverFailed(flags: Flags): Flags = flags.copy(failedThisOverview = true, overviewOpen = true)

    fun shouldAttempt(flags: Flags, hideEnabled: Boolean, overviewDetected: Boolean): Boolean =
        hideEnabled && overviewDetected && !flags.failedThisOverview
}

/**
 * Fingerprint for one Recents visit. A grant does not carry to the next open.
 * Grace is off: the next overview always asks again.
 */
object RecentsFingerprint {
    const val GRACE_MS = 0L

    data class Session(
        val id: Long = 0L,
        val grantedFor: Long = -1L,
    )

    fun begin(session: Session): Session = session.copy(id = session.id + 1L)

    fun grant(session: Session): Session = session.copy(grantedFor = session.id)

    fun isGranted(session: Session): Boolean = session.grantedFor == session.id && session.id != 0L

    /** Off by default. A grace would show other locked thumbnails after one unlock. */
    fun stillGrantedAfter(session: Session, elapsedMs: Long, graceMs: Long = GRACE_MS): Boolean =
        isGranted(session) && elapsedMs <= graceMs
}

/** Process-wide session the auth activity and the accessibility service share. */
object RecentsFingerprintGate {
    var session: RecentsFingerprint.Session = RecentsFingerprint.Session()
    var prompted: Boolean = false
    var onGranted: (() -> Unit)? = null
    var onHome: (() -> Unit)? = null
    var onAuthFinished: ((Boolean) -> Unit)? = null

    fun begin() {
        session = RecentsFingerprint.begin(session)
        prompted = false
    }

    fun grant() {
        session = RecentsFingerprint.grant(session)
        onGranted?.invoke()
    }

    fun home() {
        onHome?.invoke()
    }

    fun authFinished(granted: Boolean) {
        onAuthFinished?.invoke(granted)
    }

    fun granted(): Boolean = RecentsFingerprint.isGranted(session)

    fun clear() {
        session = RecentsFingerprint.Session()
        prompted = false
    }
}
