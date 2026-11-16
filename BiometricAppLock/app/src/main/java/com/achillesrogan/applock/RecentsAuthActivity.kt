package com.achillesrogan.applock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.google.android.material.button.MaterialButton

/**
 * Opaque cover over Recents that asks for a strong biometric.
 * BiometricPrompt needs an activity; the accessibility overlay cannot host it,
 * and on Samsung that overlay sits above an activity, so the overlay is hidden
 * while this window is up. Home and back always leave. Cancel keeps this window up.
 */
class RecentsAuthActivity : AppCompatActivity() {

    private var succeeded = false
    private var promptShowing = false
    private var asked = false
    private lateinit var status: TextView

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isFinishing) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_recents_auth)
        status = findViewById(R.id.recentsAuthStatus)
        findViewById<MaterialButton>(R.id.recentsAuthRetry).setOnClickListener { prompt() }
        findViewById<MaterialButton>(R.id.recentsCoverHome).setOnClickListener { leave() }
        onBackPressedDispatcher.addCallback(this) { leave() }
        ContextCompat.registerReceiver(
            this,
            screenOff,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onResume() {
        super.onResume()
        if (!succeeded && !promptShowing && !asked) {
            asked = true
            prompt()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!promptShowing && !succeeded && !isFinishing) finish()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOff) }
        if (!succeeded) RecentsFingerprintGate.authFinished(granted = false)
        super.onDestroy()
    }

    private fun prompt() {
        if (succeeded || promptShowing || isFinishing) return
        promptShowing = true
        try {
            Biometrics.authenticate(
                activity = this,
                title = getString(R.string.recents_auth_title),
                onSuccess = {
                    promptShowing = false
                    if (isFinishing || isDestroyed || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        return@authenticate
                    }
                    succeeded = true
                    RecentsFingerprintGate.grant()
                    RecentsFingerprintGate.authFinished(granted = true)
                    finish()
                },
                onCancel = {
                    promptShowing = false
                    if (!isDestroyed) status.setText(R.string.recents_auth_failed)
                },
                onError = {
                    promptShowing = false
                    if (!isDestroyed) status.setText(R.string.recents_auth_failed)
                },
            )
        } catch (e: Exception) {
            promptShowing = false
            if (!isDestroyed) status.setText(R.string.recents_auth_failed)
        }
    }

    private fun leave() {
        try {
            RecentsFingerprintGate.home()
        } catch (e: Exception) {
            // Home still has to work if the service callback fails.
        }
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            // finish() below is what keeps this window from trapping the user.
        }
        finish()
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, RecentsAuthActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
    }
}
