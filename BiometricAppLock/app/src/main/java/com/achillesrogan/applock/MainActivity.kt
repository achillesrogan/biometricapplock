package com.achillesrogan.applock

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Settings screen: pick which apps to lock.
 * The list stays hidden until a strong biometric unlocks the keystore key.
 * If that biometric is unavailable, the list is never shown.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var content: View
    private lateinit var authGate: View
    private lateinit var authTitle: TextView
    private lateinit var authMessage: TextView
    private lateinit var enrollButton: MaterialButton
    private lateinit var banner: MaterialCardView
    private lateinit var reviewSetupButton: MaterialButton
    private lateinit var protectionSwitch: MaterialSwitch
    private lateinit var lockedCount: TextView
    private lateinit var emptySearch: TextView
    private lateinit var progress: ProgressBar
    private lateinit var appList: RecyclerView

    private val adapter = AppListAdapter { entry, locked -> onToggle(entry, locked) }
    private var allApps: List<AppEntry> = emptyList()
    private var query = ""

    private var authenticated = false
    private var authInProgress = false
    private var updatingProtectionSwitch = false
    private var offeredSetup = false

    private val enableAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { /* The switch matches device-admin state after the next fingerprint. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        offeredSetup = savedInstanceState?.getBoolean(STATE_OFFERED_SETUP) == true
        // Do not read an authenticated flag from savedInstanceState. Rotation and
        // process death must prompt again, and the lock list must not flash first.
        prepareEdgeToEdgeWindow()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_main)

        content = findViewById(R.id.content)
        content.isSaveFromParentEnabled = false
        content.visibility = View.GONE
        authGate = findViewById(R.id.authGate)
        authTitle = findViewById(R.id.authTitle)
        authMessage = findViewById(R.id.authMessage)
        enrollButton = findViewById(R.id.enrollButton)
        enrollButton.setOnClickListener { openEnrollment() }
        banner = findViewById(R.id.serviceBanner)
        lockedCount = findViewById(R.id.lockedCount)
        emptySearch = findViewById(R.id.emptySearch)
        progress = findViewById(R.id.progress)
        appList = findViewById(R.id.appList)
        appList.layoutManager = LinearLayoutManager(this)
        appList.adapter = adapter
        applyWindowInsets()
        findViewById<MaterialButton>(R.id.openSettingsButton).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        reviewSetupButton = findViewById(R.id.reviewSetupButton)
        reviewSetupButton.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        protectionSwitch = findViewById(R.id.uninstallProtectionSwitch)
        protectionSwitch.setOnCheckedChangeListener { _, checked -> onProtectionToggled(checked) }
        findViewById<TextInputEditText>(R.id.searchInput).doAfterTextChanged {
            query = it?.toString().orEmpty()
            applyFilter()
        }

        // First frame is the gate, never the lock list. Prompting waits until onResume.
        Biometrics.unavailableReason(this)?.let { showBlocked(it) }
    }

    override fun onStart() {
        super.onStart()
        // Hide before the window is drawn. onStop already cleared auth; this covers
        // any return path that would otherwise paint the lock list for a frame.
        if (!authenticated) {
            content.visibility = View.GONE
            authGate.visibility = View.VISIBLE
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_OFFERED_SETUP, offeredSetup)
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        // First open walks through system settings before the fingerprint gate,
        // so each setup screen is not followed by another fingerprint prompt.
        if (!offeredSetup && !SetupStatus.requiredComplete(this)) {
            offeredSetup = true
            startActivity(Intent(this, SetupActivity::class.java))
            return
        }
        if (!authenticated) {
            content.visibility = View.GONE
            authGate.visibility = View.VISIBLE
            if (!authInProgress) requireAuth()
            return
        }
        if (!Biometrics.isAvailable(this)) {
            showBlocked(Biometrics.unavailableReason(this) ?: getString(R.string.biometric_unavailable))
            return
        }
        refreshSetupEntry()
        refreshProtectionSwitch()
    }

    override fun onStop() {
        super.onStop()
        // Re-lock whenever this screen leaves the foreground, including rotation.
        // Hiding here means the lock list is already gone when the window is shown again.
        authenticated = false
        authInProgress = false
        concealSettings()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (!authenticated) {
            content.visibility = View.GONE
            authGate.visibility = View.VISIBLE
        }
    }

    private fun requireAuth() {
        if (authenticated || authInProgress || isFinishing) return
        val reason = Biometrics.unavailableReason(this)
        if (reason != null) {
            showBlocked(reason)
            return
        }
        concealSettings()
        authTitle.setText(R.string.auth_required_title)
        authMessage.setText(R.string.auth_required_body)
        enrollButton.visibility = View.GONE
        authInProgress = true
        Biometrics.authenticate(
            activity = this,
            title = getString(R.string.settings_unlock_title),
            onSuccess = { enrollmentChanged ->
                authInProgress = false
                // A prompt that completes after the screen has stopped must not open settings.
                if (isFinishing || isDestroyed ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) {
                    authenticated = false
                    concealSettings()
                    return@authenticate
                }
                authenticated = true
                authGate.visibility = View.GONE
                content.visibility = View.VISIBLE
                refreshSetupEntry()
                refreshProtectionSwitch()
                if (enrollmentChanged) {
                    Toast.makeText(this, R.string.biometric_rearmed, Toast.LENGTH_LONG).show()
                }
                loadApps()
            },
            onCancel = {
                authInProgress = false
                finish()
            },
            onError = { msg ->
                authInProgress = false
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                finish()
            },
        )
    }

    /** Fail closed: the lock list is cleared and stays off screen. */
    private fun showBlocked(reason: String) {
        authenticated = false
        authInProgress = false
        concealSettings()
        authTitle.setText(R.string.auth_required_title)
        authMessage.text = getString(R.string.no_biometric_blocked, reason)
        val canEnroll = Biometrics.status(this) == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
        enrollButton.visibility = if (canEnroll) View.VISIBLE else View.GONE
    }

    private fun concealSettings() {
        content.visibility = View.GONE
        authGate.visibility = View.VISIBLE
        allApps = emptyList()
        adapter.submitList(emptyList())
        emptySearch.visibility = View.GONE
    }

    /**
     * Keep the title, search field, and lock list below the status bar, and let the
     * list scroll until the last row clears the navigation bar (gesture or 3-button).
     */
    private fun applyWindowInsets() {
        val contentHorizontal = content.paddingLeft
        val contentTop = content.paddingTop
        val gateHorizontal = authGate.paddingLeft
        val gateVertical = authGate.paddingTop
        val listBottom = appList.paddingBottom
        findViewById<View>(R.id.root).listenForBarPadding { bars ->
            authGate.setPadding(
                gateHorizontal + bars.left,
                gateVertical + bars.top,
                gateHorizontal + bars.right,
                gateVertical + bars.bottom,
            )
            content.setPadding(contentHorizontal + bars.left, contentTop + bars.top, contentHorizontal + bars.right, 0)
            appList.updatePadding(bottom = listBottom + bars.bottom)
        }
    }

    private fun refreshSetupEntry() {
        val needed = !SetupStatus.requiredComplete(this)
        banner.visibility = if (needed) View.VISIBLE else View.GONE
        reviewSetupButton.visibility = if (needed) View.GONE else View.VISIBLE
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

    private fun openEnrollment() {
        if (!SetupIntents.open(this, SetupPlan.StepId.FINGERPRINT)) {
            Toast.makeText(this, R.string.no_biometric_enrolled, Toast.LENGTH_LONG).show()
        }
    }

    private fun loadApps() {
        if (!authenticated) return
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { queryLaunchableApps() }
            if (!authenticated) return@launch
            allApps = apps
            progress.visibility = View.GONE
            applyFilter()
        }
    }

    private fun queryLaunchableApps(): List<AppEntry> {
        val pm = packageManager
        val locked = LockStore.lockedPackages(this)
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val identities = pm.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName }
            .map {
                AppIdentity(
                    label = pm.getApplicationLabel(it).toString(),
                    packageName = it.packageName,
                    locked = it.packageName in locked,
                )
            }
        val icons = identities.associate { identity ->
            identity.packageName to pm.getApplicationIcon(identity.packageName)
        }
        return AppListOrder.place(identities, Locale.getDefault()).map { placed ->
            AppEntry(
                label = placed.label,
                packageName = placed.packageName,
                icon = icons.getValue(placed.packageName),
                locked = placed.locked,
                section = placed.section,
            )
        }
    }

    private fun onToggle(entry: AppEntry, locked: Boolean) {
        if (!authenticated) return
        LockStore.setLocked(this, entry.packageName, locked)
        // Keep the row where it was. The next load sorts locked apps back to the top.
        allApps = allApps.map { if (it.packageName == entry.packageName) it.copy(locked = locked) else it }
        applyFilter()
    }

    private fun applyFilter() {
        val raw = query.trim()
        val shown = AppListOrder.filter(
            allApps,
            raw,
            Locale.getDefault(),
            { it.label },
            { it.packageName },
        )
        adapter.submitList(sectionRows(shown))
        lockedCount.text = getString(R.string.locked_count, allApps.count { it.locked })
        val noMatches = raw.isNotEmpty() && shown.isEmpty() && progress.visibility != View.VISIBLE
        emptySearch.visibility = if (noMatches) View.VISIBLE else View.GONE
        if (noMatches) emptySearch.text = getString(R.string.no_search_matches, raw)
    }

    companion object {
        private const val STATE_OFFERED_SETUP = "offered_setup"
    }
}
