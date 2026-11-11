package com.achillesrogan.applock

import java.text.Collator
import java.util.Locale

/** Where an app was placed the last time the list was sorted. Toggles do not change this. */
enum class AppSection { LOCKED, OTHER }

/** Fields the list is ordered by. Icons and other UI state stay outside this type. */
data class AppIdentity(
    val label: String,
    val packageName: String,
    val locked: Boolean,
)

data class PlacedApp(
    val label: String,
    val packageName: String,
    val locked: Boolean,
    val section: AppSection,
)

/**
 * Locked apps first, then the others. Each group is alphabetical, case-insensitive,
 * using the locale's own sort order. Package name breaks ties the same way.
 */
object AppListOrder {

    fun place(apps: List<AppIdentity>, locale: Locale): List<PlacedApp> {
        val collator = Collator.getInstance(locale).apply {
            strength = Collator.SECONDARY
        }
        return apps.sortedWith { a, b ->
            val group = groupOf(a.locked).compareTo(groupOf(b.locked))
            if (group != 0) return@sortedWith group
            val byLabel = collator.compare(a.label, b.label)
            if (byLabel != 0) return@sortedWith byLabel
            collator.compare(a.packageName, b.packageName)
        }.map { app ->
            PlacedApp(
                label = app.label,
                packageName = app.packageName,
                locked = app.locked,
                section = if (app.locked) AppSection.LOCKED else AppSection.OTHER,
            )
        }
    }

    /** Keeps the current order. An empty query returns the same sequence. */
    fun <T> filter(
        apps: List<T>,
        rawQuery: String,
        locale: Locale,
        label: (T) -> String,
        packageName: (T) -> String,
    ): List<T> {
        val query = rawQuery.trim().lowercase(locale)
        if (query.isEmpty()) return apps
        return apps.filter { app ->
            query in label(app).lowercase(locale) || query in packageName(app).lowercase(locale)
        }
    }

    private fun groupOf(locked: Boolean) = if (locked) 0 else 1
}
