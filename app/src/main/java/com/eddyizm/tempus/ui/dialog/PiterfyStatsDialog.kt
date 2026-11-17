package com.eddyizm.tempus.ui.dialog

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.eddyizm.tempus.R
import com.eddyizm.tempus.ui.components.ColumnsView
import com.eddyizm.tempus.ui.components.RingView
import com.eddyizm.tempus.ui.components.StackView
import com.eddyizm.tempus.util.PiterfyLibrary
import com.eddyizm.tempus.util.PiterfyMotion
import com.eddyizm.tempus.util.PiterfyStatsFormat as F
import com.eddyizm.tempus.util.PiterfyStatsModel
import com.eddyizm.tempus.util.PiterfyStatsModel.Tone
import com.google.android.material.color.MaterialColors

/**
 * Piterfy: „Statystyki dla nerdów” na pełnym ekranie, dwie zakładki: Biblioteka (anatomia biblioteki i rachunek jakości)
 * i Serwer (przyrost, dysk, skuteczność pobierania). Wrapped ma własny ekran obok ([PiterfyWrappedDialog]).
 * Liczy je serwer (stats.py); ekran pokazuje ostatnią zapamiętaną odpowiedź od razu i odświeża ją w tle.
 */
class PiterfyStatsDialog : PiterfyPageDialog() {
    private var stats: PiterfyStatsModel.Stats? = null
    private var tab = 0
    private var message: String? = null
    private lateinit var tabViews: List<TextView>

    private fun toneColor(tone: Tone) = when (tone) {
        Tone.GOOD, Tone.ACCENT -> accent
        Tone.WARN -> 0xFFF2C94C.toInt()
        Tone.BAD -> 0xFFFF6B6B.toInt()
        Tone.MUTED -> ColorUtils.setAlphaComponent(ink, 90)
        Tone.FREE -> track
    }

    override fun pageTitle() = s(R.string.stats_title)

    override fun addHeaderExtras(root: LinearLayout) {
        val ctx = requireContext()
        val tabRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val names = listOf(R.string.stats_tab_library, R.string.stats_tab_server)
        tabViews = names.mapIndexed { i, id ->
            TextView(ctx).apply {
                text = s(id); textSize = 14f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                setPadding(dp(18), 0, dp(18), 0); minHeight = dp(44)
                setOnClickListener { tab = i; scroll.scrollTo(0, 0); render() }
                PiterfyMotion.pressable(this)
            }.also { tabRow.addView(it, LinearLayout.LayoutParams(-2, dp(44)).apply { rightMargin = dp(8) }) }
        }
        root.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(tabRow) })
    }

    override fun startLoading() = load()

    /** Przełącza zakładkę (0 biblioteka, 1 serwer); używa tego test na urządzeniu. */
    fun showTab(index: Int) {
        tab = index.coerceIn(0, 1)
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

    // ── render ──
    private fun render() {
        if (!isAdded) return
        val data = stats
        tabViews.forEachIndexed { i, view ->
            val on = i == tab
            view.background = rounded(if (on) accent else cardBackground, 22)
            view.setTextColor(if (on) MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnPrimary, Color.BLACK) else ink)
            view.isSelected = on
        }
        subtitle.text = if (data?.generated != null) s(R.string.stats_updated, stamp(data.generated)) else ""
        body.removeAllViews()
        if (data == null) {
            showMessage(message ?: "", if (message != null && message != s(R.string.stats_loading)) ({ load() }) else null)
            return
        }
        if (tab == 0) library(data.library) else server(data)
        animateBody()
    }

    // ── Biblioteka ──
    private fun library(l: PiterfyStatsModel.Library) {
        tiles(listOf(
            Triple(s(R.string.stats_tracks), F.num(l.tracks), s(R.string.stats_albums_artists, F.num(l.albums), F.num(l.artists))),
            Triple(s(R.string.stats_time), F.duration(l.seconds), s(R.string.stats_time_sub, F.dec(l.seconds / 86400.0, 1))),
            Triple(s(R.string.stats_size), F.bytes(l.bytes), s(R.string.stats_size_sub, F.bytes(l.bytes / maxOf(l.tracks, 1)))),
            Triple(s(R.string.stats_lossless), F.percent(l.lossless.toDouble(), l.tracks.toDouble()), s(R.string.stats_lossless_sub, F.num(l.lossless), F.num(l.hires))),
            Triple(s(R.string.stats_unplayed), F.percent(l.unplayed.toDouble(), l.tracks.toDouble()), s(R.string.stats_unplayed_sub, F.num(l.unplayed))),
            Triple(s(R.string.stats_starred), F.num(l.starred), s(R.string.stats_starred_sub, F.percent(l.starred.toDouble(), l.tracks.toDouble(), 1))),
        ))

        val q = l.quality
        val segments = PiterfyStatsModel.qualitySegments(q)
        val checked = if (l.tracks > 0) Math.round(q.checked * 100.0 / l.tracks).toInt() else 0
        section(s(R.string.stats_quality), s(R.string.stats_quality_note, checked))
        add(StackView(requireContext(), segments.map { it.fraction to toneColor(it.tone) }, track), 0, dp(22))
        val names = mapOf("ok" to R.string.stats_q_ok, "suspect" to R.string.stats_q_suspect, "fake" to R.string.stats_q_fake, "pending" to R.string.stats_q_pending)
        val totalSegments = segments.sumOf { it.n }
        legend(segments.map { Triple(toneColor(it.tone), s(names.getValue(it.key)), F.num(it.n) + "  " + F.percent(it.n, totalSegments, 1)) })
        fact(s(R.string.stats_genuine, F.num(q.genuineLossless), F.num(l.lossless), F.num(l.hires)) +
            if (l.hiresUpsampled > 0) " " + s(R.string.stats_hires_fake, F.num(l.hiresUpsampled)) else "")
        if (q.replace.isNotEmpty()) {
            val list = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(4), 0, dp(4), dp(6)) }
            q.replace.take(15).forEach { r ->
                val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
                row.addView(text(r.title, 14f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                val kbps = r.sourceKbps?.let { " · ≈ MP3 $it kb/s" }.orEmpty()
                row.addView(text("${r.artist} · ${r.format.uppercase()}$kbps", 12f, false, muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                list.addView(row)
            }
            val holder = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; background = rounded(cardBackground, 14) }
            val toggle = text("▸ " + s(R.string.stats_replace, F.num(q.replaceTotal)), 14f, true).apply { setPadding(dp(14), dp(14), dp(14), dp(14)) }
            toggle.setOnClickListener {
                val open = list.visibility != View.VISIBLE
                list.visibility = if (open) View.VISIBLE else View.GONE
                toggle.text = (if (open) "▾ " else "▸ ") + s(R.string.stats_replace, F.num(q.replaceTotal))
            }
            holder.addView(toggle); holder.addView(list)
            add(holder, 12)
        }

        section(s(R.string.stats_formats))
        barRows(l.formats.map { Triple(it.label.uppercase(), it.n.toDouble(), F.num(it.n) + "  " + F.bytes(l.formatBytes[it.label] ?: 0)) })
        section(s(R.string.stats_bitrate))
        barRows(l.lossyBitrates.map { Triple(it.label, it.n.toDouble(), F.num(it.n)) })
        section(s(R.string.stats_samplerate))
        barRows(l.sampleRates.map { Triple(it.label, it.n.toDouble(), F.num(it.n)) })
        if (l.bitDepths.isNotEmpty()) fact(s(R.string.stats_bitdepth, l.bitDepths.joinToString(" · ") { "${it.label} bit × ${F.num(it.n)}" }))

        section(s(R.string.stats_decades), l.averageYear?.let { s(R.string.stats_avg_year, it) })
        val maxDecade = l.decades.maxOfOrNull { it.n } ?: 0
        add(HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            addView(ColumnsView(requireContext(), l.decades.map { Triple(it.label + "s", F.num(it.n), F.fraction(it.n.toDouble(), maxDecade.toDouble())) }, accent, track, ink, muted))
        })

        section(s(R.string.stats_genres))
        barRows(l.topGenres.map { Triple(it.label, it.n.toDouble(), F.num(it.n)) })

        section(s(R.string.stats_records))
        val records = listOfNotNull(
            l.oldest?.let { Triple(s(R.string.stats_rec_oldest), it, it.year?.toString().orEmpty()) },
            l.newest?.let { Triple(s(R.string.stats_rec_newest), it, it.year?.toString().orEmpty()) },
            l.longest?.let { Triple(s(R.string.stats_rec_longest), it, mmss(it.seconds ?: 0)) },
            l.shortest?.let { Triple(s(R.string.stats_rec_shortest), it, mmss(it.seconds ?: 0)) },
            l.biggest?.let { Triple(s(R.string.stats_rec_biggest), it, F.bytes(it.bytes ?: 0)) },
        )
        records.forEach { (label, rec, detail) ->
            val card = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; background = rounded(cardBackground, 14); setPadding(dp(14), dp(10), dp(14), dp(10)) }
            card.addView(text(label.uppercase(), 10.5f, true, muted).apply { letterSpacing = 0.08f })
            card.addView(text(rec.title, 15f, true))
            card.addView(text(listOf(rec.artist, detail).filter { it.isNotEmpty() }.joinToString(" · "), 12.5f, false, muted))
            add(card, 8)
        }
    }

    private fun mmss(seconds: Int) = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

    // ── Serwer ──
    private fun server(data: PiterfyStatsModel.Stats) {
        val sv = data.server
        val acq = sv.acquisition
        tiles(listOf(
            Triple(s(R.string.stats_new7), "+" + F.num(sv.added7), s(R.string.stats_new_sub, F.num(sv.added30))),
            Triple(s(R.string.stats_soulseek), F.percent(acq.got.toDouble(), acq.tried.toDouble()), s(R.string.stats_soulseek_sub, F.num(acq.got), F.num(acq.tried))),
            Triple(s(R.string.stats_hit), sv.hitRate?.let { "${Math.round(it * 100)}%" } ?: "—", s(R.string.stats_hit_sub, F.num(sv.liked), F.num(sv.trashed))),
            Triple(s(R.string.stats_scan), if (sv.scanning) s(R.string.stats_scanning) else stamp(sv.lastScan), s(R.string.stats_scan_sub)),
            Triple(s(R.string.stats_run), stamp(sv.lastRun), if (acq.pending > 0) s(R.string.stats_run_queue, F.num(acq.pending)) else s(R.string.stats_run_empty)),
        ))

        section(s(R.string.stats_growth), s(R.string.stats_growth_note) + (sv.firstAdded?.let { " · " + s(R.string.stats_growth_first, F.day(it)) } ?: ""))
        val maxWeek = sv.weeks.maxOfOrNull { it.n } ?: 0
        add(HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            addView(ColumnsView(requireContext(), sv.weeks.map { Triple(F.shortDay(it.day), if (it.n > 0) F.num(it.n) else "", F.fraction(it.n.toDouble(), maxWeek.toDouble())) }, accent, track, ink, muted))
        })

        section(s(R.string.stats_disk))
        val disk = PiterfyStatsModel.diskSegments(sv.disk, data.library.bytes, sv.trashBytes)
        if (disk == null) {
            add(text(s(R.string.stats_disk_none), 14f, false, muted))
        } else {
            val (parts, usedPct) = disk
            add(StackView(requireContext(), parts.map { it.fraction to toneColor(it.tone) }, track), 0, dp(22))
            val names = mapOf("library" to R.string.stats_disk_library, "trash" to R.string.stats_disk_trash, "other" to R.string.stats_disk_other, "free" to R.string.stats_disk_free)
            legend(parts.map { Triple(toneColor(it.tone), s(names.getValue(it.key)), F.bytes(it.n.toLong())) })
            fact(s(R.string.stats_disk_fact, usedPct, F.bytes(sv.disk!!.free), F.bytes(sv.disk.total)))
        }

        section(s(R.string.stats_acq), s(R.string.stats_acq_note))
        if (acq.outcomes.isEmpty()) add(text(s(R.string.stats_acq_none), 14f, false, muted))
        val outcomeNames = linkedMapOf("done" to R.string.stats_o_done, "pending" to R.string.stats_o_pending, "trashed" to R.string.stats_o_trashed,
            "notfound" to R.string.stats_o_notfound, "failed" to R.string.stats_o_failed, "fake" to R.string.stats_o_fake)
        barRows(outcomeNames.mapNotNull { (key, id) -> acq.outcomes[key]?.takeIf { it > 0 }?.let { Triple(s(id), it.toDouble(), F.num(it)) } })
        if (acq.engines.isNotEmpty()) rateTable(s(R.string.stats_engines), acq.engines.toSortedMap(), null)
        if (acq.pools.isNotEmpty()) rateTable(s(R.string.stats_pools), acq.pools.toSortedMap(), mapOf("new" to s(R.string.stats_pool_new), "familiar" to s(R.string.stats_pool_familiar)))
        if (acq.engines.isEmpty() && acq.pools.isEmpty()) fact(s(R.string.stats_acq_hint))

        val rings = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_HORIZONTAL }
        fun ring(fraction: Double?, caption: String) = RingView(requireContext(), (fraction ?: 0.0).toFloat(), fraction?.let { "${Math.round(it * 100)}%" } ?: "—", caption, accent, track, ink, muted)
        rings.addView(ring(sv.hitRate, s(R.string.stats_ring_kept)), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(24) })
        rings.addView(ring(acq.soulseekRate, s(R.string.stats_ring_found)))
        add(rings, 22)
    }

    private fun rateTable(title: String, rows: Map<String, PiterfyStatsModel.Rate>, names: Map<String, String>?) {
        add(text(title, 14f, true, muted).apply { setPadding(dp(2), 0, 0, 0) }, 18)
        val head = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(View(requireContext()), LinearLayout.LayoutParams(0, 1, 1.4f))
        listOf(s(R.string.stats_tried), s(R.string.stats_got), "%").forEach { head.addView(text(it, 11.5f, true, muted).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f)) }
        add(head, 6)
        rows.forEach { (key, rate) ->
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(5), 0, dp(5)) }
            row.addView(text(names?.get(key) ?: key, 13.5f, true), LinearLayout.LayoutParams(0, -2, 1.4f))
            listOf(F.num(rate.tried), F.num(rate.got), F.percent(rate.got.toDouble(), rate.tried.toDouble())).forEach {
                row.addView(text(it, 13.5f).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            add(row)
        }
    }

    companion object {
        const val TAG = "piterfy_stats"
    }
}
