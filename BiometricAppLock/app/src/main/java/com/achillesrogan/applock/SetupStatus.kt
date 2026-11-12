package com.achillesrogan.applock

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

/** Reads setup state off the device and remembers optional steps the user skipped. */
object SetupStatus {

    private const val PREFS = "setup_prefs"
    private const val KEY_SKIPPED = "skipped_steps"
    private const val KEY_DISCLAIMER_SEEN = "uninstall_disclaimer_seen"

    /**
     * Settings.Secure keys observed on One UI 6+ for Auto Blocker (package
     * com.samsung.android.rampart). Not a documented API. A missing key or a
     * value other than 0 or 1 is left unknown.
     */
    private const val KEY_MAIN = "rampart_main_switch_enabled"
    private const val KEY_UNAUTHORIZED = "rampart_blocked_unknown_apps"
    private const val KEY_USB = "rampart_blocked_adb_cmd"
    private const val KEY_MAXIMUM = "rampart_strict_protection_switch_enabled"

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
            autoBlocker = autoBlocker(app),
        )
    }

    fun disclaimerSeen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DISCLAIMER_SEEN, false)

    fun markDisclaimerSeen(context: Context) {
        if (disclaimerSeen(context)) return
        prefs(context).edit().putBoolean(KEY_DISCLAIMER_SEEN, true).apply()
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

    private fun autoBlocker(context: Context): AutoBlocker.Reading {
        val oneUi = oneUiVersion()
        val rampartInstalled = rampartInstalled(context)
        val present = AutoBlocker.present(Build.MANUFACTURER, oneUi, rampartInstalled)
        if (!present) return AutoBlocker.Reading.ABSENT
        return AutoBlocker.Reading(
            present = true,
            oneUiVersion = oneUi,
            main = readSwitch(context, KEY_MAIN),
            unauthorizedApps = readSwitch(context, KEY_UNAUTHORIZED),
            usbCommands = readSwitch(context, KEY_USB),
            maximumRestrictions = readSwitch(context, KEY_MAXIMUM),
        )
    }

    /**
     * Prefer `ro.build.version.oneui` (60000 = One UI 6.0). Fall back to
     * Samsung's `SEM_PLATFORM_INT`, which is that code plus 90000. Hidden-API
     * blocks and non-Samsung builds return null rather than a guessed version.
     */
    private fun oneUiVersion(): Int? {
        AutoBlocker.oneUiFromProperty(systemProperty("ro.build.version.oneui"))?.let { return it }
        val sem = semPlatformInt() ?: return null
        return AutoBlocker.oneUiFromSemPlatform(sem)
    }

    private fun systemProperty(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java, String::class.java)
            get.invoke(null, key, "") as? String
        } catch (e: Exception) {
            null
        }
    }

    private fun semPlatformInt(): Int? {
        return try {
            Build.VERSION::class.java.getDeclaredField("SEM_PLATFORM_INT").getInt(null)
        } catch (e: Exception) {
            null
        }
    }

    private fun rampartInstalled(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    AutoBlocker.RAMPART_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(AutoBlocker.RAMPART_PACKAGE, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun readSwitch(context: Context, key: String): AutoBlocker.SwitchState {
        val raw = try {
            Settings.Secure.getString(context.contentResolver, key)
        } catch (e: Exception) {
            null
        }
        return AutoBlocker.switchFromValues(listOf(raw))
    }

    private fun batteryUnrestricted(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }
}
