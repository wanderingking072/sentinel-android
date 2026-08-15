package com.samourai.sentinel.ui.dojo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.samourai.sentinel.R

class CommunityDojoAdapter(
    private val onSelect: (CommunityDojoNode) -> Unit,
    private val onCopy: (CommunityDojoNode) -> Unit
) : ListAdapter<CommunityDojoNode, CommunityDojoAdapter.ViewHolder>(DiffCallback) {

    private object DiffCallback : DiffUtil.ItemCallback<CommunityDojoNode>() {
        override fun areItemsTheSame(oldItem: CommunityDojoNode, newItem: CommunityDojoNode) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: CommunityDojoNode, newItem: CommunityDojoNode) =
            oldItem == newItem
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val statusDot: View = view.findViewById(R.id.communityDojoStatusDot)
        val flag: TextView = view.findViewById(R.id.communityDojoFlag)
        val name: TextView = view.findViewById(R.id.communityDojoName)
        val subtitle: TextView = view.findViewById(R.id.communityDojoSubtitle)
        val copyButton: ImageButton = view.findViewById(R.id.communityDojoCopyButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_community_dojo, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val node = getItem(position)
        val context = holder.itemView.context

        val dotColorRes = if (node.isOnline) R.color.success_green else R.color.grey_accent
        val dotShape = ContextCompat.getDrawable(context, R.drawable.circle_shape)?.mutate()
        dotShape?.setTint(ContextCompat.getColor(context, dotColorRes))
        holder.statusDot.background = dotShape

        holder.name.text = node.name?.takeIf { it.isNotBlank() } ?: "Unnamed Dojo"

        val flag = node.flagEmoji
        if (flag != null) {
            holder.flag.text = flag
            holder.flag.visibility = View.VISIBLE
        } else {
            holder.flag.visibility = View.GONE
        }

        val subtitleParts = listOfNotNull(
            node.version?.takeIf { it.isNotBlank() }?.let { "v$it" },
            node.jurisdiction?.takeIf { it.isNotBlank() },
            if (node.isOnline) "online" else "offline"
        )
        holder.subtitle.text = subtitleParts.joinToString(" • ")

        holder.itemView.setOnClickListener { onSelect(node) }
        holder.copyButton.setOnClickListener { onCopy(node) }
    }
}
