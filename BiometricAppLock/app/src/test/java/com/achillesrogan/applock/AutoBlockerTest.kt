package com.achillesrogan.applock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBlockerTest {

    @Test
    fun oneUiVersion_decodesThePropertyAndTheSemPlatformField() {
        assertEquals(60_000, AutoBlocker.oneUiFromProperty("60000"))
        assertEquals(60_101, AutoBlocker.oneUiFromProperty(" 60101 "))
        assertEquals(null, AutoBlocker.oneUiFromProperty(null))
        assertEquals(null, AutoBlocker.oneUiFromProperty(""))
        assertEquals(null, AutoBlocker.oneUiFromProperty("6.0"))
        assertEquals(null, AutoBlocker.oneUiFromProperty("0"))

        // Android 9 sample: SEM_PLATFORM_INT 100100 is One UI 1.1, not One UI 6.
        assertEquals(10_100, AutoBlocker.oneUiFromSemPlatform(100_100))
        assertEquals(60_000, AutoBlocker.oneUiFromSemPlatform(150_000))
        assertEquals(60_101, AutoBlocker.oneUiFromSemPlatform(150_101))
        assertEquals(null, AutoBlocker.oneUiFromSemPlatform(80_000))
    }

    @Test
    fun present_requiresSamsungAndOneUi6_orTheRampartPackageWhenVersionIsUnknown() {
        assertTrue(AutoBlocker.present("samsung", 60_000, rampartInstalled = false))
        assertTrue(AutoBlocker.present("Samsung", 70_000, rampartInstalled = false))
        assertFalse(AutoBlocker.present("samsung", 50_100, rampartInstalled = true))
        assertFalse(AutoBlocker.present("samsung", 59_999, rampartInstalled = true))
        assertTrue(AutoBlocker.present("samsung", null, rampartInstalled = true))
        assertFalse(AutoBlocker.present("samsung", null, rampartInstalled = false))
        assertFalse(AutoBlocker.present("Google", 80_000, rampartInstalled = true))
        assertFalse(AutoBlocker.present("google", null, rampartInstalled = true))
    }

    @Test
    fun switchFromValues_trustsOnlyZeroAndOne() {
        assertEquals(AutoBlocker.SwitchState.UNKNOWN, AutoBlocker.switchFromValues(emptyList()))
        assertEquals(AutoBlocker.SwitchState.UNKNOWN, AutoBlocker.switchFromValues(listOf(null, " ")))
        assertEquals(AutoBlocker.SwitchState.OFF, AutoBlocker.switchFromValues(listOf(null, "0")))
        assertEquals(AutoBlocker.SwitchState.ON, AutoBlocker.switchFromValues(listOf("1", "0")))
        assertEquals(AutoBlocker.SwitchState.UNKNOWN, AutoBlocker.switchFromValues(listOf("true")))
        assertEquals(AutoBlocker.SwitchState.UNKNOWN, AutoBlocker.switchFromValues(listOf("2")))
        assertEquals(AutoBlocker.SwitchState.ON, AutoBlocker.switchFromValues(listOf(" 1\n")))
    }

    @Test
    fun clear_whenTheMainSwitchIsOff_unlessASubSwitchStillReadsOn() {
        val off = reading(main = AutoBlocker.SwitchState.OFF, unauthorized = AutoBlocker.SwitchState.UNKNOWN)
        assertTrue(AutoBlocker.clear(off))
        assertFalse(AutoBlocker.needsManualCheck(off))
        assertTrue(AutoBlocker.detail(off).isEmpty())

        val conflict = off.copy(unauthorizedApps = AutoBlocker.SwitchState.ON)
        assertFalse(AutoBlocker.clear(conflict))
        assertFalse(AutoBlocker.needsManualCheck(conflict))
        assertEquals(AutoBlocker.DetailLine.MAIN_OFF_CONFLICT, AutoBlocker.detail(conflict).first())
    }

    @Test
    fun clear_whenAffectingSwitchesAreOff_evenIfTheMainSwitchStaysOn() {
        val kept = reading(
            main = AutoBlocker.SwitchState.ON,
            unauthorized = AutoBlocker.SwitchState.OFF,
            usb = AutoBlocker.SwitchState.OFF,
            maximum = AutoBlocker.SwitchState.OFF,
        )
        assertTrue(AutoBlocker.clear(kept))
        assertTrue(AutoBlocker.detail(kept).isEmpty())
    }

    @Test
    fun sideloadOrUsbOn_keepsTheStepOpenWithoutCallingItAManualCheck() {
        val sideload = reading(unauthorized = AutoBlocker.SwitchState.ON, usb = AutoBlocker.SwitchState.OFF)
        assertFalse(AutoBlocker.clear(sideload))
        assertFalse(AutoBlocker.needsManualCheck(sideload))
        val lines = AutoBlocker.detail(sideload)
        assertTrue(lines.contains(AutoBlocker.DetailLine.SIDELOAD_ON))
        assertTrue(lines.contains(AutoBlocker.DetailLine.USB_OFF))
        assertTrue(lines.contains(AutoBlocker.DetailLine.LEAVE_REST))
        assertEquals(AutoBlocker.DetailLine.WHERE, lines.last())

        val usb = reading(unauthorized = AutoBlocker.SwitchState.OFF, usb = AutoBlocker.SwitchState.ON)
        assertFalse(AutoBlocker.clear(usb))
        assertTrue(AutoBlocker.detail(usb).contains(AutoBlocker.DetailLine.USB_ON))
    }

    @Test
    fun maximumRestrictions_blocksOnlyWhenThatSwitchReadsOn() {
        val on = reading(oneUi = 60_101, maximum = AutoBlocker.SwitchState.ON, unauthorized = AutoBlocker.SwitchState.OFF, usb = AutoBlocker.SwitchState.OFF)
        assertEquals(AutoBlocker.Maximum.ON, AutoBlocker.maximum(on))
        assertFalse(AutoBlocker.clear(on))
        assertTrue(AutoBlocker.detail(on).contains(AutoBlocker.DetailLine.MAX_ON))
        assertFalse(AutoBlocker.needsManualCheck(on))
    }

    @Test
    fun maximumRestrictions_missingOnOneUi6_isNotThere_andMissingOn611_isUnread() {
        val older = reading(
            oneUi = 60_000,
            main = AutoBlocker.SwitchState.ON,
            unauthorized = AutoBlocker.SwitchState.OFF,
            usb = AutoBlocker.SwitchState.OFF,
            maximum = AutoBlocker.SwitchState.UNKNOWN,
        )
        assertEquals(AutoBlocker.Maximum.NOT_ON_THIS_VERSION, AutoBlocker.maximum(older))
        assertTrue(AutoBlocker.clear(older))

        val newer = older.copy(oneUiVersion = 60_101)
        assertEquals(AutoBlocker.Maximum.UNREAD, AutoBlocker.maximum(newer))
        assertFalse(AutoBlocker.clear(newer))
        assertTrue(AutoBlocker.needsManualCheck(newer))
        assertTrue(AutoBlocker.detail(newer).contains(AutoBlocker.DetailLine.MAX_UNREAD))
    }

    @Test
    fun unreadSwitches_areAManualCheckAndDoNotInventAnOnState() {
        val unread = reading(
            oneUi = null,
            main = AutoBlocker.SwitchState.UNKNOWN,
            unauthorized = AutoBlocker.SwitchState.UNKNOWN,
            usb = AutoBlocker.SwitchState.UNKNOWN,
            maximum = AutoBlocker.SwitchState.UNKNOWN,
        )
        assertFalse(AutoBlocker.clear(unread))
        assertTrue(AutoBlocker.needsManualCheck(unread))
        assertEquals(
            listOf(AutoBlocker.DetailLine.UNREAD_ALL, AutoBlocker.DetailLine.WHERE),
            AutoBlocker.detail(unread),
        )
    }

    @Test
    fun absentReading_isClearAndHasNoCopy() {
        assertTrue(AutoBlocker.clear(AutoBlocker.Reading.ABSENT))
        assertFalse(AutoBlocker.needsManualCheck(AutoBlocker.Reading.ABSENT))
        assertTrue(AutoBlocker.detail(AutoBlocker.Reading.ABSENT).isEmpty())
    }

    @Test
    fun openStep_alwaysHasSomethingToSay() {
        val samples = listOf(
            reading(unauthorized = AutoBlocker.SwitchState.ON),
            reading(usb = AutoBlocker.SwitchState.UNKNOWN, maximum = AutoBlocker.SwitchState.ON),
            reading(oneUi = 60_000, main = AutoBlocker.SwitchState.UNKNOWN, unauthorized = AutoBlocker.SwitchState.ON),
            reading(main = AutoBlocker.SwitchState.OFF, unauthorized = AutoBlocker.SwitchState.ON, usb = AutoBlocker.SwitchState.OFF),
        )
        for (sample in samples) {
            assertFalse(AutoBlocker.clear(sample))
            assertTrue(AutoBlocker.detail(sample).isNotEmpty())
        }
    }

    private fun reading(
        present: Boolean = true,
        oneUi: Int? = 70_000,
        main: AutoBlocker.SwitchState = AutoBlocker.SwitchState.ON,
        unauthorized: AutoBlocker.SwitchState = AutoBlocker.SwitchState.OFF,
        usb: AutoBlocker.SwitchState = AutoBlocker.SwitchState.OFF,
        maximum: AutoBlocker.SwitchState = AutoBlocker.SwitchState.OFF,
    ) = AutoBlocker.Reading(present, oneUi, main, unauthorized, usb, maximum)
}
