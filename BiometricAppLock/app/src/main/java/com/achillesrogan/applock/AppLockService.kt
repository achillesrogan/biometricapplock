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

    private var coverState = RecentsCoverMachine.State()
    private var lastClassIsRecents = false
    private var lastWindowWasHost = false
    private var lastScanAt = -1L
    private var lastBurstAt = -1L

    /** Set when the cover path throws. App locking keeps running; the overlay stays off. */
    private var recentsCoverRetired = false

    private val locksChanged: () -> Unit = { LockStore.load(this) }

    private val recentsCover = RecentsCover(this) {
        try {
            // Drop our state before going home, or the next launcher event shows the cover again.
            dispatch(RecentsCoverMachine.Kind.DISABLED)
            performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (e: Exception) {
            retireRecentsCover()
        }
    }

    private val inspectAgain = Runnable { pendingWatch?.let { consider(it) } }
    private val inspectActiveLater = Runnable { inspectActive() }
    private val overviewCheck = Runnable { dispatchNodes() }
    private val coverWatchdog = Runnable {
        if (coverState.phase == RecentsCoverMachine.Phase.IDLE) return@Runnable
        dispatchNodes()
        if (coverState.phase != RecentsCoverMachine.Phase.IDLE) {
            apply(RecentsCoverMachine.step(coverState, coverInput(RecentsCoverMachine.Kind.WATCHDOG)))
        }
    }

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
        LockStore.load(this)
        LockStore.addListener(locksChanged)
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
        // The cover must not be able to kill this service. A dead service is why
        // the switches can still say on while nothing locks.
        if (!recentsCoverRetired && RecentsCoverSession.coverAllowed(false)) {
            try {
                handleRecentsEvent(event)
            } catch (e: Exception) {
                retireRecentsCover()
            }
        }
        try {
            handleLockEvent(event)
        } catch (e: Exception) {
            // One bad event must not disable locking.
        }
    }

    private fun handleRecentsEvent(event: AccessibilityEvent) {
        val isWindow = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val isWindows = event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (!isWindow && !isWindows) return
        noteOverview(event.packageName?.toString(), event.className?.toString().orEmpty(), isWindows)
    }

    private fun handleLockEvent(event: AccessibilityEvent) {
        val isWindow = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val isClick = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        if (!isWindow && !isClick) return
        val pkg = event.packageName?.toString() ?: return
        val className = event.className?.toString().orEmpty()
        if (isClick && pkg !in UninstallSignals.WATCHED_PACKAGES) return
        if (shouldIgnore(pkg)) return
        if (isWindow && overviewInFront(pkg, className)) return

        if (isWindow && isActivity(pkg, event.className)) {
            LockState.onForegroundChanged(pkg)
            if (pkg !in UninstallSignals.SETTINGS_PACKAGES) recentSubject = null
            LockStore.load(this)
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
     * Recognizes overview. Stricter mode also covers the moment a locked app
     * is left for any launcher or SystemUI window, then the state machine
     * takes that cover down if the user landed on Home or another app.
     */
    private fun noteOverview(pkg: String?, className: String, fromWindowsChanged: Boolean) {
        if (recentsCoverRetired) return
        if (pkg == packageName || (pkg != null && pkg in keyboardPackages())) return
        lastClassIsRecents = className.isNotEmpty() && RecentsDetector.classNameIsRecents(className)
        lastWindowWasHost = lastClassIsRecents ||
            (pkg != null && RecentsDetector.mayHostOverview(pkg, className))
        val hideOn = RecentsGuard.isEnabled(this) && LockStore.lockedPackages(this).isNotEmpty()
        if (!hideOn) {
            dispatch(RecentsCoverMachine.Kind.DISABLED)
            return
        }
        val foreground = LockState.foreground()
        if (RecentsCoverMachine.leavingUnlockedLockedApp(
                stricter = RecentsGuard.isStricter(this),
                hideEnabled = true,
                foregroundPkg = foreground,
                foregroundIsLocked = foreground != null && LockStore.isLocked(this, foreground),
                foregroundIsUnlocked = foreground != null && LockState.isUnlocked(foreground),
                newPkg = pkg,
                newClass = className,
                ownPkg = packageName,
            )
        ) {
            dispatch(RecentsCoverMachine.Kind.LEFT_UNLOCKED_LOCKED_FOR_HOST)
        }
        if (lastClassIsRecents) {
            dispatch(RecentsCoverMachine.Kind.RECENTS_CLASS)
            return
        }
        if (pkg != null && RecentsDetector.shouldInspect(pkg, className)) {
            // Idle windows-changed events fire constantly. Scanning them is what
            // ANRs the service. A real window-state change, or a cover already up, is enough.
            val coverUp = coverState.phase != RecentsCoverMachine.Phase.IDLE
            if (fromWindowsChanged && !coverUp) return
            dispatch(RecentsCoverMachine.Kind.HOST_SIGNAL)
            return
        }
        if (fromWindowsChanged && pkg == null && coverState.phase != RecentsCoverMachine.Phase.IDLE) {
            dispatch(RecentsCoverMachine.Kind.HOST_SIGNAL)
            return
        }
        // Window adds under the overview are not "the user opened another app".
        if (!fromWindowsChanged && pkg != null && coverState.phase != RecentsCoverMachine.Phase.IDLE &&
            !RecentsDetector.mayHostOverview(pkg, className)
        ) {
            dispatch(RecentsCoverMachine.Kind.NON_HOST_APP, nonHostPkg = pkg)
        }
    }

    private fun overviewInFront(pkg: String, className: String): Boolean {
        if (RecentsDetector.classNameIsRecents(className)) return true
        return coverState.phase != RecentsCoverMachine.Phase.IDLE &&
            RecentsDetector.mayHostOverview(pkg, className)
    }

    private fun scheduleOverviewChecks() {
        if (recentsCoverRetired) return
        val now = SystemClock.elapsedRealtime()
        if (!RecentsScanGate.allow(now, lastBurstAt)) return
        lastBurstAt = now
        handler.removeCallbacks(overviewCheck)
        handler.post(overviewCheck)
        handler.postDelayed(overviewCheck, 450)
        handler.postDelayed(overviewCheck, 900)
    }

    private fun dispatchNodes() {
        if (recentsCoverRetired) return
        val now = SystemClock.elapsedRealtime()
        if (!RecentsScanGate.allow(now, lastScanAt)) return
        lastScanAt = now
        refreshActiveClass()
        val visible = try {
            OverviewScanner.isOverviewVisible(this)
        } catch (e: Exception) {
            null
        }
        dispatch(RecentsCoverMachine.Kind.NODES, nodesVisible = visible)
    }

    /** Package and class only. Nodes are not recycled: Samsung crashes if they are. */
    private fun refreshActiveClass() {
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return
        try {
            val className = root.className?.toString().orEmpty()
            val pkg = root.packageName?.toString()
            if (pkg == packageName) return
            lastClassIsRecents = RecentsDetector.classNameIsRecents(className)
            lastWindowWasHost = lastClassIsRecents ||
                (pkg != null && RecentsDetector.mayHostOverview(pkg, className))
        } catch (e: Exception) {
            // The active window can disappear between the null check and the read.
        }
    }

    private fun dispatch(kind: RecentsCoverMachine.Kind, nodesVisible: Boolean? = null, nonHostPkg: String? = null) {
        apply(RecentsCoverMachine.step(coverState, coverInput(kind, nodesVisible, nonHostPkg)))
    }

    private fun coverInput(
        kind: RecentsCoverMachine.Kind,
        nodesVisible: Boolean? = null,
        nonHostPkg: String? = null,
    ) = RecentsCoverMachine.Input(
        nowMs = SystemClock.elapsedRealtime(),
        kind = kind,
        stricter = RecentsGuard.isStricter(this),
        hideEnabled = RecentsGuard.isEnabled(this) && LockStore.lockedPackages(this).isNotEmpty(),
        nodesVisible = nodesVisible,
        currentClassIsRecents = lastClassIsRecents,
        foregroundPkg = LockState.foreground(),
        nonHostPkg = nonHostPkg,
    )

    private fun apply(effect: RecentsCoverMachine.Effect) {
        val wasUp = coverState.phase != RecentsCoverMachine.Phase.IDLE
        coverState = effect.state
        handler.removeCallbacks(coverWatchdog)
        effect.watchdogInMs?.takeIf { it > 0L }?.let { handler.postDelayed(coverWatchdog, it) }
        if (!effect.coverVisible) handler.removeCallbacks(overviewCheck)
        if (effect.scheduleBurst) scheduleOverviewChecks()
        if (effect.relock) LockState.relockAll()
        if (effect.coverVisible) {
            if (!recentsCover.show()) retireRecentsCover()
        } else {
            recentsCover.hide()
        }
        if (effect.lockForegroundIfNeeded && wasUp && !lastWindowWasHost) {
            val fg = LockState.foreground()
            if (fg != null && LockStore.isLocked(this, fg) && !LockState.isUnlocked(fg) &&
                !recentsCover.isShowing()
            ) {
                showLockScreen(fg)
            }
        }
    }

    private fun retireRecentsCover() {
        if (recentsCoverRetired) {
            recentsCover.hide()
            return
        }
        recentsCoverRetired = true
        coverState = RecentsCoverMachine.State()
        handler.removeCallbacks(overviewCheck)
        handler.removeCallbacks(coverWatchdog)
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
        LockStore.removeListener(locksChanged)
        recentsCover.hide()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        LockStore.removeListener(locksChanged)
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
