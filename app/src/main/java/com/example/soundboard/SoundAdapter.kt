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

    val playCounts = HashMap<Long, Int>()
    val durationsMs = HashMap<Long, Long>()

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
        applyItemHeight(holder, sound.isHeader)

        if (sound.isHeader) {
            holder.binding.card.cardElevation = 0f
            holder.binding.card.setCardBackgroundColor(Color.TRANSPARENT)
            holder.binding.card.setStrokeWidth(0)
            holder.binding.buttonImage.isVisible = false
            holder.binding.buttonCategory1.isVisible = false
            holder.binding.buttonCategory2.isVisible = false
            holder.binding.footer.isVisible = false
            holder.binding.contentLayout.setPadding(0, 0, 0, 0)
            holder.binding.buttonName.text = sound.name.ifEmpty {
                holder.itemView.context.getString(R.string.header_placeholder)
            }
            holder.binding.buttonName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            val tv = TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true)
            holder.binding.buttonName.setTextColor((tv.data and 0x00FFFFFF) or 0x99000000.toInt())
        } else {
            holder.binding.card.cardElevation = defaultElev
            holder.binding.footer.isVisible = true
            val pad = (8 * density).toInt()
            holder.binding.contentLayout.setPadding(pad, pad, pad, pad)
            holder.binding.buttonName.text = sound.name.ifEmpty { "Sound ${position + 1}" }
            holder.binding.buttonName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            holder.binding.buttonCategory1.text = sound.category1 ?: ""
            holder.binding.buttonCategory1.isVisible = !sound.category1.isNullOrEmpty()
            holder.binding.buttonCategory2.text = sound.category2 ?: ""
            holder.binding.buttonCategory2.isVisible = !sound.category2.isNullOrEmpty()

            val count = playCounts[sound.id] ?: 0
            if (count > 0) {
                holder.binding.playCount.text = count.toString()
                holder.binding.playCount.isVisible = true
            } else {
                holder.binding.playCount.isVisible = false
            }
            val durMs = durationsMs[sound.id]
            if (durMs != null && durMs > 0) {
                holder.binding.duration.text = formatDuration(durMs)
                holder.binding.duration.isVisible = true
            } else {
                holder.binding.duration.isVisible = false
            }

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
                holder.binding.card.setCardBackgroundColor(bgColor)
            } else {
                holder.binding.card.setCardBackgroundColor(DEFAULT_TILE_COLOR)
            }

            if (sound.id == playingId) {
                val tv = TypedValue()
                theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true)
                holder.binding.card.setStrokeColor(tv.data)
                holder.binding.card.setStrokeWidth(6)
            } else {
                holder.binding.card.setStrokeWidth(0)
            }
        }

        holder.binding.card.setOnClickListener { onClick(holder.bindingAdapterPosition) }
        holder.binding.card.setOnLongClickListener {
            onLongClick(holder.bindingAdapterPosition)
            true
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

    fun applyItemHeight(holder: ViewHolder, isHeader: Boolean) {
        val density = holder.itemView.resources.displayMetrics.density
        val lp = holder.binding.card.layoutParams
        lp.height = ((if (isHeader) HEADER_HEIGHT_DP else TILE_HEIGHT_DP) * density).toInt()
        holder.binding.card.layoutParams = lp
    }

    companion object {
        private const val TILE_HEIGHT_DP = 92
        private const val HEADER_HEIGHT_DP = 30
        private val DEFAULT_TILE_COLOR = Color.parseColor("#E0E0E0")

        private fun formatDuration(ms: Long): String {
            val totalSec = ms / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) {
                String.format("%d:%02d:%02d", h, m, s)
            } else {
                String.format("%d:%02d", m, s)
            }
        }
    }
}
