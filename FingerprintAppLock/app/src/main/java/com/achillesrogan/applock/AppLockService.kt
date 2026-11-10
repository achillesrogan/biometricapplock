package com.achillesrogan.applock

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat

/**
 * Watches which app is in the foreground. When a locked app comes to the front and
 * hasn't been unlocked this session, it shows [LockActivity] on top of it.
 */
class AppLockService : AccessibilityService() {

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            LockState.relockAll()
            // The app left open stays in front when the screen comes back on without firing
            // a new window event, so cover it now; the lock screen will be waiting after unlock.
            val current = LockState.foreground() ?: return
            if (LockStore.isLocked(this@AppLockService, current)) showLockScreen(current)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ContextCompat.registerReceiver(
            this, screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        if (shouldIgnore(pkg)) return
        // Only react to real activities, not toasts/popups/dialog windows from other packages.
        if (!isActivity(pkg, event.className)) return

        LockState.onForegroundChanged(pkg)

        if (LockStore.isLocked(this, pkg) && !LockState.isUnlocked(pkg)) {
            showLockScreen(pkg)
        }
    }

    private fun showLockScreen(pkg: String) {
        if (LockState.lockScreenFor == pkg) return
        LockState.lockScreenFor = pkg
        startActivity(LockActivity.intent(this, pkg))
    }

    private fun shouldIgnore(pkg: String): Boolean =
        pkg == packageName ||               // our own lock screen / settings
            pkg == "com.android.systemui" || // notification shade, volume, etc.
            pkg in keyboardPackages()        // the keyboard popping up isn't leaving the app

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

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        super.onDestroy()
    }

    companion object {
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, AppLockService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }
    }
}
