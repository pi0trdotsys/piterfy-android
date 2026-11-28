package com.eddyizm.tempus.ui.components

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide
import com.eddyizm.tempus.R
import com.eddyizm.tempus.glide.CustomGlideRequest
import com.eddyizm.tempus.util.PiterfyArtistTimeline
import com.google.android.material.color.MaterialColors

/**
 * Piterfy: pozioma oś czasu dyskografii na stronie wykonawcy. Kolumna na rok (od najnowszego), pod rokiem okładki albumów;
 * nowe wydania z radaru (spoza biblioteki) mają plakietkę „Nowość”. Dane układa [PiterfyArtistTimeline].
 */
class PiterfyArtistTimelineView(context: Context) : HorizontalScrollView(context) {
    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = Math.round(v * d)

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        clipToPadding = false
        setPadding(dp(12), dp(4), dp(12), dp(8))
        addView(row)
    }

    /** Buduje kolumny lat; [onLibrary] dostaje id albumu z biblioteki, [onNew] pozycję z radaru. */
    fun show(timeline: List<PiterfyArtistTimeline.Year>, onLibrary: java.util.function.Consumer<String>, onNew: java.util.function.Consumer<PiterfyArtistTimeline.Entry>) {
        row.removeAllViews()
        val accent = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0xFF1DB954.toInt())
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0xFFFFFFFF.toInt())
        timeline.forEach { year ->
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(10), 0) }
            // nagłówek roku z kropką osi
            val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val dot = View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (year.entries.any { it.isNew }) accent else (onSurface and 0x00FFFFFF) or 0x66000000) }
            }
            head.addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(6) })
            head.addView(TextView(context).apply {
                text = if (year.year > 0) year.year.toString() else "—"
                setTypeface(typeface, Typeface.BOLD); textSize = 15f; setTextColor(onSurface)
            })
            column.addView(head)
            val line = View(context).apply { setBackgroundColor((onSurface and 0x00FFFFFF) or 0x33000000) }
            column.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(6); bottomMargin = dp(8) })
            val covers = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            year.entries.forEach { entry -> covers.addView(card(entry, accent, onSurface, onLibrary, onNew)) }
            column.addView(covers)
            row.addView(column)
        }
    }

    private fun card(entry: PiterfyArtistTimeline.Entry, accent: Int, onSurface: Int, onLibrary: java.util.function.Consumer<String>, onNew: java.util.function.Consumer<PiterfyArtistTimeline.Entry>): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(8), 0)
            isClickable = true
            contentDescription = if (entry.isNew) context.getString(R.string.ux_artist_new_release_note, entry.title) else entry.title
            setOnClickListener { if (entry.albumId != null) onLibrary.accept(entry.albumId) else onNew.accept(entry) }
        }
        val frame = android.widget.FrameLayout(context)
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { setColor((onSurface and 0x00FFFFFF) or 0x22000000); cornerRadius = dp(8).toFloat() }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        frame.addView(image, android.widget.FrameLayout.LayoutParams(dp(88), dp(88)))
        if (entry.isNew) {
            val badge = TextView(context).apply {
                text = context.getString(R.string.ux_artist_new_release)
                textSize = 10f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF000000.toInt())
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(8).toFloat() }
            }
            frame.addView(badge, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(dp(4), dp(4), 0, 0) })
        }
        box.addView(frame)
        box.addView(TextView(context).apply {
            text = entry.title; textSize = 12f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setTextColor(onSurface)
        }, LinearLayout.LayoutParams(dp(88), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        // okładka: z biblioteki przez Subsonic, nowość z radaru po adresie https od Deezera
        if (entry.albumId != null && entry.coverId != null) {
            CustomGlideRequest.Builder.from(context, entry.coverId, CustomGlideRequest.ResourceType.Album).build().into(image)
        } else if (entry.coverId != null) {
            Glide.with(context).load(entry.coverId).centerCrop().into(image)
        } else {
            image.setImageResource(R.drawable.ic_placeholder_album)
        }
        return box
    }
}
