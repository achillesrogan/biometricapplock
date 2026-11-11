package com.achillesrogan.applock

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * Checklist that opens each system setting the app needs, one at a time.
 * Coming back after a finished step opens the next unfinished one.
 * Backing out of a step does not open that same screen again until you tap it.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var intro: TextView
    private lateinit var stepsView: LinearLayout
    private lateinit var continueButton: MaterialButton

    /** Step whose system screen we last opened. Null until the user starts. */
    private var launchedStep: SetupPlan.StepId? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prepareEdgeToEdgeWindow()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_setup)
        intro = findViewById(R.id.setupIntro)
        stepsView = findViewById(R.id.setupSteps)
        continueButton = findViewById(R.id.setupContinue)
        continueButton.setOnClickListener { finish() }
        val content = findViewById<View>(R.id.setupContent)
        val padH = content.paddingLeft
        val padTop = content.paddingTop
        val padBottom = content.paddingBottom
        findViewById<View>(R.id.setupRoot).listenForBarPadding { bars ->
            content.setPadding(
                padH + bars.left,
                padTop + bars.top,
                padH + bars.right,
                padBottom + bars.bottom,
            )
        }
        launchedStep = savedInstanceState?.getString(STATE_LAUNCHED)?.let { name ->
            runCatching { SetupPlan.StepId.valueOf(name) }.getOrNull()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        launchedStep?.let { outState.putString(STATE_LAUNCHED, it.name) }
    }

    override fun onResume() {
        super.onResume()
        val steps = SetupStatus.steps(this)
        bind(steps)
        val launched = launchedStep ?: return
        val next = SetupPlan.autoAdvanceTarget(steps, launched) ?: return
        openStep(next)
    }

    private fun bind(steps: List<SetupPlan.Step>) {
        val current = SetupPlan.nextIncomplete(steps)?.id
        val requiredDone = SetupPlan.requiredComplete(steps)
        intro.setText(
            if (steps.all { it.done }) R.string.setup_intro_done else R.string.setup_intro,
        )
        continueButton.visibility = if (requiredDone) View.VISIBLE else View.GONE
        stepsView.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for ((index, step) in steps.withIndex()) {
            val row = inflater.inflate(R.layout.item_setup_step, stepsView, false)
            bindRow(row, step, current == step.id, index + 1)
            stepsView.addView(row)
        }
    }

    private fun bindRow(row: View, step: SetupPlan.Step, current: Boolean, number: Int) {
        val marker = row.findViewById<TextView>(R.id.stepMarker)
        val title = row.findViewById<TextView>(R.id.stepTitle)
        val state = row.findViewById<TextView>(R.id.stepState)
        val detail = row.findViewById<TextView>(R.id.stepDetail)
        val open = row.findViewById<MaterialButton>(R.id.stepOpen)
        val skip = row.findViewById<MaterialButton>(R.id.stepSkip)

        title.setText(titleFor(step.id))
        if (step.done) {
            marker.setText(R.string.setup_marker_done)
            state.setText(
                if (step.requirement == SetupPlan.Requirement.OPTIONAL && !actuallySatisfied(step.id)) {
                    R.string.setup_state_skipped
                } else {
                    R.string.setup_state_done
                },
            )
            detail.visibility = View.GONE
            open.visibility = View.GONE
            skip.visibility = View.GONE
            row.alpha = 0.72f
            return
        }

        row.alpha = 1f
        marker.text = number.toString()
        state.setText(
            if (step.requirement == SetupPlan.Requirement.REQUIRED) {
                R.string.setup_state_needed
            } else {
                R.string.setup_state_optional
            },
        )
        if (!current) {
            detail.visibility = View.GONE
            open.visibility = View.GONE
            skip.visibility = View.GONE
            return
        }

        detail.visibility = View.VISIBLE
        detail.setText(detailFor(step.id))
        open.visibility = View.VISIBLE
        open.setText(buttonFor(step.id))
        open.setOnClickListener { openStep(step.id) }
        val canSkip = step.requirement == SetupPlan.Requirement.OPTIONAL
        skip.visibility = if (canSkip) View.VISIBLE else View.GONE
        skip.setOnClickListener { skipStep(step.id) }
    }

    private fun actuallySatisfied(id: SetupPlan.StepId): Boolean {
        val snapshot = SetupStatus.snapshot(this)
        return when (id) {
            SetupPlan.StepId.FINGERPRINT -> snapshot.fingerprintReady
            SetupPlan.StepId.RESTRICTED_SETTINGS -> !SetupPlan.restrictedSettingsBlocked(snapshot)
            SetupPlan.StepId.ACCESSIBILITY -> snapshot.accessibilityEnabled
            SetupPlan.StepId.DEVICE_ADMIN -> snapshot.deviceAdminActive
            SetupPlan.StepId.BATTERY -> snapshot.batteryUnrestricted
        }
    }

    private fun openStep(id: SetupPlan.StepId) {
        launchedStep = id
        if (!SetupIntents.open(this, id)) {
            Toast.makeText(this, R.string.setup_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun skipStep(id: SetupPlan.StepId) {
        SetupStatus.skip(this, id)
        val steps = SetupStatus.steps(this)
        bind(steps)
        val next = SetupPlan.autoAdvanceTarget(steps, id) ?: return
        openStep(next)
    }

    private fun titleFor(id: SetupPlan.StepId): Int = when (id) {
        SetupPlan.StepId.FINGERPRINT -> R.string.setup_fingerprint_title
        SetupPlan.StepId.RESTRICTED_SETTINGS -> R.string.setup_restricted_title
        SetupPlan.StepId.ACCESSIBILITY -> R.string.setup_accessibility_title
        SetupPlan.StepId.DEVICE_ADMIN -> R.string.setup_admin_title
        SetupPlan.StepId.BATTERY -> R.string.setup_battery_title
    }

    private fun detailFor(id: SetupPlan.StepId): Int = when (id) {
        SetupPlan.StepId.FINGERPRINT -> R.string.setup_fingerprint_detail
        SetupPlan.StepId.RESTRICTED_SETTINGS -> R.string.setup_restricted_detail
        SetupPlan.StepId.ACCESSIBILITY -> R.string.setup_accessibility_detail
        SetupPlan.StepId.DEVICE_ADMIN -> R.string.setup_admin_detail
        SetupPlan.StepId.BATTERY -> R.string.setup_battery_detail
    }

    private fun buttonFor(id: SetupPlan.StepId): Int = when (id) {
        SetupPlan.StepId.FINGERPRINT -> R.string.setup_fingerprint_button
        SetupPlan.StepId.RESTRICTED_SETTINGS -> R.string.setup_restricted_button
        SetupPlan.StepId.ACCESSIBILITY -> R.string.setup_accessibility_button
        SetupPlan.StepId.DEVICE_ADMIN -> R.string.setup_admin_button
        SetupPlan.StepId.BATTERY -> R.string.setup_battery_button
    }

    companion object {
        private const val STATE_LAUNCHED = "launched_step"
    }
}
