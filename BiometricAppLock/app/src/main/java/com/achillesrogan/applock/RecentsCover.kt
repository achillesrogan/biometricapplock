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

    /** False when the overlay could not be attached. The caller should stop trying this session. */
    fun show(): Boolean {
        if (shown != null) return true
        val wm = service.getSystemService(WindowManager::class.java) ?: return false
        val view = try {
            LayoutInflater.from(service).inflate(R.layout.recents_cover, null)
        } catch (e: Exception) {
            return false
        }
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
        return try {
            wm.addView(view, params)
            shown = view
            try {
                view.requestFocus()
            } catch (e: Exception) {
                // The overlay is up. Focus is only for the Home key.
            }
            true
        } catch (e: Exception) {
            shown = null
            try {
                wm.removeView(view)
            } catch (ignored: Exception) {
                // addView did not keep the view.
            }
            false
        }
    }

    fun hide() {
        val view = shown ?: return
        shown = null
        val wm = service.getSystemService(WindowManager::class.java) ?: return
        try {
            wm.removeView(view)
        } catch (e: Exception) {
            // Already detached, or the token died with the service.
        }
    }
}
