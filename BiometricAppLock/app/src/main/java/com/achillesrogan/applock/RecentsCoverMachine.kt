package com.achillesrogan.applock

/**
 * When the Recents cover is up, and when it must come down.
 *
 * Stricter mode shows the cover the moment a locked, currently unlocked app
 * gives way to a launcher, SystemUI, or recents window, before overview view
 * ids are visible. That also covers Home, so a dark flash is possible. The
 * probe then has to decide, and it is not allowed to run forever:
 * - before [PROBE_SETTLE_MS], "no overview id yet" is not treated as Home
 * - after that, a real Home reading or a different app drops the cover
 * - at [PROBE_CAP_MS] an unread or still-unconfirmed probe is dropped
 *
 * A confirmed overview is held. A missed "overview ended" event is caught by
 * [HOLDING_RECHECK_MS]: the service scans again, and a negative reading after
 * [HOLD_GRACE_MS] takes the cover down.
 */
object RecentsCoverMachine {

    /** Don't call a missing overview id "Home" until the launcher has had a moment to draw it. */
    const val PROBE_SETTLE_MS = 320L

    /** Hard stop for a probe. The cover cannot stay up on a guess longer than this. */
    const val PROBE_CAP_MS = 1_100L

    /** After the cover appears, ignore one burst of "not visible" readings. */
    const val HOLD_GRACE_MS = 800L

    /** While overview is confirmed, look again on this interval so a missed leave event cannot stick. */
    const val HOLDING_RECHECK_MS = 4_000L

    val PROBE_CHECK_DELAYS_MS = longArrayOf(0L, 160L, 400L, 750L)

    enum class Phase { IDLE, PROBING, HOLDING }

    enum class Kind {
        /** Stricter path: left an unlocked locked app for a launcher, SystemUI, or recents window. */
        LEFT_UNLOCKED_LOCKED_FOR_HOST,
        /** Window class is a known recents activity. Overview is confirmed. */
        RECENTS_CLASS,
        /** A launcher or SystemUI window arrived. Scan; do not show by itself. */
        HOST_SIGNAL,
        /** Result of looking for overview view ids. Null means the host could not be read. */
        NODES,
        /** A normal app window, not the launcher, SystemUI, this app, or the keyboard. */
        NON_HOST_APP,
        WATCHDOG,
        DISABLED,
    }

    data class State(
        val phase: Phase = Phase.IDLE,
        val shownAtMs: Long = 0L,
        val classConfirmed: Boolean = false,
        /** Package we had unlocked when the stricter cover went up. Same-package events do not end the probe. */
        val departedPkg: String? = null,
    )

    data class Input(
        val nowMs: Long,
        val kind: Kind,
        val stricter: Boolean,
        /** Hide-in-recents is on and at least one app is locked. */
        val hideEnabled: Boolean,
        val nodesVisible: Boolean? = null,
        val currentClassIsRecents: Boolean = false,
        val foregroundPkg: String? = null,
        val nonHostPkg: String? = null,
    )

    data class Effect(
        val state: State,
        val coverVisible: Boolean,
        val relock: Boolean,
        val scheduleBurst: Boolean,
        val watchdogInMs: Long?,
        /**
         * Probe ended without a new app window. If the foreground is still the
         * locked app, the service shows the fingerprint screen. Not set when a
         * different app just came to the front; that window is handled on its own.
         */
        val lockForegroundIfNeeded: Boolean,
    )

    fun leavingUnlockedLockedApp(
        stricter: Boolean,
        hideEnabled: Boolean,
        foregroundPkg: String?,
        foregroundIsLocked: Boolean,
        foregroundIsUnlocked: Boolean,
        newPkg: String?,
        newClass: String,
        ownPkg: String,
    ): Boolean {
        if (!stricter || !hideEnabled) return false
        if (newPkg.isNullOrEmpty() || newPkg == ownPkg || newPkg == foregroundPkg) return false
        if (foregroundPkg.isNullOrEmpty() || !foregroundIsLocked || !foregroundIsUnlocked) return false
        return RecentsDetector.classNameIsRecents(newClass) ||
            RecentsDetector.shouldInspect(newPkg, newClass)
    }

    fun step(state: State, input: Input): Effect {
        if (!input.hideEnabled || input.kind == Kind.DISABLED) return idle()
        return when (input.kind) {
            Kind.DISABLED -> idle()
            Kind.LEFT_UNLOCKED_LOCKED_FOR_HOST -> onLeft(state, input)
            Kind.RECENTS_CLASS -> confirm(state, input, classConfirmed = true)
            Kind.HOST_SIGNAL -> hostSignal(state, input)
            Kind.NODES -> onNodes(state, input)
            Kind.NON_HOST_APP -> onNonHost(state, input)
            Kind.WATCHDOG -> onWatchdog(state, input)
        }
    }

    private fun onLeft(state: State, input: Input): Effect {
        if (!input.stricter) return stay(state, input.nowMs)
        return when (state.phase) {
            Phase.HOLDING -> stay(state, input.nowMs)
            Phase.PROBING -> {
                val stayed = stay(state, input.nowMs)
                if (stayed.state.phase == Phase.IDLE) stayed else stayed.copy(scheduleBurst = true)
            }
            Phase.IDLE -> startProbe(input)
        }
    }

    private fun startProbe(input: Input) = Effect(
        state = State(
            phase = Phase.PROBING,
            shownAtMs = input.nowMs,
            classConfirmed = false,
            departedPkg = input.foregroundPkg,
        ),
        coverVisible = true,
        relock = true,
        scheduleBurst = true,
        watchdogInMs = PROBE_CAP_MS,
        lockForegroundIfNeeded = false,
    )

    private fun confirm(state: State, input: Input, classConfirmed: Boolean): Effect {
        val wasDown = state.phase == Phase.IDLE
        return Effect(
            state = State(
                phase = Phase.HOLDING,
                shownAtMs = if (wasDown) input.nowMs else state.shownAtMs,
                classConfirmed = state.classConfirmed || classConfirmed,
                departedPkg = state.departedPkg,
            ),
            coverVisible = true,
            relock = wasDown,
            scheduleBurst = false,
            watchdogInMs = HOLDING_RECHECK_MS,
            lockForegroundIfNeeded = false,
        )
    }

    private fun hostSignal(state: State, input: Input): Effect {
        // The stricter departure is a separate event. This one only asks for a scan.
        val stayed = stay(state, input.nowMs)
        return if (stayed.state.phase == Phase.IDLE) stayed else stayed.copy(scheduleBurst = true)
    }

    private fun onNodes(state: State, input: Input): Effect {
        if (input.nodesVisible == true) {
            return confirm(state, input, classConfirmed = state.classConfirmed)
        }
        if (input.currentClassIsRecents && input.nodesVisible != false) {
            return confirm(state, input, classConfirmed = true)
        }
        if (state.phase == Phase.IDLE) return stay(state, input.nowMs)
        val age = input.nowMs - state.shownAtMs
        if (state.phase == Phase.PROBING) {
            if (input.nodesVisible == null) {
                return if (age >= PROBE_CAP_MS) idle(lockForeground = true) else stay(state, input.nowMs)
            }
            return if (age >= PROBE_SETTLE_MS) idle(lockForeground = true) else stay(state, input.nowMs)
        }
        // Holding. A recents activity that does not use Launcher3 ids must not be
        // dropped just because those ids are absent.
        if (state.classConfirmed && input.currentClassIsRecents) return stay(state, input.nowMs)
        if (input.nodesVisible == null) return stay(state, input.nowMs)
        return if (age >= HOLD_GRACE_MS) idle(lockForeground = true) else stay(state, input.nowMs)
    }

    private fun onNonHost(state: State, input: Input): Effect {
        if (state.phase == Phase.IDLE) return stay(state, input.nowMs)
        val sameApp = input.nonHostPkg != null && input.nonHostPkg == state.departedPkg
        if (sameApp && state.phase == Phase.PROBING) return stay(state, input.nowMs)
        return idle(lockForeground = false)
    }

    private fun onWatchdog(state: State, input: Input): Effect {
        val age = input.nowMs - state.shownAtMs
        return when (state.phase) {
            Phase.IDLE -> stay(state, input.nowMs)
            Phase.PROBING -> {
                if (age >= PROBE_CAP_MS) {
                    idle(lockForeground = true)
                } else {
                    stay(state, input.nowMs).copy(watchdogInMs = PROBE_CAP_MS - age)
                }
            }
            Phase.HOLDING -> stay(state, input.nowMs).copy(watchdogInMs = HOLDING_RECHECK_MS)
        }
    }

    private fun stay(state: State, nowMs: Long): Effect {
        if (state.phase == Phase.PROBING && nowMs - state.shownAtMs >= PROBE_CAP_MS) {
            return idle(lockForeground = true)
        }
        val watchdog = when (state.phase) {
            Phase.IDLE -> null
            Phase.PROBING -> PROBE_CAP_MS - (nowMs - state.shownAtMs)
            Phase.HOLDING -> HOLDING_RECHECK_MS
        }
        return Effect(
            state = state,
            coverVisible = state.phase != Phase.IDLE,
            relock = false,
            scheduleBurst = false,
            watchdogInMs = watchdog,
            lockForegroundIfNeeded = false,
        )
    }

    private fun idle(lockForeground: Boolean = false) = Effect(
        state = State(),
        coverVisible = false,
        relock = false,
        scheduleBurst = false,
        watchdogInMs = null,
        lockForegroundIfNeeded = lockForeground,
    )
}
