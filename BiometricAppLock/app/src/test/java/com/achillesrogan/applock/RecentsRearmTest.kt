package com.achillesrogan.applock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner sequence on 1.7: the cover shows once, Clear all throws the cover off for
 * the rest of the process, then relaunching and unlocking a locked app and opening
 * Recents again shows the real thumbnails. The failure must not survive that.
 */
class RecentsRearmTest {

    @Test
    fun clearAll_relaunch_unlock_thenRecentsCoversAgain() {
        var flags = RecentsRearm.Flags()
        flags = RecentsRearm.onOverviewOpened(flags)
        assertTrue(RecentsRearm.shouldAttempt(flags, hideEnabled = true, overviewDetected = true))

        flags = flags.copy(burstAtMs = 1_000L, scanAtMs = 1_000L)
        flags = RecentsRearm.onCoverFailed(flags)
        assertFalse(RecentsRearm.shouldAttempt(flags, hideEnabled = true, overviewDetected = true))
        assertFalse(RecentsCoverSession.coverAllowed(retired = true))

        flags = RecentsRearm.onOverviewClosed(flags)
        flags = RecentsRearm.rearm(flags)
        assertEquals(-1L, flags.burstAtMs)
        assertEquals(-1L, flags.scanAtMs)
        assertTrue(RecentsScanGate.allow(nowMs = 2_000L, lastScanMs = flags.scanAtMs))
        assertTrue(RecentsCoverSession.coverAllowed(retired = false))

        flags = RecentsRearm.onOverviewOpened(flags)
        assertTrue(flags.overviewOpen)
        assertFalse(flags.failedThisOverview)
        assertTrue(RecentsRearm.shouldAttempt(flags, hideEnabled = true, overviewDetected = true))
    }

    @Test
    fun screenOffOn_andLockUnlock_clearAStuckFailure() {
        var flags = RecentsRearm.onCoverFailed(RecentsRearm.onOverviewOpened(RecentsRearm.Flags()))
        flags = RecentsRearm.rearm(flags)
        assertTrue(RecentsRearm.shouldAttempt(flags, hideEnabled = true, overviewDetected = true))

        flags = RecentsRearm.onCoverFailed(RecentsRearm.onOverviewOpened(flags))
        flags = RecentsRearm.onOverviewClosed(flags)
        assertFalse(flags.failedThisOverview)
        assertTrue(RecentsRearm.shouldAttempt(flags, hideEnabled = true, overviewDetected = true))
    }

    @Test
    fun aNewOverview_resetsTheThrottleEvenIfThePreviousBurstJustRan() {
        val stuck = RecentsRearm.Flags(burstAtMs = 5_000L, scanAtMs = 5_000L, overviewOpen = true)
        assertFalse(RecentsScanGate.allow(nowMs = 5_100L, lastScanMs = stuck.scanAtMs))
        val opened = RecentsRearm.onOverviewOpened(stuck.copy(failedThisOverview = true))
        assertEquals(-1L, opened.scanAtMs)
        assertTrue(RecentsScanGate.allow(nowMs = 5_100L, lastScanMs = opened.scanAtMs))
    }
}

class RecentsFingerprintTest {

    @Test
    fun aGrant_lastsForThatRecentsVisitOnly_andGraceIsOff() {
        assertEquals(0L, RecentsFingerprint.GRACE_MS)
        var session = RecentsFingerprint.Session()
        session = RecentsFingerprint.begin(session)
        assertFalse(RecentsFingerprint.isGranted(session))

        session = RecentsFingerprint.grant(session)
        assertTrue(RecentsFingerprint.isGranted(session))
        assertTrue(RecentsFingerprint.stillGrantedAfter(session, elapsedMs = 0L))
        assertFalse(RecentsFingerprint.stillGrantedAfter(session, elapsedMs = 1L))

        val next = RecentsFingerprint.begin(session)
        assertFalse(RecentsFingerprint.isGranted(next))
        assertFalse(RecentsFingerprint.stillGrantedAfter(next, elapsedMs = 0L))
    }

    @Test
    fun requireFingerprint_isOffByDefault_andInactiveWhileHideIsOff() {
        assertFalse(RecentsGuard.fingerprintOrDefault(null))
        assertTrue(RecentsGuard.fingerprintOrDefault(true))
        assertFalse(RecentsGuard.fingerprintActive(hideEnabled = false, stored = true))
        assertFalse(RecentsGuard.fingerprintActive(hideEnabled = true, stored = false))
        assertTrue(RecentsGuard.fingerprintActive(hideEnabled = true, stored = true))
    }
}
