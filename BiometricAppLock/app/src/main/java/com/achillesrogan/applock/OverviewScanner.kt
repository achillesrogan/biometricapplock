package com.achillesrogan.applock

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Looks only at view ids on launcher and SystemUI windows. It does not read
 * the text, content description, or notification contents on the overview cards.
 */
internal object OverviewScanner {
    private const val MAX_NODES = 180
    private const val MAX_DEPTH = 24

    /**
     * Null when no launcher or SystemUI window could be read. False only after
     * one of those windows was seen and none of them had a visible overview id.
     */
    fun isOverviewVisible(service: AccessibilityService): Boolean? {
        return try {
            val windows = service.windows ?: return null
            if (windows.isEmpty()) return null
            var sawHost = false
            var found = false
            try {
                for (window in windows) {
                    if (found) break
                    val root = try {
                        window.root
                    } catch (e: Exception) {
                        null
                    } ?: continue
                    val pkg = root.packageName?.toString().orEmpty()
                    val className = root.className?.toString().orEmpty()
                    if (!RecentsDetector.mayHostOverview(pkg, className)) continue
                    sawHost = true
                    if (containsVisibleOverview(root)) found = true
                }
            } finally {
                // Do not recycle. On Samsung One UI, recycle() on nodes from
                // AccessibilityWindowInfo.root double-frees and kills the service.
            }
            if (!sawHost) null else found
        } catch (e: Exception) {
            null
        }
    }

    private fun containsVisibleOverview(node: AccessibilityNodeInfo): Boolean {
        val seen = intArrayOf(0)
        return walk(node, seen, 0)
    }

    private fun walk(node: AccessibilityNodeInfo, seen: IntArray, depth: Int): Boolean {
        if (seen[0]++ > MAX_NODES || depth > MAX_DEPTH) return false
        if (node.isVisibleToUser && RecentsDetector.viewIdIsOverview(node.viewIdResourceName)) {
            return true
        }
        val count = node.childCount
        for (i in 0 until count) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            } ?: continue
            if (walk(child, seen, depth + 1)) return true
        }
        return false
    }
}
