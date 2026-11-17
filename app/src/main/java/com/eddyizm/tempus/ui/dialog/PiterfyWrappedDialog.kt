package com.eddyizm.tempus.ui.dialog

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import com.eddyizm.tempus.R
import com.eddyizm.tempus.ui.components.PiterfyWrappedCard
import com.eddyizm.tempus.util.PiterfyLibrary
import com.eddyizm.tempus.util.PiterfyStatsFormat as F
import com.eddyizm.tempus.util.PiterfyStatsModel
import com.google.android.material.button.MaterialButton
import java.io.File

/**
 * Piterfy: Wrapped na pełnym ekranie, obok „Statystyk dla nerdów” (a nie w środku): wybór miesiąca albo roku, przycisk
 * „Odtwórz story”, karta do udostępnienia, typ słuchacza, porównanie z poprzednim okresem i najczęściej słuchane.
 * Dane liczy serwer (stats.py); ekran pokazuje ostatnią zapamiętaną odpowiedź od razu i odświeża ją w tle.
 */
class PiterfyWrappedDialog : PiterfyPageDialog() {
    private var stats: PiterfyStatsModel.Stats? = null
    private var periodKey: String? = null
    private var message: String? = null

    override fun pageTitle() = s(R.string.stats_tab_wrapped)

    override fun startLoading() = load()

    /** Wybiera okres (np. „2026”, „2026-11”); używa tego test na urządzeniu. */
    fun showPeriod(key: String?) {
        periodKey = key
        if (viewsReady) scroll.scrollTo(0, 0)
        render()
    }

    // ── dane ──
    private fun load() {
        stats = PiterfyStatsModel.parse(PiterfyLibrary.cachedStats())
        message = if (stats == null) s(R.string.stats_loading) else null
        render()
        PiterfyLibrary.stats({ json ->
            if (!isAdded) return@stats
            PiterfyStatsModel.parse(json)?.let { stats = it; message = null; render() }
        }, { reason ->
            if (!isAdded || stats != null) return@stats
            message = s(when (reason) { "https" -> R.string.stats_error_https; "not_ready" -> R.string.stats_error_not_ready; else -> R.string.stats_error_generic })
            render()
        })
    }

    private fun render() {
        if (!isAdded) return
        val data = stats
        subtitle.text = if (data?.generated != null) s(R.string.stats_updated, stamp(data.generated)) else ""
        body.removeAllViews()
        if (data == null) {
            showMessage(message ?: "", if (message != null && message != s(R.string.stats_loading)) ({ load() }) else null)
            return
        }
        wrapped(data)
        animateBody()
    }

    private fun wrapped(data: PiterfyStatsModel.Stats) {
        if (data.periods.isEmpty()) {
            add(text(s(R.string.stats_wrapped_empty, data.historySince?.let { " (" + F.day(it) + ")" }.orEmpty()), 15f, false, muted).apply { setPadding(dp(2), dp(12), 0, 0) })
            return
        }
        val period = data.periods.firstOrNull { it.key == periodKey } ?: data.periods.first()
        chips(data.periods.map { it.label }, data.periods.indexOf(period)) { i -> periodKey = data.periods[i].key; render() }

        add(MaterialButton(requireContext()).apply {
            text = s(R.string.story_play); isAllCaps = false; cornerRadius = dp(24); minHeight = dp(52)
            setOnClickListener { PiterfyStoryDialog.newInstance(period.key).show(parentFragmentManager, PiterfyStoryDialog.TAG) }
        }, 14)

        val image = ImageView(requireContext()).apply {
            adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
            val width = minOf(resources.displayMetrics.widthPixels - dp(32), 720)
            setImageBitmap(PiterfyWrappedCard.render(requireContext(), period, width))
            contentDescription = period.label
            clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = rounded(Color.TRANSPARENT, 20)
        }
        add(image, 12)
        add(MaterialButton(requireContext()).apply {
            text = s(R.string.stats_share); isAllCaps = false; cornerRadius = dp(24); minHeight = dp(48)
            setOnClickListener { share(period) }
        }, 12)

        val cmp = period.compare
        val listener = period.listener
        card().also { card ->
            card.addView(caption(s(R.string.stats_listener)))
            if (listener != null) {
                card.addView(text(listener.name, 26f, true, accent).apply { setPadding(0, dp(4), 0, dp(2)) })
                card.addView(text(listener.blurb, 14f, false, muted))
                listener.why.forEach { card.addView(text("• $it", 13f).apply { setPadding(0, dp(4), 0, 0) }) }
            } else {
                card.addView(text(s(R.string.stats_listener_none), 14f, false, muted).apply { setPadding(0, dp(6), 0, 0) })
            }
            add(card, 16)
        }
        if (cmp != null) card().also { card ->
            card.addView(caption(s(if (period.kind == "month") R.string.stats_compare_month else R.string.stats_compare_year)))
            fun line(label: String, pct: Int?) {
                if (pct == null) return
                val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
                row.addView(text(label, 14f), LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(text(if (pct == 0) s(R.string.stats_delta_none) else F.delta(pct), 14f, true, if (pct > 0) 0xFF5FF0A3.toInt() else if (pct < 0) 0xFFFF9A9A.toInt() else ink))
                card.addView(row)
            }
            line(s(R.string.stats_plays, F.num(cmp.prevPlays), F.num(period.plays)), cmp.plays)
            line(s(R.string.stats_minutes), cmp.minutes)
            line(s(R.string.stats_artists_distinct), cmp.artists)
            add(card, 12)
        }

        section(s(R.string.stats_top))
        barRows(period.topArtists.map { Triple(it.label, it.n.toDouble(), F.num(it.n)) })
        period.topTracks.forEachIndexed { i, t ->
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            row.addView(text("${i + 1}", 16f, true, accent), LinearLayout.LayoutParams(dp(28), -2))
            val names = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            names.addView(text(t.title, 14f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            names.addView(text(t.artist, 12f, false, muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            row.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text("${t.plays}×", 13f, true, muted))
            add(row, 12)
        }
        val facts = mutableListOf(s(R.string.stats_fact_tracks, F.num(period.tracks), F.num(period.artists)))
        facts += s(R.string.stats_fact_days, F.num(period.activeDays)) + if (period.longestStreak > 1) s(R.string.stats_fact_streak, period.longestStreak) else ""
        if ((period.newArtists ?: 0) > 0) facts += s(R.string.stats_fact_new, F.num(period.newArtists))
        period.busiestDay?.let { facts += s(R.string.stats_fact_busiest, F.day(it), F.num(period.busiestPlays)) }
        facts.forEach { fact(it) }
        if (period.partial) fact(s(R.string.stats_running_note))
    }

    private fun share(period: PiterfyStatsModel.Period) {
        val ctx = requireContext()
        try {
            val bitmap = PiterfyWrappedCard.render(ctx, period, 1080)
            val folder = File(ctx.cacheDir, "wrapped").apply { mkdirs() }
            folder.listFiles()?.forEach { it.delete() }
            val file = File(folder, "piterfy-wrapped-${period.key}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.update.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, "Piterfy Wrapped · ${period.label}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, s(R.string.stats_share)))
        } catch (_: Exception) {
            Toast.makeText(ctx, R.string.stats_share_failed, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val TAG = "piterfy_wrapped"
    }
}
