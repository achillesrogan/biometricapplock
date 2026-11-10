package com.achillesrogan.applock

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
 * Full-screen cover shown over a locked app until a strong biometric unlocks
 * the keystore key. If biometrics are unavailable, this screen stays up.
 */
class LockActivity : AppCompatActivity() {

    private lateinit var targetPkg: String
    private var targetLabel: String = ""

    private lateinit var icon: ImageView
    private lateinit var title: TextView
    private lateinit var status: TextView

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
        status = findViewById(R.id.statusText)
        findViewById<MaterialButton>(R.id.unlockButton).setOnClickListener { prompt() }

        // Back never reveals the locked app; it goes to the home screen instead.
        onBackPressedDispatcher.addCallback(this) { goHome() }

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
        // Let the service show us again if the user comes back to the locked app later.
        if (::targetPkg.isInitialized && LockState.lockScreenFor == targetPkg) {
            LockState.lockScreenFor = null
        }
        promptOnResume = true
    }

    private fun bindTarget(intent: Intent): Boolean {
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return false
        targetPkg = pkg
        LockState.lockScreenFor = pkg
        targetLabel = try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            icon.setImageDrawable(packageManager.getApplicationIcon(info))
            packageManager.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
        title.text = getString(R.string.app_is_locked, targetLabel)
        status.text = ""
        return true
    }

    private fun prompt() {
        if (!::targetPkg.isInitialized) return
        val reason = Biometrics.unavailableReason(this)
        if (reason != null) {
            // Fail closed. Do not call LockState.unlock and do not finish onto the app.
            status.text = getString(R.string.lock_stays_locked, reason)
            return
        }
        status.text = ""
        Biometrics.authenticate(
            activity = this,
            title = getString(R.string.unlock_title, targetLabel),
            onSuccess = { enrollmentChanged ->
                if (isFinishing || isDestroyed || !::targetPkg.isInitialized ||
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) {
                    return@authenticate
                }
                if (enrollmentChanged) {
                    Toast.makeText(this, R.string.biometric_rearmed, Toast.LENGTH_LONG).show()
                }
                LockState.unlock(targetPkg)
                LockState.lockScreenFor = null
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            },
            onCancel = { goHome() },
            onError = { msg -> status.text = msg },
        )
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

        fun intent(context: Context, pkg: String): Intent =
            Intent(context, LockActivity::class.java)
                .putExtra(EXTRA_PACKAGE, pkg)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                )
    }
}
