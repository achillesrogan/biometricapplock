package com.achillesrogan.applock

/**
 * Samsung Auto Blocker, as it affects this app.
 *
 * Auto Blocker exists on Galaxy phones with One UI 6.0 and newer
 * (Settings → Security and privacy → Auto Blocker). Samsung does not publish
 * an API for the switches. Callers pass in a One UI version code and switch
 * readings; a switch is only ON or OFF when the reading was exactly that.
 * Anything else stays [SwitchState.UNKNOWN], and the setup step says to check
 * the screen instead of inventing a state.
 *
 * What matters here, from Samsung's own description:
 * - Block apps from unauthorized sources stops an APK install or update,
 *   including adb install. Galaxy Store and Play Store installs are not blocked.
 *   An already installed copy keeps locking apps either way.
 * - Block commands by USB cable stops adb shell setup commands. Setup done on
 *   the phone does not use those commands.
 * - Maximum restrictions, added in One UI 6.1.1 and off unless the user turns
 *   it on, blocks activating a device administrator. Uninstall protection cannot
 *   be turned on until that one switch is off. App locking still works.
 *   Message links, 2G, Wi-Fi, shared albums, and photo location under Maximum
 *   restrictions do not affect this app. Malware-image blocking, app scans, and
 *   blocking unofficial system updates do not either.
 *
 * The step stays optional. Leaving Auto Blocker on is a valid choice.
 */
object AutoBlocker {

    /** One UI 6.0. `ro.build.version.oneui`, or `SEM_PLATFORM_INT - 90000`. */
    const val ONE_UI_6 = 60_000

    /** One UI 6.1.1, when Maximum restrictions was added. */
    const val ONE_UI_6_1_1 = 60_101

    /** Samsung's SEP platform code is the One UI code plus this offset. */
    const val SEM_PLATFORM_OFFSET = 90_000

    const val RAMPART_PACKAGE = "com.samsung.android.rampart"

    enum class SwitchState { ON, OFF, UNKNOWN }

    /**
     * Maximum restrictions, separate from "the key was missing" so an older
     * One UI is not described as a switch the user should go looking for.
     */
    enum class Maximum {
        ON,
        OFF,
        UNREAD,
        NOT_ON_THIS_VERSION,
    }

    enum class DetailLine {
        UNREAD_ALL,
        MAIN_ON,
        MAIN_OFF_CONFLICT,
        MAIN_UNREAD,
        SIDELOAD_ON,
        SIDELOAD_OFF,
        SIDELOAD_UNREAD,
        USB_ON,
        USB_OFF,
        USB_UNREAD,
        MAX_ON,
        MAX_OFF,
        MAX_UNREAD,
        MAX_ABSENT,
        LEAVE_REST,
        WHERE,
    }

    data class Reading(
        val present: Boolean,
        /** Null when the One UI version could not be read. */
        val oneUiVersion: Int?,
        val main: SwitchState,
        val unauthorizedApps: SwitchState,
        val usbCommands: SwitchState,
        val maximumRestrictions: SwitchState,
    ) {
        companion object {
            val ABSENT = Reading(
                present = false,
                oneUiVersion = null,
                main = SwitchState.UNKNOWN,
                unauthorizedApps = SwitchState.UNKNOWN,
                usbCommands = SwitchState.UNKNOWN,
                maximumRestrictions = SwitchState.UNKNOWN,
            )
        }
    }

    /**
     * `ro.build.version.oneui` is already the One UI code (60000 = 6.0).
     * Values below One UI 1.0 are ignored.
     */
    fun oneUiFromProperty(raw: String?): Int? {
        val value = raw?.trim()?.toIntOrNull() ?: return null
        return value.takeIf { it >= 10_000 }
    }

    /** `Build.VERSION.SEM_PLATFORM_INT` minus [SEM_PLATFORM_OFFSET]. */
    fun oneUiFromSemPlatform(semPlatformInt: Int): Int? {
        val version = semPlatformInt - SEM_PLATFORM_OFFSET
        return version.takeIf { it >= 10_000 }
    }

    /**
     * True only for a Samsung device that is on One UI 6 or newer.
     * When the version cannot be read, the Auto Blocker package is the signal
     * that the feature is installed. A known older One UI does not show the
     * step, even if that package happens to be present. Other manufacturers
     * never do.
     */
    fun present(manufacturer: String, oneUiVersion: Int?, rampartInstalled: Boolean): Boolean {
        if (!manufacturer.equals("samsung", ignoreCase = true)) return false
        if (oneUiVersion != null) return oneUiVersion >= ONE_UI_6
        return rampartInstalled
    }

    /**
     * First non-blank reading wins. Only "0" and "1" are states.
     * A blank entry is a missing key, so a later alias can still be used.
     */
    fun switchFromValues(values: List<String?>): SwitchState {
        val raw = values.firstOrNull { !it.isNullOrBlank() }?.trim() ?: return SwitchState.UNKNOWN
        return when (raw) {
            "0" -> SwitchState.OFF
            "1" -> SwitchState.ON
            else -> SwitchState.UNKNOWN
        }
    }

    fun maximum(reading: Reading): Maximum = when (reading.maximumRestrictions) {
        SwitchState.ON -> Maximum.ON
        SwitchState.OFF -> Maximum.OFF
        SwitchState.UNKNOWN ->
            if (reading.oneUiVersion != null && reading.oneUiVersion < ONE_UI_6_1_1) {
                Maximum.NOT_ON_THIS_VERSION
            } else {
                Maximum.UNREAD
            }
    }

    /**
     * Nothing that affects this app is on, and no affecting switch is unread.
     * The main switch being off is enough unless a sub-switch still reads as on.
     * The main switch being on is fine when the three affecting switches are off:
     * the rest of Auto Blocker can stay on.
     */
    fun clear(reading: Reading): Boolean {
        if (!reading.present) return true
        val max = maximum(reading)
        if (reading.main == SwitchState.OFF &&
            reading.unauthorizedApps != SwitchState.ON &&
            reading.usbCommands != SwitchState.ON &&
            max != Maximum.ON
        ) {
            return true
        }
        val maxKnownOff = max == Maximum.OFF || max == Maximum.NOT_ON_THIS_VERSION
        return reading.unauthorizedApps == SwitchState.OFF &&
            reading.usbCommands == SwitchState.OFF &&
            maxKnownOff
    }

    /**
     * The step is a manual check when it is not already clear and any switch
     * that would decide that could not be read. A switch that reads as on is
     * not a guess, but one unread affecting switch still makes the whole step
     * a manual check so the UI does not pretend the screen was measured.
     */
    fun needsManualCheck(reading: Reading): Boolean {
        if (!reading.present || clear(reading)) return false
        val max = maximum(reading)
        return reading.main == SwitchState.UNKNOWN ||
            reading.unauthorizedApps == SwitchState.UNKNOWN ||
            reading.usbCommands == SwitchState.UNKNOWN ||
            max == Maximum.UNREAD
    }

    /** Paragraphs for the current step. Empty when the step is done or absent. */
    fun detail(reading: Reading): List<DetailLine> {
        if (!reading.present || clear(reading)) return emptyList()
        val max = maximum(reading)
        val nothingRead = reading.main == SwitchState.UNKNOWN &&
            reading.unauthorizedApps == SwitchState.UNKNOWN &&
            reading.usbCommands == SwitchState.UNKNOWN &&
            max == Maximum.UNREAD
        if (nothingRead) return listOf(DetailLine.UNREAD_ALL, DetailLine.WHERE)

        val lines = mutableListOf<DetailLine>()
        when (reading.main) {
            SwitchState.ON -> lines += DetailLine.MAIN_ON
            SwitchState.OFF -> lines += DetailLine.MAIN_OFF_CONFLICT
            SwitchState.UNKNOWN -> lines += DetailLine.MAIN_UNREAD
        }
        when (reading.unauthorizedApps) {
            SwitchState.ON -> lines += DetailLine.SIDELOAD_ON
            SwitchState.OFF -> lines += DetailLine.SIDELOAD_OFF
            SwitchState.UNKNOWN -> lines += DetailLine.SIDELOAD_UNREAD
        }
        when (reading.usbCommands) {
            SwitchState.ON -> lines += DetailLine.USB_ON
            SwitchState.OFF -> lines += DetailLine.USB_OFF
            SwitchState.UNKNOWN -> lines += DetailLine.USB_UNREAD
        }
        when (max) {
            Maximum.ON -> lines += DetailLine.MAX_ON
            Maximum.OFF -> lines += DetailLine.MAX_OFF
            Maximum.UNREAD -> lines += DetailLine.MAX_UNREAD
            Maximum.NOT_ON_THIS_VERSION -> lines += DetailLine.MAX_ABSENT
        }
        lines += DetailLine.LEAVE_REST
        lines += DetailLine.WHERE
        return lines
    }
}
