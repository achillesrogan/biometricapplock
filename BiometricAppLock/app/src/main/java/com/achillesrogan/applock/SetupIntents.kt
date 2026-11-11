package com.achillesrogan.applock

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.biometric.BiometricManager

/**
 * Opens the system screen for one setup step, trying the closest deep link first.
 * A missing activity on an OEM build falls through to the next intent.
 */
object SetupIntents {

    private const val ACCESSIBILITY_DETAILS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
    private const val DEVICE_ADMIN_SETTINGS = "android.settings.DEVICE_ADMIN_SETTINGS"
    private const val FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

    fun open(context: Context, step: SetupPlan.StepId): Boolean {
        val intents = intentsFor(context, step).onEach { it.addCategory(Intent.CATEGORY_DEFAULT) }
        // Prefer screens this phone actually has. If none resolve, still try:
        // some OEM builds hide settings activities from resolveActivity.
        val resolvable = intents.filter { it.resolveActivity(context.packageManager) != null }
        val ordered = resolvable.ifEmpty { intents }
        for (intent in ordered) {
            if (start(context, intent)) return true
        }
        return false
    }

    fun intentsFor(context: Context, step: SetupPlan.StepId): List<Intent> = when (step) {
        SetupPlan.StepId.FINGERPRINT -> fingerprintIntents()
        SetupPlan.StepId.RESTRICTED_SETTINGS -> appDetailsIntents(context)
        SetupPlan.StepId.ACCESSIBILITY -> accessibilityIntents(context)
        SetupPlan.StepId.DEVICE_ADMIN -> deviceAdminIntents(context)
        SetupPlan.StepId.BATTERY -> batteryIntents(context)
    }

    private fun accessibilityIntents(context: Context): List<Intent> {
        val component = ComponentName(context, AppLockService::class.java)
        val flat = component.flattenToString()
        val args = Bundle().apply { putString(FRAGMENT_ARG_KEY, flat) }
        return listOf(
            Intent(ACCESSIBILITY_DETAILS).putExtra(Intent.EXTRA_COMPONENT_NAME, flat),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(FRAGMENT_ARG_KEY, flat)
                .putExtra(SHOW_FRAGMENT_ARGS, args),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
    }

    private fun appDetailsIntents(context: Context): List<Intent> {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
        return listOf(
            details,
            Intent(Settings.ACTION_APPLICATION_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
    }

    private fun deviceAdminIntents(context: Context): List<Intent> = listOf(
        DeviceAdmin.enableIntent(context),
        Intent(DEVICE_ADMIN_SETTINGS),
        Intent(Settings.ACTION_SECURITY_SETTINGS),
    )

    private fun batteryIntents(context: Context): List<Intent> {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        return listOf(
            direct,
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        ) + appDetailsIntents(context)
    }

    private fun fingerprintIntents(): List<Intent> {
        val intents = mutableListOf<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            intents += Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
                Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                BiometricManager.Authenticators.BIOMETRIC_STRONG,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            intents += Intent(Settings.ACTION_FINGERPRINT_ENROLL)
        }
        intents += Intent(Settings.ACTION_SECURITY_SETTINGS)
        return intents
    }

    private fun start(context: Context, intent: Intent): Boolean {
        return try {
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        } catch (e: RuntimeException) {
            // A few OEM settings screens throw instead of returning ActivityNotFound.
            false
        }
    }
}
