package com.achillesrogan.applock

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process

/** Reads setup state off the device and remembers optional steps the user skipped. */
object SetupStatus {

    private const val PREFS = "setup_prefs"
    private const val KEY_SKIPPED = "skipped_steps"

    fun snapshot(context: Context): SetupPlan.Snapshot {
        val app = context.applicationContext
        return SetupPlan.Snapshot(
            sdkInt = Build.VERSION.SDK_INT,
            fingerprintReady = Biometrics.isAvailable(app),
            accessibilityEnabled = AppLockService.isEnabled(app),
            restrictedSettingsMode = restrictedSettingsMode(app),
            unknownInstaller = unknownInstaller(app),
            deviceAdminActive = DeviceAdmin.isActive(app),
            batteryUnrestricted = batteryUnrestricted(app),
            skipped = skipped(app),
        )
    }

    fun steps(context: Context): List<SetupPlan.Step> = SetupPlan.steps(snapshot(context))

    fun requiredComplete(context: Context): Boolean = SetupPlan.requiredComplete(steps(context))

    fun skip(context: Context, id: SetupPlan.StepId) {
        if (SetupPlan.requirement(id) != SetupPlan.Requirement.OPTIONAL) return
        val updated = skipped(context).map { it.name }.toMutableSet()
        updated += id.name
        prefs(context).edit().putStringSet(KEY_SKIPPED, HashSet(updated)).apply()
    }

    private fun skipped(context: Context): Set<SetupPlan.StepId> {
        val raw = prefs(context).getStringSet(KEY_SKIPPED, emptySet()).orEmpty()
        return raw.mapNotNull { name ->
            runCatching { SetupPlan.StepId.valueOf(name) }.getOrNull()
        }.toSet()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * App-op that Android 13+ uses to grey out a sideloaded accessibility switch.
     * Null on older releases, or when this build of Android does not recognize the op.
     */
    private fun restrictedSettingsMode(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return null
        return try {
            appOps.unsafeCheckOpNoThrow(
                "android:access_restricted_settings",
                Process.myUid(),
                context.packageName,
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun unknownInstaller(context: Context): Boolean {
        val installer = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        } catch (e: Exception) {
            null
        }
        return installer.isNullOrBlank()
    }

    private fun batteryUnrestricted(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }
}
