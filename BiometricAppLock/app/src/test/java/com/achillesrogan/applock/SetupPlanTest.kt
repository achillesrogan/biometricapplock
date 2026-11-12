package com.achillesrogan.applock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupPlanTest {

    private fun snap(
        sdk: Int = 34,
        fingerprint: Boolean = false,
        accessibility: Boolean = false,
        restrictedMode: Int? = SetupPlan.MODE_IGNORED,
        unknownInstaller: Boolean = true,
        admin: Boolean = false,
        battery: Boolean = false,
        skipped: Set<SetupPlan.StepId> = emptySet(),
        autoBlocker: AutoBlocker.Reading = AutoBlocker.Reading.ABSENT,
    ) = SetupPlan.Snapshot(
        sdk, fingerprint, accessibility, restrictedMode, unknownInstaller, admin, battery, skipped, autoBlocker,
    )

    private fun blockingAutoBlocker() = AutoBlocker.Reading(
        present = true,
        oneUiVersion = 70_000,
        main = AutoBlocker.SwitchState.ON,
        unauthorizedApps = AutoBlocker.SwitchState.ON,
        usbCommands = AutoBlocker.SwitchState.ON,
        maximumRestrictions = AutoBlocker.SwitchState.OFF,
    )

    private fun ids(snapshot: SetupPlan.Snapshot) = SetupPlan.steps(snapshot).map { it.id }

    @Test
    fun sideloadOnAndroid13_putsRestrictedSettingsBeforeAccessibility() {
        val steps = SetupPlan.steps(snap())
        assertEquals(
            listOf(
                SetupPlan.StepId.FINGERPRINT,
                SetupPlan.StepId.RESTRICTED_SETTINGS,
                SetupPlan.StepId.ACCESSIBILITY,
                SetupPlan.StepId.DEVICE_ADMIN,
                SetupPlan.StepId.BATTERY,
            ),
            ids(snap()),
        )
        assertEquals(SetupPlan.StepId.FINGERPRINT, SetupPlan.nextIncomplete(steps)?.id)
        assertFalse(SetupPlan.requiredComplete(steps))
        assertTrue(steps.first { it.id == SetupPlan.StepId.RESTRICTED_SETTINGS }.requirement == SetupPlan.Requirement.REQUIRED)
        assertFalse(steps.first { it.id == SetupPlan.StepId.RESTRICTED_SETTINGS }.done)
    }

    @Test
    fun android12_hidesRestrictedSettings() {
        assertFalse(ids(snap(sdk = 32)).contains(SetupPlan.StepId.RESTRICTED_SETTINGS))
        val ready = SetupPlan.steps(snap(sdk = 32, fingerprint = true))
        assertEquals(SetupPlan.StepId.ACCESSIBILITY, SetupPlan.nextIncomplete(ready)?.id)
    }

    @Test
    fun allowedOrDefaultMode_doesNotBlockAccessibility() {
        for (mode in listOf(SetupPlan.MODE_ALLOWED, SetupPlan.MODE_DEFAULT)) {
            val steps = SetupPlan.steps(snap(fingerprint = true, restrictedMode = mode))
            assertTrue(steps.first { it.id == SetupPlan.StepId.RESTRICTED_SETTINGS }.done)
            assertEquals(SetupPlan.StepId.ACCESSIBILITY, SetupPlan.nextIncomplete(steps)?.id)
        }
    }

    @Test
    fun ignoredOrErroredMode_blocksUntilAppInfo() {
        for (mode in listOf(SetupPlan.MODE_IGNORED, SetupPlan.MODE_ERRORED)) {
            val steps = SetupPlan.steps(snap(fingerprint = true, restrictedMode = mode))
            assertEquals(SetupPlan.StepId.RESTRICTED_SETTINGS, SetupPlan.nextIncomplete(steps)?.id)
        }
    }

    @Test
    fun enabledAccessibility_clearsTheRestrictedBlock() {
        val steps = SetupPlan.steps(
            snap(fingerprint = true, accessibility = true, restrictedMode = SetupPlan.MODE_IGNORED),
        )
        assertTrue(steps.first { it.id == SetupPlan.StepId.RESTRICTED_SETTINGS }.done)
        assertTrue(steps.first { it.id == SetupPlan.StepId.ACCESSIBILITY }.done)
        assertEquals(SetupPlan.StepId.DEVICE_ADMIN, SetupPlan.nextIncomplete(steps)?.id)
        assertTrue(SetupPlan.requiredComplete(steps))
    }

    @Test
    fun unknownAppOp_blocksOnlyAnUnknownInstaller() {
        val sideload = SetupPlan.steps(snap(fingerprint = true, restrictedMode = null, unknownInstaller = true))
        assertEquals(SetupPlan.StepId.RESTRICTED_SETTINGS, SetupPlan.nextIncomplete(sideload)?.id)
        val store = SetupPlan.steps(snap(fingerprint = true, restrictedMode = null, unknownInstaller = false))
        assertEquals(SetupPlan.StepId.ACCESSIBILITY, SetupPlan.nextIncomplete(store)?.id)
    }

    @Test
    fun autoAdvance_opensTheNextMissingStepOnlyAfterTheLaunchedOneIsDone() {
        val blocked = SetupPlan.steps(snap(fingerprint = true, restrictedMode = SetupPlan.MODE_IGNORED))
        assertNull(SetupPlan.autoAdvanceTarget(blocked, SetupPlan.StepId.RESTRICTED_SETTINGS))

        val allowed = SetupPlan.steps(snap(fingerprint = true, restrictedMode = SetupPlan.MODE_ALLOWED))
        assertEquals(
            SetupPlan.StepId.ACCESSIBILITY,
            SetupPlan.autoAdvanceTarget(allowed, SetupPlan.StepId.RESTRICTED_SETTINGS),
        )
        assertEquals(
            SetupPlan.StepId.ACCESSIBILITY,
            SetupPlan.autoAdvanceTarget(allowed, SetupPlan.StepId.FINGERPRINT),
        )
    }

    @Test
    fun autoAdvance_continuesThroughOptionalSteps() {
        val steps = SetupPlan.steps(snap(fingerprint = true, accessibility = true, restrictedMode = SetupPlan.MODE_ALLOWED))
        assertEquals(
            SetupPlan.StepId.DEVICE_ADMIN,
            SetupPlan.autoAdvanceTarget(steps, SetupPlan.StepId.ACCESSIBILITY),
        )
        val adminOn = SetupPlan.steps(
            snap(fingerprint = true, accessibility = true, restrictedMode = SetupPlan.MODE_ALLOWED, admin = true),
        )
        assertEquals(SetupPlan.StepId.BATTERY, SetupPlan.autoAdvanceTarget(adminOn, SetupPlan.StepId.DEVICE_ADMIN))
    }

    @Test
    fun skip_countsAsDoneForTheWalkthrough_andDoesNotSatisfyRequiredSteps() {
        val skipped = SetupPlan.steps(
            snap(
                fingerprint = true,
                accessibility = true,
                restrictedMode = SetupPlan.MODE_ALLOWED,
                skipped = setOf(SetupPlan.StepId.DEVICE_ADMIN),
            ),
        )
        assertTrue(skipped.first { it.id == SetupPlan.StepId.DEVICE_ADMIN }.done)
        assertEquals(SetupPlan.StepId.BATTERY, SetupPlan.autoAdvanceTarget(skipped, SetupPlan.StepId.DEVICE_ADMIN))
        assertEquals(SetupPlan.Requirement.OPTIONAL, SetupPlan.requirement(SetupPlan.StepId.DEVICE_ADMIN))
        assertEquals(SetupPlan.Requirement.REQUIRED, SetupPlan.requirement(SetupPlan.StepId.ACCESSIBILITY))
    }

    @Test
    fun finishedChecklist_hasNothingToAdvanceTo() {
        val steps = SetupPlan.steps(
            snap(
                fingerprint = true,
                accessibility = true,
                restrictedMode = SetupPlan.MODE_ALLOWED,
                admin = true,
                battery = true,
            ),
        )
        assertNull(SetupPlan.nextIncomplete(steps))
        assertNull(SetupPlan.autoAdvanceTarget(steps, SetupPlan.StepId.BATTERY))
        assertTrue(SetupPlan.requiredComplete(steps))
    }

    @Test
    fun autoBlocker_isHiddenUnlessTheReadingSaysThePhoneHasIt() {
        assertFalse(ids(snap()).contains(SetupPlan.StepId.AUTO_BLOCKER))
        val shown = ids(snap(autoBlocker = blockingAutoBlocker()))
        assertEquals(
            listOf(
                SetupPlan.StepId.FINGERPRINT,
                SetupPlan.StepId.RESTRICTED_SETTINGS,
                SetupPlan.StepId.ACCESSIBILITY,
                SetupPlan.StepId.AUTO_BLOCKER,
                SetupPlan.StepId.DEVICE_ADMIN,
                SetupPlan.StepId.BATTERY,
            ),
            shown,
        )
    }

    @Test
    fun autoBlocker_isOptionalAndDoesNotBlockRequiredSetup() {
        val steps = SetupPlan.steps(
            snap(
                fingerprint = true,
                accessibility = true,
                restrictedMode = SetupPlan.MODE_ALLOWED,
                autoBlocker = blockingAutoBlocker(),
            ),
        )
        val step = steps.first { it.id == SetupPlan.StepId.AUTO_BLOCKER }
        assertEquals(SetupPlan.Requirement.OPTIONAL, step.requirement)
        assertFalse(step.done)
        assertFalse(step.manualCheck)
        assertTrue(SetupPlan.requiredComplete(steps))
        assertEquals(SetupPlan.StepId.AUTO_BLOCKER, SetupPlan.nextIncomplete(steps)?.id)
    }

    @Test
    fun autoBlocker_manualCheckWhenTheSwitchesCannotBeRead() {
        val unread = AutoBlocker.Reading(
            present = true,
            oneUiVersion = 70_000,
            main = AutoBlocker.SwitchState.UNKNOWN,
            unauthorizedApps = AutoBlocker.SwitchState.UNKNOWN,
            usbCommands = AutoBlocker.SwitchState.UNKNOWN,
            maximumRestrictions = AutoBlocker.SwitchState.UNKNOWN,
        )
        val steps = SetupPlan.steps(snap(autoBlocker = unread))
        val step = steps.first { it.id == SetupPlan.StepId.AUTO_BLOCKER }
        assertFalse(step.done)
        assertTrue(step.manualCheck)
    }

    @Test
    fun autoBlocker_doneWhenTheAffectingSwitchesAreOff_andSkipAdvances() {
        val clear = blockingAutoBlocker().copy(
            unauthorizedApps = AutoBlocker.SwitchState.OFF,
            usbCommands = AutoBlocker.SwitchState.OFF,
        )
        val steps = SetupPlan.steps(
            snap(fingerprint = true, accessibility = true, restrictedMode = SetupPlan.MODE_ALLOWED, autoBlocker = clear),
        )
        assertTrue(steps.first { it.id == SetupPlan.StepId.AUTO_BLOCKER }.done)
        assertEquals(
            SetupPlan.StepId.DEVICE_ADMIN,
            SetupPlan.autoAdvanceTarget(steps, SetupPlan.StepId.ACCESSIBILITY),
        )

        val skipped = SetupPlan.steps(
            snap(
                fingerprint = true,
                accessibility = true,
                restrictedMode = SetupPlan.MODE_ALLOWED,
                skipped = setOf(SetupPlan.StepId.AUTO_BLOCKER),
                autoBlocker = blockingAutoBlocker(),
            ),
        )
        assertTrue(skipped.first { it.id == SetupPlan.StepId.AUTO_BLOCKER }.done)
        assertFalse(skipped.first { it.id == SetupPlan.StepId.AUTO_BLOCKER }.manualCheck)
        assertEquals(
            SetupPlan.StepId.DEVICE_ADMIN,
            SetupPlan.autoAdvanceTarget(skipped, SetupPlan.StepId.AUTO_BLOCKER),
        )
    }

    @Test
    fun autoAdvance_stopsOnAutoBlockerUntilItIsDone() {
        val steps = SetupPlan.steps(
            snap(
                fingerprint = true,
                accessibility = true,
                restrictedMode = SetupPlan.MODE_ALLOWED,
                autoBlocker = blockingAutoBlocker(),
            ),
        )
        assertEquals(
            SetupPlan.StepId.AUTO_BLOCKER,
            SetupPlan.autoAdvanceTarget(steps, SetupPlan.StepId.ACCESSIBILITY),
        )
        assertNull(SetupPlan.autoAdvanceTarget(steps, SetupPlan.StepId.AUTO_BLOCKER))
    }

    @Test
    fun optionalSteps_doNotBlockRequiredCompletion() {
        val steps = SetupPlan.steps(
            snap(fingerprint = true, accessibility = true, restrictedMode = SetupPlan.MODE_DEFAULT),
        )
        assertTrue(SetupPlan.requiredComplete(steps))
        assertFalse(steps.first { it.id == SetupPlan.StepId.BATTERY }.done)
    }
}
