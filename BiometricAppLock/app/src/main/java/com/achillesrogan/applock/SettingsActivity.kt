package com.achillesrogan.applock

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Uninstall protection, Recents cover, and setup. Hidden until a fingerprint
 * succeeds, same as the app list. System back and the arrow both return to it.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var content: View
    private lateinit var authGate: View
    private lateinit var protectionSwitch: MaterialSwitch
    private lateinit var hideRecentsSwitch: MaterialSwitch
    private lateinit var stricterRecentsSwitch: MaterialSwitch
    private lateinit var stricterRecentsBlock: View
    private lateinit var recentsFingerprintSwitch: MaterialSwitch
    private lateinit var recentsFingerprintBlock: View
    private lateinit var disclaimerSummary: View
    private lateinit var disclaimerBody: TextView
    private lateinit var disclaimerDetails: MaterialButton

    private var authenticated = false
    private var authInProgress = false
    private var updatingProtectionSwitch = false
    private var updatingHideRecents = false
    private var updatingStricterRecents = false
    private var updatingRecentsFingerprint = false
    private var disclaimerExpanded = false
    private var showingFullDisclaimer = false

    private val enableAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disclaimerExpanded = savedInstanceState?.getBoolean(STATE_DISCLAIMER_EXPANDED) == true
        prepareEdgeToEdgeWindow()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_settings)
        content = findViewById(R.id.settingsScroll)
        content.visibility = View.GONE
        authGate = findViewById(R.id.settingsAuthGate)
        findViewById<View>(R.id.settingsBack).setOnClickListener { finish() }
        val root = findViewById<View>(R.id.settingsRoot)
        val contentPadH = findViewById<View>(R.id.settingsContent).paddingLeft
        val contentPadTop = findViewById<View>(R.id.settingsContent).paddingTop
        val contentPadBottom = findViewById<View>(R.id.settingsContent).paddingBottom
        root.listenForBarPadding { bars ->
            findViewById<View>(R.id.settingsContent).setPadding(
                contentPadH + bars.left,
                contentPadTop + bars.top,
                contentPadH + bars.right,
                contentPadBottom + bars.bottom,
            )
        }

        protectionSwitch = findViewById(R.id.uninstallProtectionSwitch)
        protectionSwitch.setOnCheckedChangeListener { _, checked -> onProtectionToggled(checked) }
        hideRecentsSwitch = findViewById(R.id.hideRecentsSwitch)
        stricterRecentsSwitch = findViewById(R.id.stricterRecentsSwitch)
        stricterRecentsBlock = findViewById(R.id.stricterRecentsBlock)
        hideRecentsSwitch.setOnCheckedChangeListener { _, checked ->
            if (!updatingHideRecents && authenticated) RecentsGuard.setEnabled(this, checked)
            refreshStricterRecentsSwitch()
        }
        stricterRecentsSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingStricterRecents || !authenticated) return@setOnCheckedChangeListener
            RecentsGuard.setStricter(this, checked)
        }
        recentsFingerprintSwitch = findViewById(R.id.recentsFingerprintSwitch)
        recentsFingerprintBlock = findViewById(R.id.recentsFingerprintBlock)
        recentsFingerprintSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingRecentsFingerprint || !authenticated) return@setOnCheckedChangeListener
            RecentsGuard.setFingerprint(this, checked)
        }
        disclaimerSummary = findViewById(R.id.uninstallDisclaimerSummary)
        disclaimerBody = findViewById(R.id.uninstallDisclaimerBody)
        disclaimerDetails = findViewById(R.id.uninstallDisclaimerDetails)
        disclaimerDetails.setOnClickListener {
            disclaimerExpanded = !disclaimerExpanded
            refreshDisclaimer()
        }
        findViewById<MaterialButton>(R.id.reviewSetupButton).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_DISCLAIMER_EXPANDED, disclaimerExpanded)
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        if (!authenticated) {
            content.visibility = View.GONE
            authGate.visibility = View.VISIBLE
            if (!authInProgress) requireAuth()
            return
        }
        bindControls()
    }

    override fun onStop() {
        if (showingFullDisclaimer) SetupStatus.markDisclaimerSeen(this)
        showingFullDisclaimer = false
        super.onStop()
        authenticated = false
        authInProgress = false
        content.visibility = View.GONE
        authGate.visibility = View.VISIBLE
    }

    private fun requireAuth() {
        if (authenticated || authInProgress || isFinishing) return
        if (Biometrics.unavailableReason(this) != null) {
            finish()
            return
        }
        authInProgress = true
        Biometrics.authenticate(
            activity = this,
            title = getString(R.string.settings_unlock_title),
            onSuccess = {
                authInProgress = false
                if (isFinishing || isDestroyed || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    return@authenticate
                }
                authenticated = true
                authGate.visibility = View.GONE
                content.visibility = View.VISIBLE
                bindControls()
            },
            onCancel = {
                authInProgress = false
                finish()
            },
            onError = {
                authInProgress = false
                finish()
            },
        )
    }

    private fun bindControls() {
        refreshProtectionSwitch()
        refreshHideRecentsSwitch()
        refreshDisclaimer()
    }

    private fun refreshHideRecentsSwitch() {
        val enabled = RecentsGuard.isEnabled(this)
        if (hideRecentsSwitch.isChecked != enabled) {
            updatingHideRecents = true
            hideRecentsSwitch.isChecked = enabled
            updatingHideRecents = false
        }
        refreshStricterRecentsSwitch()
    }

    private fun refreshStricterRecentsSwitch() {
        val stored = RecentsGuard.isStricterStored(this)
        if (stricterRecentsSwitch.isChecked != stored) {
            updatingStricterRecents = true
            stricterRecentsSwitch.isChecked = stored
            updatingStricterRecents = false
        }
        val parentOn = hideRecentsSwitch.isChecked
        stricterRecentsSwitch.isEnabled = parentOn
        stricterRecentsBlock.alpha = if (parentOn) 1f else 0.45f
        refreshRecentsFingerprintSwitch()
    }

    private fun refreshRecentsFingerprintSwitch() {
        val stored = RecentsGuard.isFingerprintStored(this)
        if (recentsFingerprintSwitch.isChecked != stored) {
            updatingRecentsFingerprint = true
            recentsFingerprintSwitch.isChecked = stored
            updatingRecentsFingerprint = false
        }
        val parentOn = hideRecentsSwitch.isChecked
        recentsFingerprintSwitch.isEnabled = parentOn
        recentsFingerprintBlock.alpha = if (parentOn) 1f else 0.45f
    }

    private fun refreshProtectionSwitch() {
        val active = DeviceAdmin.isActive(this)
        if (protectionSwitch.isChecked == active) return
        updatingProtectionSwitch = true
        protectionSwitch.isChecked = active
        updatingProtectionSwitch = false
    }

    private fun onProtectionToggled(checked: Boolean) {
        if (updatingProtectionSwitch || !authenticated) return
        val active = DeviceAdmin.isActive(this)
        if (checked == active) return
        if (checked) {
            if (!AppLockService.isEnabled(this)) {
                Toast.makeText(this, R.string.uninstall_protection_needs_service, Toast.LENGTH_LONG).show()
            }
            enableAdminLauncher.launch(DeviceAdmin.enableIntent(this))
            refreshProtectionSwitch()
        } else if (!DeviceAdmin.deactivate(this)) {
            Toast.makeText(this, R.string.device_admin_change_failed, Toast.LENGTH_LONG).show()
            refreshProtectionSwitch()
        } else {
            protectionSwitch.postDelayed({
                if (!isDestroyed) refreshProtectionSwitch()
            }, 300)
        }
    }

    private fun refreshDisclaimer() {
        if (content.visibility != View.VISIBLE) return
        val collapse = UninstallDisclaimer.collapsed(
            setupComplete = SetupStatus.requiredComplete(this),
            seen = SetupStatus.disclaimerSeen(this),
        )
        if (!collapse) {
            showingFullDisclaimer = true
            disclaimerSummary.visibility = View.GONE
            disclaimerBody.visibility = View.VISIBLE
            return
        }
        disclaimerSummary.visibility = View.VISIBLE
        disclaimerBody.visibility = if (disclaimerExpanded) View.VISIBLE else View.GONE
        disclaimerDetails.setText(
            if (disclaimerExpanded) R.string.uninstall_protection_hide else R.string.uninstall_protection_details,
        )
    }

    companion object {
        private const val STATE_DISCLAIMER_EXPANDED = "disclaimer_expanded"
    }
}
