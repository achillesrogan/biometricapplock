package com.achillesrogan.applock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentsCoverMachineTest {

    private val notes = "com.example.notes"
    private val launcher = "com.google.android.apps.nexuslauncher"
    private val launcherClass = "com.google.android.apps.nexuslauncher.NexusLauncherActivity"
    private val recentsClass = "com.android.systemui.recents.RecentsActivity"
    private val own = "com.achillesrogan.applock"

    @Test
    fun stricterOff_doesNotCoverJustBecauseTheAppWasLeft() {
        val effect = RecentsCoverMachine.step(RecentsCoverMachine.State(), left(stricter = false))
        assertEquals(RecentsCoverMachine.Phase.IDLE, effect.state.phase)
        assertFalse(effect.coverVisible)
        assertFalse(effect.relock)
    }

    @Test
    fun leavingAnUnlockedLockedApp_coversImmediatelyAndRelocks() {
        val effect = RecentsCoverMachine.step(RecentsCoverMachine.State(), left(stricter = true))
        assertEquals(RecentsCoverMachine.Phase.PROBING, effect.state.phase)
        assertTrue(effect.coverVisible)
        assertTrue(effect.relock)
        assertTrue(effect.scheduleBurst)
        assertEquals(RecentsCoverMachine.PROBE_CAP_MS, effect.watchdogInMs)
        assertEquals(notes, effect.state.departedPkg)
    }

    @Test
    fun repeatedDeparture_doesNotExtendTheProbeCap() {
        val started = RecentsCoverMachine.step(RecentsCoverMachine.State(), left(stricter = true, now = 0L))
        val again = RecentsCoverMachine.step(
            started.state,
            left(stricter = true, now = 900L),
        )
        assertEquals(0L, again.state.shownAtMs)
        assertEquals(RecentsCoverMachine.PROBE_CAP_MS - 900L, again.watchdogInMs)
        assertFalse(again.relock)
    }

    @Test
    fun homeReadingBeforeSettle_keepsTheCover() {
        val probing = RecentsCoverMachine.step(RecentsCoverMachine.State(), left(stricter = true, now = 0L)).state
        val early = RecentsCoverMachine.step(probing, nodes(visible = false, now = 100L))
        assertEquals(RecentsCoverMachine.Phase.PROBING, early.state.phase)
        assertTrue(early.coverVisible)
    }

    @Test
    fun homeReadingAfterSettle_dropsTheCover() {
        val probing = RecentsCoverMachine.step(RecentsCoverMachine.State(), left(stricter = true, now = 0L)).state
        val home = RecentsCoverMachine.step(
            probing,
            nodes(visible = false, now = RecentsCoverMachine.PROBE_SETTLE_MS),
        )
        assertEquals(RecentsCoverMachine.Phase.IDLE, home.state.phase)
        assertFalse(home.coverVisible)
        assertNull(home.watchdogInMs)
        assertTrue(home.lockForegroundIfNeeded)
    }

    @Test
    fun overviewIds_promoteTheProbeAndKeepTheCover() {
        val probing = RecentsCoverMachine.step(RecentsCoverMachine.State(), left(stricter = true, now = 0L)).state
        val overview = RecentsCoverMachine.step(probing, nodes(visible = true, now = 200L))
        assertEquals(RecentsCoverMachine.Phase.HOLDING, overview.state.phase)
        assertTrue(overview.coverVisible)
        assertFalse(overview.relock)
        assertEquals(RecentsCoverMachine.HOLDING_RECHECK_MS, overview.watchdogInMs)
    }

    @Test
    fun recentsClass_holdsEvenWithoutViewIds() {
        val effect = RecentsCoverMachine.step(RecentsCoverMachine.State(), input(RecentsCoverMachine.Kind.RECENTS_CLASS, now = 0L))
        assertEquals(RecentsCoverMachine.Phase.HOLDING, effect.state.phase)
        assertTrue(effect.relock)
        assertTrue(effect.state.classConfirmed)
        val still = RecentsCoverMachine.step(
            effect.state,
            nodes(visible = false, now = 5_000L, classIsRecents = true),
        )
        assertEquals(RecentsCoverMachine.Phase.HOLDING, still.state.phase)
        assertTrue(still.coverVisible)
    }

    @Test
    fun clearAllThenOpenRecentsAgain_coversTheSecondTime() {
        val first = RecentsCoverMachine.step(
            RecentsCoverMachine.State(),
            input(RecentsCoverMachine.Kind.RECENTS_CLASS, now = 0L, stricter = false),
        )
        assertEquals(RecentsCoverMachine.Phase.HOLDING, first.state.phase)
        assertTrue(first.coverVisible)
        val left = RecentsCoverMachine.step(first.state, nonHost(notes, now = 1_000L))
        assertEquals(RecentsCoverMachine.Phase.IDLE, left.state.phase)
        assertFalse(left.coverVisible)
        val second = RecentsCoverMachine.step(
            left.state,
            input(RecentsCoverMachine.Kind.RECENTS_CLASS, now = 2_000L, stricter = false),
        )
        assertEquals(RecentsCoverMachine.Phase.HOLDING, second.state.phase)
        assertTrue(second.coverVisible)
        assertTrue(second.relock)
    }

    @Test
    fun differentNonLockedApp_dropsTheCoverImmediately() {
        val probing = RecentsCoverMachine.step(RecentsCoverMachine.State(), left(stricter = true, now = 0L)).state
        val maps = RecentsCoverMachine.step(probing, nonHost("com.example.maps", now = 50L))
        assertEquals(RecentsCoverMachine.Phase.IDLE, maps.state.phase)
        assertFalse(maps.coverVisible)
        assertFalse(maps.lockForegroundIfNeeded)
    }

    @Test
    fun sameAppDuringProbe_doesNotEndIt() {
        val probing = RecentsCoverMachine.step(RecentsCoverMachine.State(), left(stricter = true, now = 0L)).state
        val bounce = RecentsCoverMachine.step(probing, nonHost(notes, now = 40L))
        assertEquals(RecentsCoverMachine.Phase.PROBING, bounce.state.phase)
        assertTrue(bounce.coverVisible)
    }

    @Test
    fun probeCap_dropsTheCoverWhenOverviewWasNeverConfirmed() {
        val probing = RecentsCoverMachine.step(RecentsCoverMachine.State(), left(stricter = true, now = 0L)).state
        val unread = RecentsCoverMachine.step(
            probing,
            nodes(visible = null, now = RecentsCoverMachine.PROBE_CAP_MS),
        )
        assertEquals(RecentsCoverMachine.Phase.IDLE, unread.state.phase)
        assertFalse(unread.coverVisible)
        val forced = RecentsCoverMachine.step(
            probing,
            input(RecentsCoverMachine.Kind.WATCHDOG, now = RecentsCoverMachine.PROBE_CAP_MS),
        )
        assertEquals(RecentsCoverMachine.Phase.IDLE, forced.state.phase)
        assertTrue(forced.lockForegroundIfNeeded)
    }

    @Test
    fun holdingWatchdog_doesNotDropAConfirmedOverviewByItself() {
        val holding = RecentsCoverMachine.step(RecentsCoverMachine.State(), input(RecentsCoverMachine.Kind.RECENTS_CLASS)).state
        val beat = RecentsCoverMachine.step(
            holding,
            input(RecentsCoverMachine.Kind.WATCHDOG, now = RecentsCoverMachine.HOLDING_RECHECK_MS),
        )
        assertEquals(RecentsCoverMachine.Phase.HOLDING, beat.state.phase)
        assertTrue(beat.coverVisible)
        assertEquals(RecentsCoverMachine.HOLDING_RECHECK_MS, beat.watchdogInMs)
    }

    @Test
    fun holdingNegativeReadingAfterGrace_dropsTheCoverWhenTheClassIsNotRecents() {
        val holding = RecentsCoverMachine.step(
            RecentsCoverMachine.State(),
            nodes(visible = true, now = 0L),
        ).state
        val early = RecentsCoverMachine.step(holding, nodes(visible = false, now = 100L))
        assertEquals(RecentsCoverMachine.Phase.HOLDING, early.state.phase)
        val later = RecentsCoverMachine.step(
            holding,
            nodes(visible = false, now = RecentsCoverMachine.HOLD_GRACE_MS, classIsRecents = false),
        )
        assertEquals(RecentsCoverMachine.Phase.IDLE, later.state.phase)
        assertFalse(later.coverVisible)
    }

    @Test
    fun disabled_clearsWhateverWasUp() {
        val probing = RecentsCoverMachine.step(RecentsCoverMachine.State(), left(stricter = true)).state
        val off = RecentsCoverMachine.step(probing, input(RecentsCoverMachine.Kind.DISABLED))
        assertEquals(RecentsCoverMachine.Phase.IDLE, off.state.phase)
        assertFalse(off.coverVisible)
        assertNull(off.watchdogInMs)
        assertFalse(off.scheduleBurst)
    }

    @Test
    fun ownPackageAndStatusBar_areNotAStricterDeparture() {
        assertFalse(
            RecentsCoverMachine.leavingUnlockedLockedApp(
                stricter = true,
                hideEnabled = true,
                foregroundPkg = notes,
                foregroundIsLocked = true,
                foregroundIsUnlocked = true,
                newPkg = own,
                newClass = "android.widget.FrameLayout",
                ownPkg = own,
            ),
        )
        assertFalse(
            RecentsCoverMachine.leavingUnlockedLockedApp(
                stricter = true,
                hideEnabled = true,
                foregroundPkg = notes,
                foregroundIsLocked = true,
                foregroundIsUnlocked = true,
                newPkg = "com.android.systemui",
                newClass = "com.android.systemui.statusbar.phone.StatusBar",
                ownPkg = own,
            ),
        )
        assertFalse(
            RecentsCoverMachine.leavingUnlockedLockedApp(
                stricter = true,
                hideEnabled = false,
                foregroundPkg = notes,
                foregroundIsLocked = true,
                foregroundIsUnlocked = true,
                newPkg = launcher,
                newClass = launcherClass,
                ownPkg = own,
            ),
        )
        assertFalse(
            RecentsCoverMachine.leavingUnlockedLockedApp(
                stricter = true,
                hideEnabled = true,
                foregroundPkg = notes,
                foregroundIsLocked = true,
                foregroundIsUnlocked = false,
                newPkg = launcher,
                newClass = launcherClass,
                ownPkg = own,
            ),
        )
    }

    @Test
    fun launcherAndRecentsClass_areDeparturesWhenStricterIsOn() {
        assertTrue(
            RecentsCoverMachine.leavingUnlockedLockedApp(
                stricter = true,
                hideEnabled = true,
                foregroundPkg = notes,
                foregroundIsLocked = true,
                foregroundIsUnlocked = true,
                newPkg = launcher,
                newClass = launcherClass,
                ownPkg = own,
            ),
        )
        assertTrue(
            RecentsCoverMachine.leavingUnlockedLockedApp(
                stricter = true,
                hideEnabled = true,
                foregroundPkg = notes,
                foregroundIsLocked = true,
                foregroundIsUnlocked = true,
                newPkg = "com.android.systemui",
                newClass = recentsClass,
                ownPkg = own,
            ),
        )
    }

    @Test
    fun stricterStored_isOffByDefault_andInactiveWhileHideIsOff() {
        assertFalse(RecentsGuard.stricterOrDefault(null))
        assertTrue(RecentsGuard.stricterOrDefault(true))
        assertFalse(RecentsGuard.stricterActive(hideEnabled = false, stricterStored = true))
        assertTrue(RecentsGuard.stricterActive(hideEnabled = true, stricterStored = true))
        assertFalse(RecentsGuard.stricterActive(hideEnabled = true, stricterStored = false))
        assertFalse(RecentsGuard.fingerprintOrDefault(null))
        assertFalse(RecentsGuard.fingerprintActive(hideEnabled = false, stored = true))
        assertTrue(RecentsGuard.fingerprintActive(hideEnabled = true, stored = true))
        assertFalse(RecentsGuard.fingerprintActive(hideEnabled = true, stored = false))
    }

    private fun left(stricter: Boolean, now: Long = 0L) = input(
        RecentsCoverMachine.Kind.LEFT_UNLOCKED_LOCKED_FOR_HOST,
        now = now,
        stricter = stricter,
    ).copy(foregroundPkg = notes)

    private fun nodes(visible: Boolean?, now: Long, classIsRecents: Boolean = false) =
        input(RecentsCoverMachine.Kind.NODES, now = now).copy(
            nodesVisible = visible,
            currentClassIsRecents = classIsRecents,
        )

    private fun nonHost(pkg: String, now: Long) =
        input(RecentsCoverMachine.Kind.NON_HOST_APP, now = now).copy(nonHostPkg = pkg)

    private fun input(
        kind: RecentsCoverMachine.Kind,
        now: Long = 0L,
        stricter: Boolean = true,
    ) = RecentsCoverMachine.Input(
        nowMs = now,
        kind = kind,
        stricter = stricter,
        hideEnabled = true,
    )
}
