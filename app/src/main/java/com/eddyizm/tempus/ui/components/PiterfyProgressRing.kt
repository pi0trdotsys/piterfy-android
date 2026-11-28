package com.eddyizm.tempus.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

/**
 * Piterfy: mały pierścień postępu (0–1) na okładce wznawianego utworu/odcinka: półprzezroczyste tło, pełny łuk w kolorze
 * akcentu od godziny 12 zgodnie z ruchem wskazówek. Bez tekstu; opis dla czytników ekranu ustawia adapter.
 */
class PiterfyProgressRing @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66000000 }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x99000000.toInt() }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        color = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0xFF1DB954.toInt())
    }
    private val box = RectF()

    /** Postęp 0–1; poza zakresem jest przycinany. */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val stroke = width * 0.12f
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        val half = stroke / 2f
        box.set(half, half, width - half, height - half)
        canvas.drawCircle(width / 2f, height / 2f, width / 2f, disc)
        canvas.drawArc(box, 0f, 360f, false, track)
        if (progress > 0f) canvas.drawArc(box, -90f, 360f * progress, false, arc)
    }
}
