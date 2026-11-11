package com.achillesrogan.applock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Device-admin self-protection. Active admin is the uninstall-protection switch. */
object DeviceAdmin {

    fun component(context: Context): ComponentName =
        ComponentName(context, AppDeviceAdminReceiver::class.java)

    fun isActive(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        return dpm.isAdminActive(component(context))
    }

    fun enableIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                context.getString(R.string.device_admin_explanation),
            )

    /** The app may remove its own administrator. Returns false if the system refused. */
    fun deactivate(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        val admin = component(context)
        if (!dpm.isAdminActive(admin)) return true
        return try {
            dpm.removeActiveAdmin(admin)
            true
        } catch (e: SecurityException) {
            false
        }
    }
}

/** Labels for this app and every locked app. This app is protected even though it is not in the list. */
fun protectedLabels(context: Context): Map<String, String> {
    val pm = context.packageManager
    val packages = LinkedHashSet<String>()
    packages += context.packageName
    packages += LockStore.lockedPackages(context)
    val labels = LinkedHashMap<String, String>(packages.size)
    for (pkg in packages) {
        labels[pkg] = try {
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString().ifBlank { pkg }
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }
    return labels
}

/**
 * After a fingerprint succeeds, the same packages can be confirmed on the system
 * dialog without a second prompt. Cleared when the screen turns off.
 */
object ProtectionGate {
    private const val HOLD_MS = 60_000L
    private val until = HashMap<String, Long>()

    @Synchronized
    fun allow(packages: Collection<String>, now: Long = SystemClock.elapsedRealtime()) {
        val deadline = now + HOLD_MS
        for (pkg in packages) until[pkg] = deadline
    }

    @Synchronized
    fun isAllowed(pkg: String, now: Long = SystemClock.elapsedRealtime()): Boolean {
        val deadline = until[pkg] ?: return false
        if (now > deadline) {
            until.remove(pkg)
            return false
        }
        return true
    }

    @Synchronized
    fun clear() {
        until.clear()
    }
}

/**
 * Asks the accessibility service to close the system screen under the lock
 * after the fingerprint prompt is cancelled. The service owns the real work.
 */
object ProtectionController {
    @Volatile
    var dismissUnderlying: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var scheduled = false

    fun dismiss() {
        if (scheduled) return
        scheduled = true
        // Click Cancel immediately, while the system window is still behind us.
        // The service delays Back, which would otherwise hit this activity.
        dismissUnderlying?.invoke()
        main.postDelayed({ scheduled = false }, 1000)
    }
}
