package com.achillesrogan.applock

/**
 * Decides whether a system window is an uninstall, force-stop, clear-storage,
 * or device-admin deactivation aimed at a protected package.
 *
 * Unknown screens fail open: if the target app is not clear, nothing is blocked.
 * Install and update screens are not treated as uninstalls.
 *
 * This class does not read the screen itself. The accessibility service passes
 * text and view ids it already collected from a package-installer or Settings window.
 */
object UninstallSignals {

    val INSTALLER_PACKAGES = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.sec.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.miui.global.packageinstaller",
        "com.huawei.android.packageinstaller",
        "com.huawei.packageinstaller",
        "com.oplus.packageinstaller",
        "com.coloros.packageinstaller",
        "com.oneplus.packageinstaller",
    )

    val SETTINGS_PACKAGES = setOf(
        "com.android.settings",
        "com.samsung.android.settings",
        "com.miui.securitycenter",
        "com.huawei.systemmanager",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
    )

    val WATCHED_PACKAGES = INSTALLER_PACKAGES + SETTINGS_PACKAGES

    /** Shown by [AppDeviceAdminReceiver]; a dialog with this text is our deactivation warning. */
    const val ADMIN_WARNING_SNIPPET = "uninstall protection is on"

    enum class Action { UNINSTALL, FORCE_STOP, CLEAR_STORAGE, DEVICE_ADMIN }

    data class Snapshot(
        val eventPackage: String,
        val className: String,
        val texts: List<String>,
        val viewIds: List<String>,
        val headerTitle: String?,
        val clickedText: String?,
        val clickedViewId: String?,
        val fromClick: Boolean,
    )

    data class GuardContext(
        /** Package name to the label shown on screen. Includes this app when protection is on. */
        val protectedLabels: Map<String, String>,
        val ownPackage: String,
        val adminActive: Boolean,
        /**
         * Last protected app whose Settings details page was in front.
         * Used only when a Settings confirmation dialog does not repeat the app name.
         */
        val fallbackPackage: String?,
    )

    data class Hit(
        val packages: Set<String>,
        val label: String,
        val action: Action,
    )

    fun shouldInspect(
        eventPackage: String,
        className: String,
        fromClick: Boolean,
        clickedText: String?,
        clickedViewId: String?,
    ): Boolean {
        val classLower = className.lowercase()
        if (eventPackage in INSTALLER_PACKAGES) {
            if (isInstallClass(classLower)) return false
            if (classLower.contains("uninstall") || classLower.contains("dialog") || classLower.isEmpty()) {
                return true
            }
            return fromClick && clickAction(clickedText, clickedViewId) != null
        }
        if (eventPackage in SETTINGS_PACKAGES) {
            if (fromClick) return clickAction(clickedText, clickedViewId) != null
            // SubSettings and generic "*Dialog*" activities are intentionally not
            // included: those host Wi-Fi passwords and other Settings pages.
            return SETTINGS_CLASS_HINTS.any { classLower.contains(it) } || classLower.endsWith(".dialog")
        }
        return false
    }

    fun classify(snapshot: Snapshot, context: GuardContext): Hit? {
        if (!context.adminActive || context.protectedLabels.isEmpty()) return null
        if (snapshot.eventPackage !in WATCHED_PACKAGES) return null

        val action = detectAction(snapshot, context) ?: return null
        if (action == Action.DEVICE_ADMIN) {
            if (!mentionsOwnApp(snapshot, context)) return null
            val label = context.protectedLabels[context.ownPackage] ?: context.ownPackage
            return Hit(setOf(context.ownPackage), label, Action.DEVICE_ADMIN)
        }

        var packages = matchingPackages(snapshot.texts, context.protectedLabels)
        if (packages.isEmpty() && allowFallback(snapshot, action)) {
            val fallback = context.fallbackPackage
            if (fallback != null && fallback in context.protectedLabels) packages = setOf(fallback)
        }
        if (packages.isEmpty()) return null
        return Hit(packages, labelFor(packages, context.protectedLabels), action)
    }

    /** One protected package whose header title is exactly [header], or null if that is not clear. */
    fun packageForHeader(header: String?, protected: Map<String, String>): String? {
        if (header.isNullOrBlank()) return null
        val title = normalize(header)
        val hits = protected.filter { (pkg, label) ->
            title.equals(pkg, ignoreCase = true) || title.equals(normalize(label), ignoreCase = true)
        }.keys
        return hits.singleOrNull()
    }

    fun looksLikeAppInfo(snapshot: Snapshot): Boolean {
        val classLower = snapshot.className.lowercase()
        if (APP_INFO_CLASS_HINTS.any { classLower.contains(it) }) return true
        return snapshot.viewIds.any { id ->
            val lower = id.lowercase()
            lower.endsWith("/entity_header_title") ||
                lower.endsWith("/left_button") ||
                lower.endsWith("/app_name") ||
                lower.endsWith("/header_title")
        }
    }

    private fun detectAction(snapshot: Snapshot, context: GuardContext): Action? {
        val classLower = snapshot.className.lowercase()
        if (isOwnDeviceAdminScreen(snapshot, classLower, context)) return Action.DEVICE_ADMIN

        if (snapshot.fromClick) {
            clickAction(snapshot.clickedText, snapshot.clickedViewId)?.let { return it }
        }

        if (snapshot.eventPackage in INSTALLER_PACKAGES && classLower.contains("uninstall")) {
            return Action.UNINSTALL
        }

        if (snapshot.eventPackage in SETTINGS_PACKAGES && isConfirmationDialog(snapshot)) {
            val blob = snapshot.texts.joinToString("\n").lowercase()
            when {
                FORCE_STOP_CONFIRM.any { blob.contains(it) } -> return Action.FORCE_STOP
                CLEAR_CONFIRM.any { blob.contains(it) } -> return Action.CLEAR_STORAGE
                UNINSTALL_CONFIRM.any { blob.contains(it) } -> return Action.UNINSTALL
                blob.contains(ADMIN_WARNING_SNIPPET) -> return Action.DEVICE_ADMIN
                blob.contains("deactivat") &&
                    (blob.contains("device admin") || classLower.contains("deviceadmin")) ->
                    return Action.DEVICE_ADMIN
            }
        }
        return null
    }

    private fun isOwnDeviceAdminScreen(snapshot: Snapshot, classLower: String, context: GuardContext): Boolean {
        if (!context.adminActive || !classLower.contains("deviceadminadd")) return false
        return mentionsOwnApp(snapshot, context)
    }

    private fun mentionsOwnApp(snapshot: Snapshot, context: GuardContext): Boolean {
        val label = context.protectedLabels[context.ownPackage]
        if (matchingPackages(snapshot.texts, mapOf(context.ownPackage to (label ?: context.ownPackage)))
                .contains(context.ownPackage)
        ) {
            return true
        }
        return snapshot.texts.any { it.lowercase().contains(ADMIN_WARNING_SNIPPET) }
    }

    /**
     * Clicks already have the page in front, so a missing name means "not this app".
     * A Settings dialog often omits the name; the details page we just saw is the fallback.
     */
    private fun allowFallback(snapshot: Snapshot, action: Action): Boolean {
        if (snapshot.fromClick || action == Action.DEVICE_ADMIN) return false
        if (snapshot.eventPackage !in SETTINGS_PACKAGES) return false
        return isConfirmationDialog(snapshot)
    }

    private fun isConfirmationDialog(snapshot: Snapshot): Boolean {
        val classLower = snapshot.className.lowercase()
        // "WifiDialogActivity" contains "dialog" but is not a confirmation we handle.
        if (classLower.contains("alertdialog") || classLower.endsWith(".dialog")) return true
        return snapshot.viewIds.any { id ->
            val lower = id.lowercase()
            lower.endsWith(":id/button1") || lower.endsWith("/button1") ||
                lower.endsWith(":id/alerttitle") || lower.endsWith("/alerttitle")
        }
    }

    private fun matchingPackages(texts: List<String>, protected: Map<String, String>): Set<String> {
        if (protected.isEmpty()) return emptySet()
        val exact = mutableSetOf<String>()
        for (raw in texts) {
            val text = normalize(raw)
            if (text.isEmpty()) continue
            for ((pkg, label) in protected) {
                if (text.equals(pkg, ignoreCase = true) || text.equals(normalize(label), ignoreCase = true)) {
                    exact += pkg
                }
            }
        }
        if (exact.isNotEmpty()) return exact

        val loose = mutableSetOf<String>()
        for (raw in texts) {
            if (!looksLikeActionSentence(raw)) continue
            for ((pkg, label) in protected) {
                if (containsToken(raw, label) || containsToken(raw, pkg)) loose += pkg
            }
        }
        return loose
    }

    private fun looksLikeActionSentence(text: String): Boolean {
        val lower = text.lowercase()
        return UNINSTALL_CONFIRM.any { lower.contains(it) } ||
            FORCE_STOP_CONFIRM.any { lower.contains(it) } ||
            CLEAR_CONFIRM.any { lower.contains(it) } ||
            lower.contains("deactivat") ||
            lower.contains(ADMIN_WARNING_SNIPPET)
    }

    private fun containsToken(text: String, token: String): Boolean {
        val trimmed = token.trim()
        // Short names match only when the whole node equals them (handled above).
        if (trimmed.length < 3) return false
        return TOKEN.find(trimmed, text) != null
    }

    private fun labelFor(packages: Set<String>, protected: Map<String, String>): String =
        packages.map { protected[it]?.takeIf { label -> label.isNotBlank() } ?: it }
            .distinct()
            .joinToString(", ")

    private fun isInstallClass(classLower: String): Boolean =
        !classLower.contains("uninstall") && INSTALL_CLASS_SKIP.any { classLower.contains(it) }

    private fun normalize(text: String) = text.trim().trimEnd('?', '!', '。', '？', '"', '\'')

    /** Language-independent where the view id is stable; English and a few common labels otherwise. */
    fun clickAction(clickedText: String?, clickedViewId: String?): Action? {
        val text = clickedText?.trim()?.lowercase().orEmpty()
        val id = clickedViewId?.lowercase().orEmpty()
        if (id.endsWith("/left_button") || id.endsWith("/force_stop_button") || id.endsWith("/force_stop")) {
            return Action.FORCE_STOP
        }
        if (text in FORCE_STOP_CLICK) return Action.FORCE_STOP
        if (text in CLEAR_CLICK) return Action.CLEAR_STORAGE
        if (text in UNINSTALL_CLICK) return Action.UNINSTALL
        if (text.contains("deactivat")) return Action.DEVICE_ADMIN
        return null
    }

    private val TOKEN = object {
        fun find(token: String, text: String): MatchResult? {
            val pattern = Regex(
                "(?<![\\p{L}\\p{N}_])${Regex.escape(token)}(?![\\p{L}\\p{N}_])",
                RegexOption.IGNORE_CASE,
            )
            return pattern.find(text)
        }
    }

    private val INSTALL_CLASS_SKIP = listOf(
        "installstart",
        "installlaunch",
        "installstaging",
        "installsuccess",
        "installfailed",
        "packageinstalleractivity",
    )

    private val SETTINGS_CLASS_HINTS = listOf(
        "installedapp",
        "applicationdetail",
        "deviceadmin",
        "alertdialog",
        "spaactivity",
        "appinfo",
        "appmanager",
        "appmanagement",
        "uninstall",
    )

    private val APP_INFO_CLASS_HINTS = listOf(
        "installedapp",
        "applicationdetail",
        "appinfo",
        "appmanager",
        "spaactivity",
    )

    private val UNINSTALL_CONFIRM = listOf(
        "uninstall this app",
        "want to uninstall",
        "uninstall the app",
        "desinstalar esta aplicación",
        "désinstaller cette application",
        "diese app deinstallieren",
        "卸载此应用",
        "卸载这个应用",
    )

    private val FORCE_STOP_CONFIRM = listOf(
        "if you force stop",
        "force stop?",
        "force-stop this app",
    )

    private val CLEAR_CONFIRM = listOf(
        "delete app data",
        "all this app's data will be deleted",
        "all of this app's data will be deleted",
        "clear all data",
        "clear storage?",
        "clear data?",
    )

    private val UNINSTALL_CLICK = setOf(
        "uninstall",
        "uninstall app",
        "uninstall application",
        "desinstalar",
        "désinstaller",
        "deinstallieren",
        "удалить",
        "卸载",
        "アンインストール",
        "제거",
    )

    private val FORCE_STOP_CLICK = setOf(
        "force stop",
        "force-stop",
        "forzar detención",
        "forcer l'arrêt",
        "beenden erzwingen",
        "强行停止",
        "强制停止",
        "強制停止",
        "강제 종료",
    )

    private val CLEAR_CLICK = setOf(
        "clear storage",
        "clear data",
        "borrar almacenamiento",
        "borrar datos",
        "effacer les données",
        "effacer le stockage",
        "daten löschen",
        "清除数据",
        "清除存储",
        "清除存储空间",
        "データを削除",
        "데이터 삭제",
    )
}
