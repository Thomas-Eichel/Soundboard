package com.example.soundboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var peaks: FloatArray = FloatArray(0)
        set(value) {
            field = value
            invalidate()
        }

    var selStart: Float = 0f
        set(value) {
            field = value.coerceIn(0f, selEnd)
            invalidate()
        }

    var selEnd: Float = 1f
        set(value) {
            field = value.coerceIn(selStart, 1f)
            invalidate()
        }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.LTGRAY
    }
    private var selectedColor: Int = Color.RED

    init {
        val tv = TypedValue()
        if (context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true)) {
            selectedColor = tv.data
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawLine(0f, h / 2f, w, h / 2f, linePaint)
        val n = peaks.size
        if (n == 0) {
            barPaint.color = Color.LTGRAY
            canvas.drawRect(0f, h * 0.45f, w, h * 0.55f, barPaint)
            return
        }
        val barW = w / n
        for (i in 0 until n) {
            val pos = i.toFloat() / n
            val inSel = pos >= selStart && pos <= selEnd
            barPaint.color = if (inSel) selectedColor else Color.argb(160, 176, 190, 197)
            val amp = peaks[i].coerceIn(0f, 1f)
            val barH = (amp * h * 0.9f).coerceAtLeast(h * 0.04f)
            val x = i * barW
            canvas.drawRect(x, (h - barH) / 2f, x + barW * 0.8f, (h + barH) / 2f, barPaint)
        }
    }
}
