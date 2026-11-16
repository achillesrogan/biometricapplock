package com.achillesrogan.applock

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager

/**
 * Full-screen opaque cover over the overview window.
 *
 * TYPE_ACCESSIBILITY_OVERLAY sits above activities, including the launcher and
 * SystemUI recents activities, and does not need the draw-over-other-apps
 * permission. FLAG_SECURE keeps a screenshot taken while the cover is up from
 * including that frame. The cover is not part of the locked app's task, so it
 * does not rewrite the snapshot the system already stored.
 */
internal class RecentsCover(
    private val service: AccessibilityService,
    private val onHome: () -> Unit,
) {
    private var shown: View? = null

    fun isShowing(): Boolean = shown != null

    fun show() {
        if (shown != null) return
        val wm = service.getSystemService(WindowManager::class.java) ?: return
        val view = LayoutInflater.from(service).inflate(R.layout.recents_cover, null)
        view.findViewById<View>(R.id.recentsCoverHome).setOnClickListener { onHome() }
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                onHome()
                true
            } else {
                false
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SECURE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                fitInsetsSides = 0
            }
        }
        try {
            wm.addView(view, params)
            view.requestFocus()
            shown = view
        } catch (e: RuntimeException) {
            // The accessibility token is not ready, or this build rejected the overlay.
        }
    }

    fun hide() {
        val view = shown ?: return
        shown = null
        val wm = service.getSystemService(WindowManager::class.java) ?: return
        try {
            wm.removeView(view)
        } catch (e: RuntimeException) {
            // Already detached.
        }
    }
}
