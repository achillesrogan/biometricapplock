package com.achillesrogan.applock

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AppListOrderTest {

    @Test
    fun lockedAppsComeFirst_thenEachGroupIsAlphabeticalIgnoringCase() {
        val placed = AppListOrder.place(
            listOf(
                app("Zoom", "com.zoom", locked = false),
                app("beta", "com.beta", locked = true),
                app("Alpha", "com.alpha", locked = true),
                app("cafe", "com.cafe", locked = false),
            ),
            Locale.US,
        )
        assertEquals(listOf("Alpha", "beta", "cafe", "Zoom"), placed.map { it.label })
        assertEquals(
            listOf(AppSection.LOCKED, AppSection.LOCKED, AppSection.OTHER, AppSection.OTHER),
            placed.map { it.section },
        )
    }

    @Test
    fun equalLabels_breakTiesByPackageName() {
        val placed = AppListOrder.place(
            listOf(
                app("Mail", "com.z", locked = true),
                app("mail", "com.a", locked = true),
            ),
            Locale.US,
        )
        assertEquals(listOf("com.a", "com.z"), placed.map { it.packageName })
    }

    @Test
    fun swedishSort_putsARingAfterZ() {
        val placed = AppListOrder.place(
            listOf(
                app("Åhlén", "se.ahlen", locked = false),
                app("Zebra", "se.zebra", locked = false),
            ),
            Locale.forLanguageTag("sv"),
        )
        assertEquals(listOf("Zebra", "Åhlén"), placed.map { it.label })
    }

    @Test
    fun togglingLock_doesNotChangePlaceOrSection() {
        val placed = AppListOrder.place(
            listOf(
                app("Alpha", "com.alpha", locked = true),
                app("Beta", "com.beta", locked = false),
            ),
            Locale.US,
        )
        val toggled = placed.map { app ->
            if (app.packageName == "com.beta") app.copy(locked = true) else app
        }
        assertEquals(placed.map { it.packageName }, toggled.map { it.packageName })
        assertEquals(placed.map { it.section }, toggled.map { it.section })
    }

    @Test
    fun filter_keepsTheSortedOrder() {
        val placed = AppListOrder.place(
            listOf(
                app("Zoom", "com.zoom", locked = false),
                app("Maps", "com.maps", locked = true),
                app("Mail", "com.mail", locked = true),
            ),
            Locale.US,
        )
        val shown = AppListOrder.filter(placed, "ma", Locale.US, { it.label }, { it.packageName })
        assertEquals(listOf("Mail", "Maps"), shown.map { it.label })
    }

    private fun app(label: String, packageName: String, locked: Boolean) =
        AppIdentity(label, packageName, locked)
}
