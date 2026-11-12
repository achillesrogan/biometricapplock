package com.achillesrogan.applock

/**
 * The long uninstall-protection explanation stays expanded until the user has
 * seen it, and it stays collapsed once setup is finished even if they have not.
 * Either condition is enough. The full text stays reachable from the summary.
 */
object UninstallDisclaimer {
    fun collapsed(setupComplete: Boolean, seen: Boolean): Boolean = setupComplete || seen
}
