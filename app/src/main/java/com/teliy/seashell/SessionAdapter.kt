package com.teliy.seashell

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.teliy.seashell.databinding.ItemSessionBinding
import java.util.Locale
import kotlin.math.abs

/**
 * Adapter for the saved-server list on the main screen.
 */
class SessionAdapter(
    private val onClick: (SshSession) -> Unit,
    private val onLongClick: (SshSession) -> Unit
) : RecyclerView.Adapter<SessionAdapter.ViewHolder>() {

    private val items = mutableListOf<SshSession>()

    private val avatarColors = intArrayOf(
        0xFF0069A3.toInt(), 0xFF2E6B34.toInt(), 0xFF8C4A0F.toInt(),
        0xFF5E4EA8.toInt(), 0xFFA6304F.toInt(), 0xFF00696C.toInt(),
        0xFF4A545F.toInt(), 0xFF8A6D00.toInt()
    )

    fun submit(list: List<SshSession>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class ViewHolder(val binding: ItemSessionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemSessionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val s = items[position]
        val ctx = holder.binding.root.context
        val title = s.displayName()
        holder.binding.textName.text = title
        holder.binding.textAddress.text = s.address()
        holder.binding.avatar.text = title.take(1).uppercase(Locale.getDefault())
        holder.binding.avatar.backgroundTintList = ColorStateList.valueOf(
            avatarColors[abs(title.hashCode()) % avatarColors.size]
        )
        holder.binding.root.setOnClickListener { onClick(s) }
        holder.binding.root.setOnLongClickListener { onLongClick(s); true }
        holder.binding.root.contentDescription =
            ctx.getString(R.string.session_cd, title, s.address())
    }
}
