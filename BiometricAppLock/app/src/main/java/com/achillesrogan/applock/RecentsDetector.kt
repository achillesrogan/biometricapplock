package com.achillesrogan.applock

/**
 * Decides whether a window is the system overview (Recents) screen.
 *
 * Android will not let a third-party app set FLAG_SECURE on another app, and
 * the task snapshot is taken from that app's own surface. The lock activity
 * lives in a different task, so it cannot replace the thumbnail. The practical
 * fix is to recognize the overview window and cover it.
 *
 * Two signals are used, and neither one reads the text on the cards:
 * - A window class whose simple name is a known overview activity
 *   (AOSP/Pixel SystemUI `RecentsActivity`, and the same name in Samsung,
 *   Xiaomi, and other Quickstep forks).
 * - A known launcher or SystemUI window that currently has a visible
 *   Launcher3 overview view id (`overview_panel` and the like). Gesture
 *   navigation on Pixel, AOSP, Samsung One UI, and other Launcher3 forks
 *   draws overview inside the launcher activity, which is also the home
 *   screen, so the class name alone is not enough there.
 */
object RecentsDetector {

    val HOST_PACKAGES: Set<String> = setOf(
        "com.android.systemui",
        "com.android.launcher3",
        "com.android.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher",
        "com.samsung.android.app.launcher",
        "com.miui.home",
        "com.mi.android.globallauncher",
        "com.huawei.android.launcher",
        "com.hihonor.android.launcher",
        "com.oppo.launcher",
        "com.coloros.launcher",
        "com.realme.launcher",
        "com.oneplus.launcher",
        "net.oneplus.launcher",
        "com.nothing.launcher",
        "com.motorola.launcher3",
        "com.bbk.launcher2",
        "com.vivo.launcher",
        "com.sonymobile.home",
        "com.lge.launcher2",
        "com.lge.launcher3",
        "com.transsion.launcher3",
        "com.transsion.XOSLauncher",
        "com.transsion.hilauncher",
        "com.asus.launcher",
        "com.teslacoilsw.launcher",
        "com.microsoft.launcher",
    )

    /** View id entry names that Launcher3 shows only on the overview page. */
    val OVERVIEW_VIEW_IDS: Set<String> = setOf(
        "overview_panel",
        "overview_actions_view",
        "recents_view",
        "recents_container",
        "clear_all_button",
    )

    private val RECENTS_SIMPLE_NAMES: Set<String> = setOf(
        "RecentsActivity",
        "RecentsView",
        "OverviewActivity",
        "OverviewProxyActivity",
    )

    fun classNameIsRecents(className: String): Boolean {
        if (className.isEmpty()) return false
        return className.substringAfterLast('.') in RECENTS_SIMPLE_NAMES
    }

    /** `com.android.launcher3:id/overview_panel` → overview_panel. */
    fun viewIdIsOverview(viewIdResourceName: String?): Boolean {
        if (viewIdResourceName.isNullOrBlank()) return false
        val entry = viewIdResourceName.substringAfterLast('/').substringAfterLast(':')
        return entry in OVERVIEW_VIEW_IDS
    }

    /**
     * Windows worth scanning for overview view ids. A normal app is not scanned.
     * Launcher3 forks we do not list by package still match on the class name.
     */
    fun mayHostOverview(packageName: String, className: String): Boolean {
        if (packageName in HOST_PACKAGES) return true
        if (classNameIsRecents(className)) return true
        val name = className.lowercase()
        return name.contains("launcher3") || name.contains("quickstep") || name.contains(".recents.")
    }

    /**
     * Whether this event should start a view-id scan. SystemUI sends status-bar
     * and shade events constantly; those are not overview, so they are ignored
     * unless the class name itself is the recents activity.
     */
    fun shouldInspect(packageName: String, className: String): Boolean {
        if (!mayHostOverview(packageName, className)) return false
        if (packageName != "com.android.systemui") return true
        if (classNameIsRecents(className)) return true
        val name = className.lowercase()
        if (name.contains("statusbar") || name.contains("notification") ||
            name.contains("volume") || name.contains("keyguard") || name.contains("bouncer")
        ) {
            return false
        }
        return name.contains("recents") || name.contains("overview") ||
            name.contains("launcher") || name.contains("quickstep")
    }

    /**
     * @param overviewNodeVisible true when a host window has a visible overview view id.
     *   Invisible ids that stay in the home layout do not count.
     */
    fun isOverview(packageName: String, className: String, overviewNodeVisible: Boolean): Boolean {
        if (classNameIsRecents(className)) return true
        if (!overviewNodeVisible) return false
        return mayHostOverview(packageName, className)
    }

    /** Cover only while the setting is on, something is locked, and overview is up. */
    fun shouldCover(enabled: Boolean, hasLockedApp: Boolean, overview: Boolean): Boolean =
        enabled && hasLockedApp && overview
}
