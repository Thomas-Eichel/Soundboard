package com.example.soundboard

import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.example.soundboard.databinding.ItemSoundBinding

class SoundAdapter(
    private val items: List<Sound>,
    private val onClick: (Int) -> Unit,
    private val onLongClick: (Int) -> Unit,
    private val onStartDrag: (ViewHolder) -> Unit
) : RecyclerView.Adapter<SoundAdapter.ViewHolder>() {

    var playingId: Long? = null
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    var dragEnabled: Boolean = true
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
        val theme = holder.itemView.context.theme
        val density = holder.itemView.resources.displayMetrics.density
        val defaultElev = 2f * density
        val lp = holder.itemView.layoutParams
        lp.height = ((if (sound.isHeader) HEADER_HEIGHT_DP else TILE_HEIGHT_DP) * density).toInt()
        holder.itemView.layoutParams = lp

        if (sound.isHeader) {
            holder.binding.root.cardElevation = 0f
            holder.binding.root.setCardBackgroundColor(Color.TRANSPARENT)
            holder.binding.root.setStrokeWidth(0)
            holder.binding.buttonImage.isVisible = false
            holder.binding.buttonCategory1.isVisible = false
            holder.binding.buttonCategory2.isVisible = false
            holder.binding.buttonName.text = sound.name.ifEmpty {
                holder.itemView.context.getString(R.string.header_placeholder)
            }
            val tv = TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true)
            holder.binding.buttonName.setTextColor((tv.data and 0x00FFFFFF) or 0x99000000.toInt())
        } else {
            holder.binding.root.cardElevation = defaultElev
            holder.binding.buttonName.text = sound.name.ifEmpty { "Sound ${position + 1}" }
            holder.binding.buttonCategory1.text = sound.category1 ?: ""
            holder.binding.buttonCategory1.isVisible = !sound.category1.isNullOrEmpty()
            holder.binding.buttonCategory2.text = sound.category2 ?: ""
            holder.binding.buttonCategory2.isVisible = !sound.category2.isNullOrEmpty()

            val hasImage = sound.imageUri != null
            if (hasImage) {
                holder.binding.buttonImage.isVisible = true
                holder.binding.buttonImage.setImageURI(sound.imageUri)
            } else {
                holder.binding.buttonImage.setImageDrawable(null)
                holder.binding.buttonImage.isVisible = false
            }
            val textColor = if (hasImage) {
                Color.WHITE
            } else {
                val tv = TypedValue()
                theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true)
                tv.data
            }
            holder.binding.buttonName.setTextColor(textColor)
            holder.binding.buttonCategory1.setTextColor(Color.WHITE)
            holder.binding.buttonCategory2.setTextColor(Color.WHITE)

            val bgColor = sound.backgroundColor?.let { hex ->
                try {
                    Color.parseColor(hex)
                } catch (_: Exception) {
                    null
                }
            }
            if (bgColor != null) {
                holder.binding.root.setCardBackgroundColor(bgColor)
            } else {
                val tv = TypedValue()
                theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, tv, true)
                holder.binding.root.setCardBackgroundColor(tv.data)
            }

            if (sound.id == playingId) {
                val tv = TypedValue()
                theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true)
                holder.binding.root.setStrokeColor(tv.data)
                holder.binding.root.setStrokeWidth(6)
            } else {
                holder.binding.root.setStrokeWidth(0)
            }
        }

        holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
        holder.itemView.setOnLongClickListener {
            onLongClick(holder.bindingAdapterPosition)
            true
        }
        holder.binding.dragHandle.isVisible = dragEnabled
        holder.binding.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && dragEnabled) {
                onStartDrag(holder)
            }
            true
        }
    }

    override fun getItemCount(): Int = items.size

    companion object {
        private const val TILE_HEIGHT_DP = 100
        private const val HEADER_HEIGHT_DP = 32
    }
}
