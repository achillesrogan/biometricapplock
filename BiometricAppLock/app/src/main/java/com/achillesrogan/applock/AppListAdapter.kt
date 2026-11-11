package com.achillesrogan.applock

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch

data class AppEntry(
    val label: String,
    val packageName: String,
    val icon: Drawable,
    val locked: Boolean,
    /** Section from the last sort. A toggle does not move the row to the other section. */
    val section: AppSection,
)

sealed class AppListRow {
    data class Header(val section: AppSection) : AppListRow()
    data class App(val entry: AppEntry) : AppListRow()
}

fun sectionRows(apps: List<AppEntry>): List<AppListRow> {
    val rows = ArrayList<AppListRow>(apps.size + 2)
    var previous: AppSection? = null
    for (app in apps) {
        if (app.section != previous) {
            rows += AppListRow.Header(app.section)
            previous = app.section
        }
        rows += AppListRow.App(app)
    }
    return rows
}

class AppListAdapter(
    private val onToggle: (AppEntry, Boolean) -> Unit,
) : ListAdapter<AppListRow, RecyclerView.ViewHolder>(Diff) {

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is AppListRow.Header -> VIEW_HEADER
        is AppListRow.App -> VIEW_APP
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_section, parent, false))
        } else {
            AppHolder(inflater.inflate(R.layout.item_app, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is AppListRow.Header -> (holder as HeaderHolder).bind(row.section)
            is AppListRow.App -> (holder as AppHolder).bind(row.entry, onToggle)
        }
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.sectionTitle)

        fun bind(section: AppSection) {
            val text = when (section) {
                AppSection.LOCKED -> R.string.section_locked
                AppSection.OTHER -> R.string.section_other
            }
            title.setText(text)
        }
    }

    class AppHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.appIcon)
        private val label: TextView = view.findViewById(R.id.appLabel)
        private val pkg: TextView = view.findViewById(R.id.appPackage)
        private val toggle: MaterialSwitch = view.findViewById(R.id.lockSwitch)

        fun bind(entry: AppEntry, onToggle: (AppEntry, Boolean) -> Unit) {
            icon.setImageDrawable(entry.icon)
            label.text = entry.label
            pkg.text = entry.packageName
            toggle.setOnCheckedChangeListener(null)
            toggle.isChecked = entry.locked
            toggle.contentDescription = entry.label
            toggle.setOnCheckedChangeListener { _, checked -> onToggle(entry, checked) }
            itemView.setOnClickListener { toggle.toggle() }
        }
    }

    private object Diff : DiffUtil.ItemCallback<AppListRow>() {
        override fun areItemsTheSame(a: AppListRow, b: AppListRow) = when {
            a is AppListRow.Header && b is AppListRow.Header -> a.section == b.section
            a is AppListRow.App && b is AppListRow.App -> a.entry.packageName == b.entry.packageName
            else -> false
        }

        override fun areContentsTheSame(a: AppListRow, b: AppListRow) = when {
            a is AppListRow.Header && b is AppListRow.Header -> true
            a is AppListRow.App && b is AppListRow.App ->
                a.entry.label == b.entry.label &&
                    a.entry.locked == b.entry.locked &&
                    a.entry.section == b.entry.section
            else -> false
        }
    }

    companion object {
        private const val VIEW_HEADER = 0
        private const val VIEW_APP = 1
    }
}
