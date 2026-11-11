package com.achillesrogan.applock

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.google.android.material.button.MaterialButton

/**
 * Full-screen cover shown over a locked app, or over a protected uninstall /
 * force-stop / clear-storage / device-admin screen, until a strong biometric
 * unlocks the keystore key. If biometrics are unavailable, this screen stays up.
 */
class LockActivity : AppCompatActivity() {

    private enum class Mode { APP, ACTION }

    private lateinit var targetPkg: String
    private var targetLabel: String = ""
    private var mode = Mode.APP
    private var action: UninstallSignals.Action? = null
    private var protectPackages: Set<String> = emptySet()
    private var protectionPassed = false

    private lateinit var icon: ImageView
    private lateinit var title: TextView
    private lateinit var detail: TextView
    private lateinit var status: TextView
    private lateinit var unlockButton: MaterialButton

    private var promptOnResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prepareEdgeToEdgeWindow()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_lock)
        val root = findViewById<View>(R.id.lockRoot)
        val padH = root.paddingLeft
        val padV = root.paddingTop
        root.listenForBarPadding { bars ->
            root.setPadding(padH + bars.left, padV + bars.top, padH + bars.right, padV + bars.bottom)
        }
        icon = findViewById(R.id.targetIcon)
        title = findViewById(R.id.lockedTitle)
        detail = findViewById(R.id.detailText)
        status = findViewById(R.id.statusText)
        unlockButton = findViewById(R.id.unlockButton)
        unlockButton.setOnClickListener { prompt() }

        // Back never reveals the locked app or completes the protected action.
        onBackPressedDispatcher.addCallback(this) { leave() }

        if (!bindTarget(intent)) goHome()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (bindTarget(intent)) promptOnResume = true
    }

    override fun onResume() {
        super.onResume()
        if (promptOnResume) {
            promptOnResume = false
            prompt()
        }
    }

    override fun onStop() {
        super.onStop()
        if (mode == Mode.ACTION) {
            // Leaving without a fingerprint cancels the system action underneath.
            if (!protectionPassed && !isChangingConfigurations) {
                LockState.suppressProtectionUntil = SystemClock.elapsedRealtime() + 2500
                if (ProtectionController.dismissUnderlying == null) {
                    if (!isFinishing) goHome()
                } else {
                    ProtectionController.dismiss()
                    if (!isFinishing) finish()
                }
            }
            LockState.protectionActive = false
        }
        if (mode == Mode.APP && ::targetPkg.isInitialized && LockState.lockScreenFor == targetPkg) {
            LockState.lockScreenFor = null
        }
        promptOnResume = true
    }

    private fun bindTarget(intent: Intent): Boolean {
        val actionName = intent.getStringExtra(EXTRA_ACTION)
        if (actionName != null) return bindProtection(intent, actionName)
        return bindApp(intent)
    }

    private fun bindApp(intent: Intent): Boolean {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return false
        mode = Mode.APP
        action = null
        protectPackages = emptySet()
        protectionPassed = false
        targetPkg = pkg
        LockState.lockScreenFor = pkg
        targetLabel = try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            icon.setImageDrawable(packageManager.getApplicationIcon(info))
            packageManager.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            icon.setImageResource(R.drawable.ic_lock)
            pkg
        }
        title.text = getString(R.string.app_is_locked, targetLabel)
        detail.visibility = View.GONE
        detail.text = ""
        status.text = ""
        unlockButton.setText(R.string.unlock_button)
        return true
    }

    private fun bindProtection(intent: Intent, actionName: String): Boolean {
        val parsed = runCatching { UninstallSignals.Action.valueOf(actionName) }.getOrNull() ?: return false
        val packages = intent.getStringArrayListExtra(EXTRA_PACKAGES)?.filter { it.isNotBlank() }.orEmpty()
        if (packages.isEmpty()) return false
        mode = Mode.ACTION
        action = parsed
        protectPackages = packages.toSet()
        protectionPassed = false
        targetPkg = packages.first()
        // Do not set lockScreenFor. This cover is not the app-lock session for that package.
        LockState.protectionActive = true
        targetLabel = intent.getStringExtra(EXTRA_LABEL)?.takeIf { it.isNotBlank() } ?: targetPkg
        showAppIcon(targetPkg)
        title.text = protectionTitle(parsed, targetLabel)
        detail.setText(R.string.protect_detail)
        detail.visibility = View.VISIBLE
        status.text = ""
        unlockButton.setText(R.string.protect_confirm_button)
        return true
    }

    private fun protectionTitle(parsed: UninstallSignals.Action, label: String): String = when (parsed) {
        UninstallSignals.Action.UNINSTALL -> getString(R.string.protect_uninstall_title, label)
        UninstallSignals.Action.FORCE_STOP -> getString(R.string.protect_force_stop_title, label)
        UninstallSignals.Action.CLEAR_STORAGE -> getString(R.string.protect_clear_title, label)
        UninstallSignals.Action.DEVICE_ADMIN -> getString(R.string.protect_admin_title)
    }

    private fun showAppIcon(pkg: String) {
        try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            icon.setImageDrawable(packageManager.getApplicationIcon(info))
        } catch (e: PackageManager.NameNotFoundException) {
            icon.setImageResource(R.drawable.ic_lock)
        }
    }

    private fun prompt() {
        if (!::targetPkg.isInitialized) return
        val reason = Biometrics.unavailableReason(this)
        if (reason != null) {
            status.text = if (mode == Mode.ACTION) {
                getString(R.string.protect_stays_blocked, reason)
            } else {
                getString(R.string.lock_stays_locked, reason)
            }
            return
        }
        status.text = ""
        val promptTitle = if (mode == Mode.ACTION) {
            protectionTitle(action ?: return, targetLabel)
        } else {
            getString(R.string.unlock_title, targetLabel)
        }
        Biometrics.authenticate(
            activity = this,
            title = promptTitle,
            onSuccess = { enrollmentChanged ->
                if (isFinishing || isDestroyed || !::targetPkg.isInitialized ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) {
                    return@authenticate
                }
                if (enrollmentChanged) {
                    Toast.makeText(this, R.string.biometric_rearmed, Toast.LENGTH_LONG).show()
                }
                if (mode == Mode.ACTION) {
                    protectionPassed = true
                    ProtectionGate.allow(protectPackages)
                    LockState.protectionActive = false
                    finish()
                    @Suppress("DEPRECATION")
                    overridePendingTransition(0, 0)
                } else {
                    LockState.unlock(targetPkg)
                    LockState.lockScreenFor = null
                    finish()
                    @Suppress("DEPRECATION")
                    overridePendingTransition(0, 0)
                }
            },
            onCancel = { leave() },
            onError = { msg -> status.text = msg },
        )
    }

    private fun leave() {
        if (mode == Mode.ACTION) {
            // onStop closes the system dialog and goes home once this window is gone.
            finish()
        } else {
            goHome()
        }
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
    }

    companion object {
        private const val EXTRA_PACKAGE = "target_package"
        private const val EXTRA_LABEL = "target_label"
        private const val EXTRA_PACKAGES = "target_packages"
        private const val EXTRA_ACTION = "protection_action"

        fun intent(context: Context, pkg: String): Intent =
            Intent(context, LockActivity::class.java)
                .putExtra(EXTRA_PACKAGE, pkg)
                .addFlags(launchFlags())

        fun protectionIntent(
            context: Context,
            label: String,
            packages: ArrayList<String>,
            action: String,
        ): Intent =
            Intent(context, LockActivity::class.java)
                .putExtra(EXTRA_PACKAGE, packages.firstOrNull().orEmpty())
                .putExtra(EXTRA_LABEL, label)
                .putStringArrayListExtra(EXTRA_PACKAGES, packages)
                .putExtra(EXTRA_ACTION, action)
                .addFlags(launchFlags())

        private fun launchFlags(): Int =
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
}
