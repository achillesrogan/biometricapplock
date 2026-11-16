package com.achillesrogan.applock

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat

/**
 * Watches which app is in the foreground. When a locked app comes to the front and
 * hasn't been unlocked this session, it shows [LockActivity] on top of it.
 *
 * While uninstall protection is on, it also reads package-installer and Settings
 * windows enough to tell whether an uninstall, force stop, clear storage, or
 * device-admin deactivation targets a locked app or this app. Other windows are
 * not read.
 */
class AppLockService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingWatch: PendingWatch? = null
    private var recentSubject: String? = null
    private var recentSubjectAt = 0L

    /** Class name already proved this is overview, so a late view-id miss does not drop the cover. */
    private var classConfirmedOverview = false
    private var coverShownAt = 0L

    private val recentsCover = RecentsCover(this) {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private val inspectAgain = Runnable { pendingWatch?.let { consider(it) } }
    private val inspectActiveLater = Runnable { inspectActive() }
    private val overviewCheck = Runnable { checkOverviewNodes() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    // Re-check after the screen-off suppress window, in case a confirmation
                    // was still open when the display came back.
                    val wait = (LockState.suppressProtectionUntil - SystemClock.elapsedRealtime())
                        .coerceAtLeast(0L) + 200L
                    handler.removeCallbacks(inspectActiveLater)
                    handler.postDelayed(inspectActiveLater, wait)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ProtectionController.dismissUnderlying = { closeDangerousUi(andGoHome = true) }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val isWindow = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val isClick = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
        val isWindows = event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (isWindows) {
            // Window adds and removes do not mean overview closed. The app under
            // the overview still has a window, and treating that as "left recents"
            // would drop the cover. A real activity change handles leaving.
            val pkg = event.packageName?.toString()
            val className = event.className?.toString().orEmpty()
            if (!RecentsGuard.isEnabled(this) || LockStore.lockedPackages(this).isEmpty()) {
                if (recentsCover.isShowing() || classConfirmedOverview) clearOverview()
                return
            }
            if (className.isNotEmpty() && RecentsDetector.classNameIsRecents(className)) {
                classConfirmedOverview = true
                showRecentsCover()
                return
            }
            if (recentsCover.isShowing() || classConfirmedOverview ||
                (pkg != null && RecentsDetector.shouldInspect(pkg, className))
            ) {
                scheduleOverviewChecks()
            }
            return
        }
        if (!isWindow && !isClick) return
        val pkg = event.packageName?.toString() ?: return
        val className = event.className?.toString().orEmpty()
        if (isWindow) noteOverview(pkg, className)
        // Clicks are only useful on the system screens we already intend to read.
        if (isClick && pkg !in UninstallSignals.WATCHED_PACKAGES) return
        if (shouldIgnore(pkg)) return
        // Overview is not an app the user opened. Don't treat it as the foreground,
        // and don't launch the lock screen on top of the cover.
        if (isWindow && overviewInFront(pkg, className)) return

        if (isWindow && isActivity(pkg, event.className)) {
            LockState.onForegroundChanged(pkg)
            if (pkg !in UninstallSignals.SETTINGS_PACKAGES) recentSubject = null
            if (LockStore.isLocked(this, pkg) && !LockState.isUnlocked(pkg)) {
                if (recentsCover.isShowing()) return
                showLockScreen(pkg)
                return
            }
        }

        watchForProtection(event, pkg, className, isClick)
    }

    private fun watchForProtection(
        event: AccessibilityEvent,
        pkg: String,
        className: String,
        fromClick: Boolean,
    ) {
        if (!DeviceAdmin.isActive(this)) return
        // Window class is enough to skip Settings screens we never read. Clicks need the
        // control's label, which [WindowReader.pendingFrom] copies off the event.
        if (!fromClick && !UninstallSignals.shouldInspect(pkg, className, false, null, null)) return
        val pending = try {
            WindowReader.pendingFrom(event, pkg, fromClick)
        } catch (e: Exception) {
            return
        }
        if (!UninstallSignals.shouldInspect(
                pkg, className, fromClick, pending.clickedText, pending.clickedViewId,
            )
        ) {
            return
        }
        pendingWatch = pending
        consider(pending)
        handler.removeCallbacks(inspectAgain)
        handler.postDelayed(inspectAgain, 300)
        handler.postDelayed(inspectAgain, 800)
    }

    private fun consider(pending: PendingWatch) {
        if (LockState.protectionActive) return
        if (SystemClock.elapsedRealtime() < LockState.suppressProtectionUntil) return
        if (!DeviceAdmin.isActive(this)) return
        val snapshot = try {
            WindowReader.read(this, pending)
        } catch (e: Exception) {
            null
        } ?: return
        val protected = protectedLabels(this)
        rememberSubject(snapshot, protected)
        val hit = UninstallSignals.classify(
            snapshot,
            UninstallSignals.GuardContext(
                protectedLabels = protected,
                ownPackage = packageName,
                adminActive = true,
                fallbackPackage = fallbackPackage(),
            ),
        ) ?: return
        if (hit.packages.all { ProtectionGate.isAllowed(it) }) return
        showProtection(hit)
    }

    private fun rememberSubject(snapshot: UninstallSignals.Snapshot, protected: Map<String, String>) {
        if (!UninstallSignals.looksLikeAppInfo(snapshot)) return
        if (snapshot.headerTitle.isNullOrBlank()) return
        recentSubject = UninstallSignals.packageForHeader(snapshot.headerTitle, protected)
        recentSubjectAt = SystemClock.elapsedRealtime()
    }

    private fun fallbackPackage(): String? {
        val pkg = recentSubject ?: return null
        if (SystemClock.elapsedRealtime() - recentSubjectAt > SUBJECT_HOLD_MS) return null
        return pkg
    }

    /**
     * Recognizes overview and, when the setting is on and an app is locked,
     * drops the in-memory unlock and covers the screen. View ids are checked
     * a few times because Quickstep often reports the launcher activity before
     * the overview panel is visible.
     */
    private fun noteOverview(pkg: String?, className: String) {
        if (!RecentsGuard.isEnabled(this) || LockStore.lockedPackages(this).isEmpty()) {
            clearOverview()
            return
        }
        if (className.isNotEmpty() && RecentsDetector.classNameIsRecents(className)) {
            classConfirmedOverview = true
            showRecentsCover()
            return
        }
        if (pkg != null && RecentsDetector.shouldInspect(pkg, className)) {
            scheduleOverviewChecks()
            return
        }
        // A normal app window means overview is gone. SystemUI shade and status-bar
        // events are hosts, so they must not drop the cover.
        if (pkg != null && pkg != packageName && recentsCover.isShowing() &&
            !RecentsDetector.mayHostOverview(pkg, className)
        ) {
            clearOverview()
        }
    }

    private fun overviewInFront(pkg: String, className: String): Boolean {
        if (RecentsDetector.classNameIsRecents(className)) return true
        return recentsCover.isShowing() && RecentsDetector.mayHostOverview(pkg, className)
    }

    private fun scheduleOverviewChecks() {
        handler.removeCallbacks(overviewCheck)
        handler.post(overviewCheck)
        handler.postDelayed(overviewCheck, 120)
        handler.postDelayed(overviewCheck, 360)
        handler.postDelayed(overviewCheck, 700)
    }

    private fun checkOverviewNodes() {
        if (!RecentsGuard.isEnabled(this) || LockStore.lockedPackages(this).isEmpty()) {
            clearOverview()
            return
        }
        val visible = try {
            OverviewScanner.isOverviewVisible(this)
        } catch (e: Exception) {
            null
        }
        if (visible == null) return
        if (visible) {
            showRecentsCover()
            return
        }
        // The same burst of checks that discovered the panel can also sample a
        // frame where it is not visible yet. Hold a cover we just put up.
        val held = coverShownAt != 0L && SystemClock.elapsedRealtime() - coverShownAt < 800
        if (!classConfirmedOverview && recentsCover.isShowing() && !held) {
            recentsCover.hide()
            coverShownAt = 0L
        }
    }

    private fun showRecentsCover() {
        if (recentsCover.isShowing()) return
        // Clear the unlock even if this build rejects the overlay, so the card
        // cannot resume straight into the app.
        LockState.relockAll()
        recentsCover.show()
        if (recentsCover.isShowing()) coverShownAt = SystemClock.elapsedRealtime()
    }

    private fun clearOverview() {
        classConfirmedOverview = false
        coverShownAt = 0L
        handler.removeCallbacks(overviewCheck)
        recentsCover.hide()
    }

    private fun showLockScreen(pkg: String) {
        if (LockState.lockScreenFor == pkg) return
        LockState.lockScreenFor = pkg
        startActivity(LockActivity.intent(this, pkg))
    }

    private fun showProtection(hit: UninstallSignals.Hit) {
        LockState.protectionActive = true
        try {
            startActivity(
                LockActivity.protectionIntent(
                    this,
                    hit.label,
                    ArrayList(hit.packages),
                    hit.action.name,
                ),
            )
        } catch (e: Exception) {
            LockState.protectionActive = false
        }
    }

    private fun onScreenOff() {
        LockState.relockAll()
        ProtectionGate.clear()
        recentSubject = null
        // A confirmation that was already allowed is closed, so the next attempt
        // needs a fingerprint again. Don't press Back on an ordinary app.
        LockState.suppressProtectionUntil = SystemClock.elapsedRealtime() + 1500
        dismissIfDangerousConfirmation()
        val current = LockState.foreground() ?: return
        if (LockStore.isLocked(this, current)) showLockScreen(current)
    }

    private fun inspectActive() {
        if (LockState.protectionActive) return
        if (SystemClock.elapsedRealtime() < LockState.suppressProtectionUntil) return
        if (!DeviceAdmin.isActive(this)) return
        val snapshot = try {
            WindowReader.readActive(this)
        } catch (e: Exception) {
            null
        } ?: return
        val protected = protectedLabels(this)
        rememberSubject(snapshot, protected)
        val hit = UninstallSignals.classify(
            snapshot,
            UninstallSignals.GuardContext(
                protectedLabels = protected,
                ownPackage = packageName,
                adminActive = true,
                fallbackPackage = fallbackPackage(),
            ),
        ) ?: return
        if (hit.packages.all { ProtectionGate.isAllowed(it) }) return
        showProtection(hit)
    }

    private fun dismissIfDangerousConfirmation() {
        if (!DeviceAdmin.isActive(this)) return
        val snapshot = try {
            WindowReader.readActive(this)
        } catch (e: Exception) {
            null
        } ?: return
        val hit = UninstallSignals.classify(
            snapshot,
            UninstallSignals.GuardContext(
                protectedLabels = protectedLabels(this),
                ownPackage = packageName,
                adminActive = true,
                fallbackPackage = fallbackPackage(),
            ),
        ) ?: return
        closeDangerousUi(andGoHome = false)
    }

    private fun closeDangerousUi(andGoHome: Boolean) {
        val clicked = try {
            clickCancelOnWatchedWindows()
        } catch (e: Exception) {
            false
        }
        // Back is delayed so it is not delivered to our own lock window.
        if (!clicked) {
            handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 400)
        }
        if (andGoHome) {
            handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, if (clicked) 200 else 700)
        }
    }

    private fun clickCancelOnWatchedWindows(): Boolean {
        val snapshot = windows ?: return false
        var clicked = false
        try {
            for (window in snapshot) {
                val root = window.root ?: continue
                try {
                    val pkg = root.packageName?.toString() ?: continue
                    if (pkg !in UninstallSignals.WATCHED_PACKAGES) continue
                    if (clickCancel(root)) clicked = true
                } finally {
                    @Suppress("DEPRECATION")
                    root.recycle()
                }
            }
        } finally {
            for (window in snapshot) {
                @Suppress("DEPRECATION")
                window.recycle()
            }
        }
        return clicked
    }

    private fun clickCancel(root: AccessibilityNodeInfo): Boolean {
        for (label in CANCEL_LABELS) {
            val nodes = root.findAccessibilityNodeInfosByText(label) ?: continue
            var clicked = false
            for (node in nodes) {
                try {
                    if (!clicked) {
                        val text = (node.text ?: node.contentDescription)?.toString()?.trim()
                        if (text.equals(label, ignoreCase = true) &&
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        ) {
                            clicked = true
                        }
                    }
                } finally {
                    @Suppress("DEPRECATION")
                    node.recycle()
                }
            }
            if (clicked) return true
        }
        return false
    }

    private fun shouldIgnore(pkg: String): Boolean {
        if (pkg == packageName || pkg == "com.android.systemui") return true
        if (pkg in UninstallSignals.WATCHED_PACKAGES) return false
        return pkg in keyboardPackages()
    }

    private fun keyboardPackages(): Set<String> {
        val imm = getSystemService(InputMethodManager::class.java)
        val fromImm = imm?.enabledInputMethodList?.map { it.packageName }.orEmpty()
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
        return (fromImm + listOfNotNull(current)).toSet()
    }

    private fun isActivity(pkg: String, className: CharSequence?): Boolean {
        if (className.isNullOrEmpty()) return false
        return try {
            @Suppress("DEPRECATION")
            packageManager.getActivityInfo(ComponentName(pkg, className.toString()), 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        ProtectionController.dismissUnderlying = null
        recentsCover.hide()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recentsCover.hide()
        ProtectionController.dismissUnderlying = null
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    companion object {
        private const val SUBJECT_HOLD_MS = 15_000L
        private val CANCEL_LABELS = listOf("Cancel", "取消", "Annuler", "Cancelar", "Abbrechen")

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, AppLockService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}
