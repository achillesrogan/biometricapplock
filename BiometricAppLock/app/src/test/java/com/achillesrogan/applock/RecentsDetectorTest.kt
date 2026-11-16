package com.achillesrogan.applock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentsDetectorTest {

    @Test
    fun className_matchesRecentsActivities_onSystemUiAndOemForks() {
        val classes = listOf(
            "com.android.systemui.recents.RecentsActivity",
            "com.android.quickstep.RecentsActivity",
            "com.miui.home.recents.RecentsActivity",
            "com.sec.android.app.launcher.recents.RecentsActivity",
            "com.android.systemui.recents.views.RecentsView",
            "com.android.systemui.recents.OverviewProxyActivity",
        )
        for (name in classes) {
            assertTrue(name, RecentsDetector.classNameIsRecents(name))
            assertTrue(name, RecentsDetector.isOverview(name.substringBeforeLast('.').ifEmpty { "android" }, name, false))
        }
    }

    @Test
    fun launcherHome_isNotOverview_untilAnOverviewViewIsVisible() {
        val homes = listOf(
            "com.google.android.apps.nexuslauncher" to "com.google.android.apps.nexuslauncher.NexusLauncherActivity",
            "com.android.launcher3" to "com.android.launcher3.uioverrides.QuickstepLauncher",
            "com.sec.android.app.launcher" to "com.sec.android.app.launcher.activities.LauncherActivity",
            "com.miui.home" to "com.miui.home.launcher.Launcher",
            "com.android.launcher" to "com.android.launcher.Launcher",
            "com.nothing.launcher" to "com.nothing.launcher.Launcher",
            "com.huawei.android.launcher" to "com.huawei.android.launcher.unihome.UniHomeLauncher",
        )
        for ((pkg, cls) in homes) {
            assertFalse(cls, RecentsDetector.classNameIsRecents(cls))
            assertFalse(cls, RecentsDetector.isOverview(pkg, cls, overviewNodeVisible = false))
            assertTrue(cls, RecentsDetector.isOverview(pkg, cls, overviewNodeVisible = true))
        }
    }

    @Test
    fun unknownLauncher3Fork_usesTheClassNamePlusAVisibleOverviewId() {
        val pkg = "com.oem.launcher3"
        val cls = "com.oem.launcher3.uioverrides.QuickstepLauncher"
        assertTrue(RecentsDetector.mayHostOverview(pkg, cls))
        assertFalse(RecentsDetector.isOverview(pkg, cls, overviewNodeVisible = false))
        assertTrue(RecentsDetector.isOverview(pkg, cls, overviewNodeVisible = true))
    }

    @Test
    fun ordinaryApp_isNotOverview_evenWithAnOverviewShapedViewId() {
        assertFalse(
            RecentsDetector.isOverview(
                "com.example.notes",
                "com.example.notes.MainActivity",
                overviewNodeVisible = true,
            ),
        )
        assertFalse(RecentsDetector.mayHostOverview("com.example.notes", "com.example.notes.MainActivity"))
    }

    @Test
    fun viewIds_matchTheEntryNameOnly() {
        assertTrue(RecentsDetector.viewIdIsOverview("com.android.launcher3:id/overview_panel"))
        assertTrue(RecentsDetector.viewIdIsOverview("com.sec.android.app.launcher:id/overview_actions_view"))
        assertTrue(RecentsDetector.viewIdIsOverview("com.miui.home:id/recents_view"))
        assertTrue(RecentsDetector.viewIdIsOverview("com.android.launcher3:id/clear_all_button"))
        assertFalse(RecentsDetector.viewIdIsOverview("com.android.launcher3:id/workspace"))
        assertFalse(RecentsDetector.viewIdIsOverview("com.android.launcher3:id/hotseat"))
        assertFalse(RecentsDetector.viewIdIsOverview(null))
        assertFalse(RecentsDetector.viewIdIsOverview(""))
    }

    @Test
    fun systemUiNoise_isNotInspected_butRecentsActivityIs() {
        assertFalse(
            RecentsDetector.shouldInspect(
                "com.android.systemui",
                "com.android.systemui.statusbar.phone.StatusBar",
            ),
        )
        assertFalse(
            RecentsDetector.shouldInspect(
                "com.android.systemui",
                "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout",
            ),
        )
        assertTrue(
            RecentsDetector.shouldInspect(
                "com.android.systemui",
                "com.android.systemui.recents.RecentsActivity",
            ),
        )
        assertFalse(RecentsDetector.shouldInspect("com.android.systemui", ""))
        assertTrue(
            RecentsDetector.shouldInspect(
                "com.google.android.apps.nexuslauncher",
                "com.google.android.apps.nexuslauncher.NexusLauncherActivity",
            ),
        )
    }

    @Test
    fun shouldCover_requiresTheSettingALockedAppAndOverview() {
        assertTrue(RecentsDetector.shouldCover(enabled = true, hasLockedApp = true, overview = true))
        assertFalse(RecentsDetector.shouldCover(enabled = false, hasLockedApp = true, overview = true))
        assertFalse(RecentsDetector.shouldCover(enabled = true, hasLockedApp = false, overview = true))
        assertFalse(RecentsDetector.shouldCover(enabled = true, hasLockedApp = true, overview = false))
    }
}

class RecentsGuardTest {

    @Test
    fun missingPreference_isOn() {
        assertTrue(RecentsGuard.enabledOrDefault(null))
        assertTrue(RecentsGuard.enabledOrDefault(true))
        assertFalse(RecentsGuard.enabledOrDefault(false))
    }
}
