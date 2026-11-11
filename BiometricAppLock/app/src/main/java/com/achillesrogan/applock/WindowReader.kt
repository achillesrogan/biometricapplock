package com.achillesrogan.applock

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Fields copied out of an [AccessibilityEvent] before the system recycles it. */
internal data class PendingWatch(
    val eventPackage: String,
    val className: String,
    val fromClick: Boolean,
    val clickedText: String?,
    val clickedViewId: String?,
    val prefetchedTexts: List<String>,
    val prefetchedViewIds: List<String>,
)

/**
 * Reads on-screen text for one installer or Settings window.
 * Callers must already have decided the package is one we are allowed to inspect.
 */
internal object WindowReader {

    private const val MAX_NODES = 250
    private const val MAX_DEPTH = 40

    fun pendingFrom(event: AccessibilityEvent, eventPackage: String, fromClick: Boolean): PendingWatch {
        val texts = ArrayList<String>()
        val viewIds = ArrayList<String>()
        event.text?.forEach { value ->
            value?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
        }
        event.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
        var clickedText: String? = null
        var clickedViewId: String? = null
        val source = event.source
        if (source != null) {
            try {
                if (fromClick) {
                    clickedText = source.text?.toString() ?: source.contentDescription?.toString()
                    clickedViewId = source.viewIdResourceName
                    clickedText?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
                    clickedViewId?.let(viewIds::add)
                }
            } finally {
                @Suppress("DEPRECATION")
                source.recycle()
            }
        }
        return PendingWatch(
            eventPackage = eventPackage,
            className = event.className?.toString().orEmpty(),
            fromClick = fromClick,
            clickedText = clickedText,
            clickedViewId = clickedViewId,
            prefetchedTexts = texts,
            prefetchedViewIds = viewIds,
        )
    }

    fun read(service: AccessibilityService, pending: PendingWatch): UninstallSignals.Snapshot? {
        val root = service.rootInActiveWindow
        if (root == null) {
            if (pending.prefetchedTexts.isEmpty() && pending.clickedText == null) return null
            return snapshot(pending, pending.className, pending.prefetchedTexts, pending.prefetchedViewIds, null)
        }
        return try {
            val rootPackage = root.packageName?.toString()
            if (rootPackage != pending.eventPackage) return null
            val texts = ArrayList<String>(pending.prefetchedTexts)
            val viewIds = ArrayList<String>(pending.prefetchedViewIds)
            val header = arrayOfNulls<String>(1)
            val counter = intArrayOf(0)
            collect(root, texts, viewIds, header, counter, 0)
            val className = pending.className.ifEmpty { root.className?.toString().orEmpty() }
            snapshot(pending, className, texts, viewIds, header[0])
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    /** Active window, if it belongs to a package installer or Settings. */
    fun readActive(service: AccessibilityService): UninstallSignals.Snapshot? {
        val root = service.rootInActiveWindow ?: return null
        return try {
            val pkg = root.packageName?.toString() ?: return null
            if (pkg !in UninstallSignals.WATCHED_PACKAGES) return null
            val texts = ArrayList<String>()
            val viewIds = ArrayList<String>()
            val header = arrayOfNulls<String>(1)
            collect(root, texts, viewIds, header, intArrayOf(0), 0)
            UninstallSignals.Snapshot(
                eventPackage = pkg,
                className = root.className?.toString().orEmpty(),
                texts = texts,
                viewIds = viewIds,
                headerTitle = header[0],
                clickedText = null,
                clickedViewId = null,
                fromClick = false,
            )
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    private fun snapshot(
        pending: PendingWatch,
        className: String,
        texts: List<String>,
        viewIds: List<String>,
        header: String?,
    ) = UninstallSignals.Snapshot(
        eventPackage = pending.eventPackage,
        className = className,
        texts = texts,
        viewIds = viewIds,
        headerTitle = header,
        clickedText = pending.clickedText,
        clickedViewId = pending.clickedViewId,
        fromClick = pending.fromClick,
    )

    private fun collect(
        node: AccessibilityNodeInfo,
        texts: MutableList<String>,
        viewIds: MutableList<String>,
        header: Array<String?>,
        seen: IntArray,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH || seen[0] >= MAX_NODES) return
        seen[0]++
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
        val viewId = node.viewIdResourceName
        if (!viewId.isNullOrEmpty()) {
            viewIds += viewId
            if (header[0] == null && isHeaderId(viewId)) {
                header[0] = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
        for (i in 0 until node.childCount) {
            if (seen[0] >= MAX_NODES) return
            val child = node.getChild(i) ?: continue
            try {
                collect(child, texts, viewIds, header, seen, depth + 1)
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
    }

    private fun isHeaderId(viewId: String): Boolean {
        val lower = viewId.lowercase()
        return lower.endsWith("/entity_header_title") ||
            lower.endsWith("/app_name") ||
            lower.endsWith("/header_title")
    }
}
