package com.achillesrogan.applock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UninstallDisclaimerTest {

    @Test
    fun staysExpandedUntilSeen_andCollapsesOnceSetupIsDone() {
        assertFalse(UninstallDisclaimer.collapsed(setupComplete = false, seen = false))
        assertTrue(UninstallDisclaimer.collapsed(setupComplete = false, seen = true))
        assertTrue(UninstallDisclaimer.collapsed(setupComplete = true, seen = false))
        assertTrue(UninstallDisclaimer.collapsed(setupComplete = true, seen = true))
    }
}

class CreditTest {

    @Test
    fun creditUrl_isTheGitHubProfileAndNothingElse() {
        assertEquals("https://github.com/achillesrogan", Credit.URL)
        assertFalse(Credit.URL.contains("@"))
        assertFalse(Credit.URL.contains("mailto:", ignoreCase = true))
    }
}
