package com.example.soundboard

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.soundboard.databinding.ItemSoundBinding

class SoundAdapter(
    private val items: List<Sound>,
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit
) : RecyclerView.Adapter<SoundAdapter.ViewHolder>() {

    var playingId: Long? = null
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    class ViewHolder(val binding: ItemSoundBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding =
            ItemSoundBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val sound = items[position]
        holder.binding.buttonName.text = sound.name.ifEmpty { "Sound ${position + 1}" }
        val categories = listOfNotNull(sound.category1, sound.category2).joinToString(", ")
        holder.binding.buttonCategories.text = categories
        holder.binding.buttonCategories.isVisible = categories.isNotEmpty()
        if (sound.id == playingId) {
            val tv = TypedValue()
            holder.itemView.context.theme.resolveAttribute(
                androidx.appcompat.R.attr.colorPrimary, tv, true
            )
            holder.binding.root.setStrokeColor(tv.data)
            holder.binding.root.setStrokeWidth(6)
        } else {
            holder.binding.root.setStrokeWidth(0)
        }
        holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
        holder.itemView.setOnLongClickListener {
            onLongClick(holder.bindingAdapterPosition)
            true
        }
    }

    override fun getItemCount(): Int = items.size
}
