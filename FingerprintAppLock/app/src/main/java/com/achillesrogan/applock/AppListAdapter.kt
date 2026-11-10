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
)

class AppListAdapter(
    private val onToggle: (AppEntry, Boolean) -> Unit,
) : ListAdapter<AppEntry, AppListAdapter.Holder>(Diff) {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val label: TextView = view.findViewById(R.id.appLabel)
        val pkg: TextView = view.findViewById(R.id.appPackage)
        val toggle: MaterialSwitch = view.findViewById(R.id.lockSwitch)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = getItem(position)
        holder.icon.setImageDrawable(entry.icon)
        holder.label.text = entry.label
        holder.pkg.text = entry.packageName
        holder.toggle.setOnCheckedChangeListener(null)
        holder.toggle.isChecked = entry.locked
        holder.toggle.contentDescription = entry.label
        holder.toggle.setOnCheckedChangeListener { _, checked -> onToggle(entry, checked) }
        holder.itemView.setOnClickListener { holder.toggle.toggle() }
    }

    private object Diff : DiffUtil.ItemCallback<AppEntry>() {
        override fun areItemsTheSame(a: AppEntry, b: AppEntry) = a.packageName == b.packageName
        override fun areContentsTheSame(a: AppEntry, b: AppEntry) =
            a.label == b.label && a.locked == b.locked
    }
}
