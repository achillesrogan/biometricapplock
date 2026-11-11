package com.achillesrogan.applock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UninstallSignalsTest {

    private val own = "com.achillesrogan.applock"
    private val notes = "com.example.notes"
    private val maps = "com.example.maps"

    private fun labels(extra: Map<String, String> = emptyMap()) =
        mapOf(notes to "Notes", maps to "Maps", own to "Biometric App Lock") + extra

    private fun guard(
        protected: Map<String, String> = labels(),
        adminActive: Boolean = true,
        fallback: String? = null,
    ) = UninstallSignals.GuardContext(protected, own, adminActive, fallback)

    private fun snap(
        eventPackage: String,
        className: String,
        texts: List<String> = emptyList(),
        viewIds: List<String> = emptyList(),
        headerTitle: String? = null,
        clickedText: String? = null,
        clickedViewId: String? = null,
        fromClick: Boolean = false,
    ) = UninstallSignals.Snapshot(
        eventPackage, className, texts, viewIds, headerTitle, clickedText, clickedViewId, fromClick,
    )

    @Test
    fun uninstallActivity_matchesProtectedLabel_onAospAndOemInstallers() {
        val classes = listOf(
            "com.android.packageinstaller" to "com.android.packageinstaller.UninstallerActivity",
            "com.google.android.packageinstaller" to "com.android.packageinstaller.UninstallerActivity",
            "com.samsung.android.packageinstaller" to "com.samsung.android.packageinstaller.UninstallerActivity",
            "com.miui.packageinstaller" to "com.miui.packageinstaller.UninstallerActivity",
        )
        for ((pkg, className) in classes) {
            val hit = UninstallSignals.classify(snap(pkg, className, listOf("Notes", "Uninstall")), guard())
            assertEquals(setOf(notes), hit?.packages)
            assertEquals(UninstallSignals.Action.UNINSTALL, hit?.action)
        }
    }

    @Test
    fun uninstallActivity_sentenceWithoutSeparateLabel_matches() {
        val hit = UninstallSignals.classify(
            snap(
                "com.google.android.packageinstaller",
                "com.android.packageinstaller.v2.ui.UninstallLaunch",
                listOf("Do you want to uninstall Notes?"),
            ),
            guard(),
        )
        assertEquals(setOf(notes), hit?.packages)
    }

    @Test
    fun uninstallActivity_otherApp_isNotBlocked() {
        val hit = UninstallSignals.classify(
            snap(
                "com.android.packageinstaller",
                "com.android.packageinstaller.UninstallerActivity",
                listOf("Calculator", "Do you want to uninstall Calculator?"),
            ),
            guard(),
        )
        assertNull(hit)
    }

    @Test
    fun uninstallActivity_doesNotTreatHeadphonesAsPhone() {
        val hit = UninstallSignals.classify(
            snap(
                "com.android.packageinstaller",
                "com.android.packageinstaller.UninstallerActivity",
                listOf("Headphones", "Do you want to uninstall Headphones?"),
            ),
            guard(labels(mapOf("com.example.phone" to "Phone"))),
        )
        assertNull(hit)
    }

    @Test
    fun installScreen_mentioningLockedApp_isNotBlocked() {
        assertFalse(
            UninstallSignals.shouldInspect(
                "com.google.android.packageinstaller",
                "com.android.packageinstaller.InstallStart",
                false, null, null,
            ),
        )
        val hit = UninstallSignals.classify(
            snap(
                "com.google.android.packageinstaller",
                "com.android.packageinstaller.PackageInstallerActivity",
                listOf("Notes", "Do you want to update this app?"),
            ),
            guard(),
        )
        assertNull(hit)
    }

    @Test
    fun appInfoPage_withoutClickOrDialog_isNotBlocked() {
        val hit = UninstallSignals.classify(
            snap(
                "com.android.settings",
                "com.android.settings.applications.InstalledAppDetails",
                listOf("Notes", "Force stop", "Uninstall", "Notifications"),
                listOf("com.android.settings:id/left_button", "com.android.settings:id/right_button"),
                headerTitle = "Notes",
            ),
            guard(),
        )
        assertNull(hit)
    }

    @Test
    fun forceStopClick_usesViewIdWithoutEnglishText() {
        val hit = UninstallSignals.classify(
            snap(
                "com.android.settings",
                "com.android.settings.applications.InstalledAppDetails",
                listOf("Notes", "Detener"),
                headerTitle = "Notes",
                clickedText = "Detener",
                clickedViewId = "com.android.settings:id/left_button",
                fromClick = true,
            ),
            guard(),
        )
        assertEquals(UninstallSignals.Action.FORCE_STOP, hit?.action)
        assertEquals(setOf(notes), hit?.packages)
    }

    @Test
    fun uninstallClick_onSettings_matchesHeader() {
        val hit = UninstallSignals.classify(
            snap(
                "com.miui.securitycenter",
                "com.miui.appmanager.ApplicationsDetailsActivity",
                listOf("Notes"),
                clickedText = "Uninstall",
                fromClick = true,
            ),
            guard(),
        )
        assertEquals(UninstallSignals.Action.UNINSTALL, hit?.action)
        assertEquals("Notes", hit?.label)
    }

    @Test
    fun disableClick_isNotAnUninstall() {
        assertNull(UninstallSignals.clickAction("Disable", "com.android.settings:id/right_button"))
        val hit = UninstallSignals.classify(
            snap(
                "com.android.settings",
                "com.android.settings.applications.InstalledAppDetails",
                listOf("Notes"),
                clickedText = "Disable",
                clickedViewId = "com.android.settings:id/right_button",
                fromClick = true,
            ),
            guard(),
        )
        assertNull(hit)
    }

    @Test
    fun clearDataClick_matchesProtectedApp() {
        val hit = UninstallSignals.classify(
            snap(
                "com.android.settings",
                "com.android.settings.SubSettings",
                listOf("Notes", "Clear data"),
                clickedText = "Clear data",
                fromClick = true,
            ),
            guard(),
        )
        assertEquals(UninstallSignals.Action.CLEAR_STORAGE, hit?.action)
        assertEquals(setOf(notes), hit?.packages)
    }

    @Test
    fun confirmationDialog_includesAppName() {
        val hit = UninstallSignals.classify(
            snap(
                "com.android.settings",
                "android.app.AlertDialog",
                listOf("Uninstall this app?", "Notes", "Cancel"),
            ),
            guard(),
        )
        assertEquals(UninstallSignals.Action.UNINSTALL, hit?.action)
        assertEquals(setOf(notes), hit?.packages)
    }

    @Test
    fun forceStopDialog_withoutName_usesRecentAppInfoSubject() {
        val hit = UninstallSignals.classify(
            snap(
                "com.android.settings",
                "androidx.appcompat.app.AlertDialog",
                listOf("Force stop?", "If you force stop an app, it may misbehave."),
            ),
            guard(fallback = notes),
        )
        assertEquals(UninstallSignals.Action.FORCE_STOP, hit?.action)
        assertEquals(setOf(notes), hit?.packages)
    }

    @Test
    fun fallback_isNotUsedForClicksOrInstallerScreens() {
        val click = UninstallSignals.classify(
            snap(
                "com.android.settings",
                "com.android.settings.SubSettings",
                listOf("Clear storage"),
                clickedText = "Clear storage",
                fromClick = true,
            ),
            guard(fallback = notes),
        )
        assertNull(click)
        val installer = UninstallSignals.classify(
            snap(
                "com.android.packageinstaller",
                "com.android.packageinstaller.UninstallerActivity",
                listOf("Uninstall"),
            ),
            guard(fallback = notes),
        )
        assertNull(installer)
    }

    @Test
    fun ownPackage_uninstall_isBlocked() {
        val hit = UninstallSignals.classify(
            snap(
                "com.google.android.packageinstaller",
                "com.android.packageinstaller.UninstallerActivity",
                listOf("Biometric App Lock", own),
            ),
            guard(),
        )
        assertEquals(setOf(own), hit?.packages)
        assertEquals(UninstallSignals.Action.UNINSTALL, hit?.action)
    }

    @Test
    fun deviceAdminDeactivation_forThisApp_isBlocked() {
        val hit = UninstallSignals.classify(
            snap(
                "com.android.settings",
                "com.android.settings.applications.specialaccess.deviceadmin.DeviceAdminAdd",
                listOf("Biometric App Lock", "Deactivate this device admin app"),
            ),
            guard(),
        )
        assertEquals(UninstallSignals.Action.DEVICE_ADMIN, hit?.action)
        assertEquals(setOf(own), hit?.packages)
        assertEquals("Biometric App Lock", hit?.label)
    }

    @Test
    fun deviceAdminScreen_forAnotherApp_isNotBlocked() {
        val hit = UninstallSignals.classify(
            snap(
                "com.android.settings",
                "com.android.settings.DeviceAdminAdd",
                listOf("Some other admin", "Deactivate this device admin app"),
            ),
            guard(),
        )
        assertNull(hit)
    }

    @Test
    fun deviceAdminActivation_whileProtectionIsOff_isNotBlocked() {
        val hit = UninstallSignals.classify(
            snap(
                "com.android.settings",
                "com.android.settings.DeviceAdminAdd",
                listOf("Activate this device admin app?", "Biometric App Lock"),
            ),
            guard(adminActive = false),
        )
        assertNull(hit)
    }

    @Test
    fun disableWarningDialog_targetsThisApp() {
        val hit = UninstallSignals.classify(
            snap(
                "com.android.settings",
                "android.app.AlertDialog",
                listOf("Uninstall protection is on. Deactivating device admin lets Biometric App Lock be uninstalled."),
            ),
            guard(),
        )
        assertEquals(UninstallSignals.Action.DEVICE_ADMIN, hit?.action)
        assertEquals(setOf(own), hit?.packages)
    }

    @Test
    fun settingsProse_mentioningThisApp_isNotBlocked() {
        val description = "On package-installer, Settings, and device-admin screens it also reads " +
            "the on-screen text to see if an uninstall, force stop, clear storage, or " +
            "device-admin deactivation targets a locked app or Biometric App Lock."
        assertFalse(
            UninstallSignals.shouldInspect(
                "com.android.settings",
                "com.android.settings.SubSettings",
                false, null, null,
            ),
        )
        val hit = UninstallSignals.classify(
            snap(
                "com.android.settings",
                "com.android.settings.SubSettings",
                listOf("Biometric App Lock", description),
            ),
            guard(),
        )
        assertNull(hit)
    }

    @Test
    fun wifiDialog_isNotTreatedAsAConfirmation() {
        assertFalse(
            UninstallSignals.shouldInspect(
                "com.android.settings",
                "com.android.settings.wifi.WifiDialogActivity",
                false, null, null,
            ),
        )
        val hit = UninstallSignals.classify(
            snap(
                "com.android.settings",
                "com.android.settings.wifi.WifiDialogActivity",
                listOf("Notes", "If you force stop an app, it may misbehave."),
            ),
            guard(),
        )
        assertNull(hit)
    }

    @Test
    fun protectionOff_doesNotBlockALockedApp() {
        val hit = UninstallSignals.classify(
            snap(
                "com.android.packageinstaller",
                "com.android.packageinstaller.UninstallerActivity",
                listOf("Notes"),
            ),
            guard(adminActive = false),
        )
        assertNull(hit)
    }

    @Test
    fun ambiguousExactLabels_areBothIncluded() {
        val hit = UninstallSignals.classify(
            snap(
                "com.android.packageinstaller",
                "com.android.packageinstaller.UninstallerActivity",
                listOf("Notes", "Maps"),
            ),
            guard(),
        )
        assertEquals(setOf(notes, maps), hit?.packages)
    }

    @Test
    fun headerTitle_isASingleProtectedPackageOrNull() {
        val protected = labels()
        assertEquals(notes, UninstallSignals.packageForHeader("Notes", protected))
        assertNull(UninstallSignals.packageForHeader("Calculator", protected))
        assertNull(UninstallSignals.packageForHeader("Notes", mapOf(notes to "Notes", "other.notes" to "Notes")))
    }

    @Test
    fun shouldInspect_coversUninstallClicksAndSkipsOrdinarySettingsClicks() {
        assertTrue(
            UninstallSignals.shouldInspect(
                "com.android.settings",
                "com.android.settings.homepage.SettingsHomepageActivity",
                true, "Uninstall", null,
            ),
        )
        assertFalse(
            UninstallSignals.shouldInspect(
                "com.android.settings",
                "com.android.settings.homepage.SettingsHomepageActivity",
                true, "Wi-Fi", null,
            ),
        )
        assertTrue(
            UninstallSignals.shouldInspect(
                "com.android.settings",
                "android.app.AlertDialog",
                false, null, null,
            ),
        )
        assertFalse(
            UninstallSignals.shouldInspect(
                "com.android.settings",
                "com.android.settings.homepage.SettingsHomepageActivity",
                false, null, null,
            ),
        )
    }
}
