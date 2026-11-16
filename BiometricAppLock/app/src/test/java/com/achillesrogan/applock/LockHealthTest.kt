package com.achillesrogan.applock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockHealthTest {

    @Test
    fun missingPrefsFile_hidesLocksEvenIfMemoryStillHasThem() {
        val stale = setOf("com.example.notes", "com.example.mail")
        assertEquals(emptySet<String>(), LockHealth.visibleLocks(prefsFileExists = false, persisted = stale))
        assertEquals(stale, LockHealth.visibleLocks(prefsFileExists = true, persisted = stale))
    }

    @Test
    fun serviceOff_meansNothingIsEnforcedEvenWhenTheSwitchIsOn() {
        val locked = setOf("com.example.notes")
        assertFalse(
            LockHealth.enforces(
                serviceEnabled = false,
                locked = locked,
                pkg = "com.example.notes",
                sessionUnlocked = false,
            ),
        )
        assertTrue(
            LockHealth.enforces(
                serviceEnabled = true,
                locked = locked,
                pkg = "com.example.notes",
                sessionUnlocked = false,
            ),
        )
        assertFalse(
            LockHealth.enforces(
                serviceEnabled = true,
                locked = locked,
                pkg = "com.example.notes",
                sessionUnlocked = true,
            ),
        )
    }

    @Test
    fun banner_prefersAStoppedServiceOverABrokenKey() {
        assertEquals(LockHealth.Problem.SERVICE_OFF, LockHealth.problem(serviceEnabled = false, keyBroken = true))
        assertEquals(LockHealth.Problem.KEY_BROKEN, LockHealth.problem(serviceEnabled = true, keyBroken = true))
        assertEquals(LockHealth.Problem.NONE, LockHealth.problem(serviceEnabled = true, keyBroken = false))
    }
}

class RecentsCoverSessionTest {

    @Test
    fun aFailedCover_staysOffWhileLockingStaysOn() {
        assertTrue(RecentsCoverSession.coverAllowed(retired = false))
        assertFalse(RecentsCoverSession.coverAllowed(retired = true))
        val locked = setOf("com.example.notes")
        assertTrue(
            LockHealth.enforces(
                serviceEnabled = true,
                locked = locked,
                pkg = "com.example.notes",
                sessionUnlocked = false,
            ),
        )
    }

    @Test
    fun scans_areThrottledSoWindowChangesCannotFloodTheMainThread() {
        assertTrue(RecentsScanGate.allow(nowMs = 1_000L, lastScanMs = -1L))
        assertFalse(RecentsScanGate.allow(nowMs = 1_100L, lastScanMs = 1_000L))
        assertTrue(RecentsScanGate.allow(nowMs = 1_000L + RecentsScanGate.MIN_INTERVAL_MS, lastScanMs = 1_000L))
    }
}
