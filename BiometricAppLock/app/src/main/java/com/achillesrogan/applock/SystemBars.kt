package com.achillesrogan.applock

import android.os.Build
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Target SDK 35 draws edge-to-edge. Make the system bars transparent, turn off the
 * contrast scrim (it shows up as a line through the status bar), and report the
 * insets the screens should pad for. Covers gesture navigation, 3-button navigation,
 * display cutouts, and the keyboard.
 */
internal fun AppCompatActivity.prepareEdgeToEdgeWindow() {
    enableEdgeToEdge()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        @Suppress("DEPRECATION")
        window.isStatusBarContrastEnforced = false
        @Suppress("DEPRECATION")
        window.isNavigationBarContrastEnforced = false
    }
}

internal data class BarPadding(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal fun WindowInsetsCompat.barPadding(): BarPadding {
    val bars = getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
    val ime = getInsets(WindowInsetsCompat.Type.ime())
    return BarPadding(
        left = bars.left,
        top = bars.top,
        right = bars.right,
        bottom = maxOf(bars.bottom, ime.bottom),
    )
}

internal fun View.listenForBarPadding(onPadding: (BarPadding) -> Unit) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
        onPadding(insets.barPadding())
        WindowInsetsCompat.CONSUMED
    }
    ViewCompat.requestApplyInsets(this)
}
