package com.achillesrogan.applock

/**
 * Order of the setup checklist, and which step to open next.
 *
 * Restricted-settings modes use the same integers as [android.app.AppOpsManager]:
 * allowed 0, ignored 1, errored 2, default 3. This class stays free of Android
 * types so it can be tested on the JVM.
 */
object SetupPlan {

    const val MODE_ALLOWED = 0
    const val MODE_IGNORED = 1
    const val MODE_ERRORED = 2
    const val MODE_DEFAULT = 3

    /** Android 13, where sideloaded accessibility switches can be blocked. */
    const val RESTRICTED_SETTINGS_SDK = 33

    enum class StepId {
        FINGERPRINT,
        RESTRICTED_SETTINGS,
        ACCESSIBILITY,
        AUTO_BLOCKER,
        DEVICE_ADMIN,
        BATTERY,
    }

    enum class Requirement { REQUIRED, OPTIONAL }

    data class Snapshot(
        val sdkInt: Int,
        val fingerprintReady: Boolean,
        val accessibilityEnabled: Boolean,
        /** Null when the app-op could not be read. */
        val restrictedSettingsMode: Int?,
        /** True when Android has no installer package, typical of an adb sideload. */
        val unknownInstaller: Boolean,
        val deviceAdminActive: Boolean,
        val batteryUnrestricted: Boolean,
        val skipped: Set<StepId> = emptySet(),
        val autoBlocker: AutoBlocker.Reading = AutoBlocker.Reading.ABSENT,
    )

    data class Step(
        val id: StepId,
        val requirement: Requirement,
        val done: Boolean,
        /** True when the step is showing and its on/off state could not be read. */
        val manualCheck: Boolean = false,
    )

    fun requirement(id: StepId): Requirement = when (id) {
        StepId.FINGERPRINT, StepId.RESTRICTED_SETTINGS, StepId.ACCESSIBILITY -> Requirement.REQUIRED
        StepId.AUTO_BLOCKER, StepId.DEVICE_ADMIN, StepId.BATTERY -> Requirement.OPTIONAL
    }

    /**
     * True when Android 13+ is blocking this sideloaded app's accessibility switch
     * until the user allows restricted settings. An enabled service is not blocked.
     */
    fun restrictedSettingsBlocked(snapshot: Snapshot): Boolean {
        if (snapshot.sdkInt < RESTRICTED_SETTINGS_SDK || snapshot.accessibilityEnabled) return false
        val mode = snapshot.restrictedSettingsMode
        if (mode == null) return snapshot.unknownInstaller
        return mode == MODE_IGNORED || mode == MODE_ERRORED
    }

    fun steps(snapshot: Snapshot): List<Step> {
        val restrictedBlocked = restrictedSettingsBlocked(snapshot)
        val all = listOf(
            Step(StepId.FINGERPRINT, Requirement.REQUIRED, snapshot.fingerprintReady),
            Step(
                StepId.RESTRICTED_SETTINGS,
                Requirement.REQUIRED,
                done = !restrictedBlocked,
            ),
            Step(StepId.ACCESSIBILITY, Requirement.REQUIRED, snapshot.accessibilityEnabled),
            Step(
                StepId.AUTO_BLOCKER,
                Requirement.OPTIONAL,
                done = AutoBlocker.clear(snapshot.autoBlocker) || StepId.AUTO_BLOCKER in snapshot.skipped,
                manualCheck = AutoBlocker.needsManualCheck(snapshot.autoBlocker) &&
                    StepId.AUTO_BLOCKER !in snapshot.skipped,
            ),
            Step(
                StepId.DEVICE_ADMIN,
                Requirement.OPTIONAL,
                snapshot.deviceAdminActive || StepId.DEVICE_ADMIN in snapshot.skipped,
            ),
            Step(
                StepId.BATTERY,
                Requirement.OPTIONAL,
                snapshot.batteryUnrestricted || StepId.BATTERY in snapshot.skipped,
            ),
        )
        return all.filter { step ->
            when (step.id) {
                StepId.RESTRICTED_SETTINGS -> snapshot.sdkInt >= RESTRICTED_SETTINGS_SDK
                StepId.AUTO_BLOCKER -> snapshot.autoBlocker.present
                else -> true
            }
        }
    }

    fun nextIncomplete(steps: List<Step>): Step? = steps.firstOrNull { !it.done }

    fun requiredComplete(steps: List<Step>): Boolean =
        steps.none { it.requirement == Requirement.REQUIRED && !it.done }

    /**
     * Step to open after the user returns from [launched].
     * Opens the next unfinished step only when [launched] is now done.
     * A step they backed out of is not opened again on its own.
     */
    fun autoAdvanceTarget(steps: List<Step>, launched: StepId): StepId? {
        val index = steps.indexOfFirst { it.id == launched }
        if (index < 0 || !steps[index].done) return null
        return steps.drop(index + 1).firstOrNull { !it.done }?.id
    }
}
