package com.eddyizm.tempus.ui.dialog

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.DialogFragment
import com.eddyizm.tempus.R
import com.eddyizm.tempus.ui.components.BarView
import com.eddyizm.tempus.ui.components.ColumnsView
import com.eddyizm.tempus.ui.components.PiterfyWrappedCard
import com.eddyizm.tempus.ui.components.RingView
import com.eddyizm.tempus.ui.components.StackView
import com.eddyizm.tempus.util.PiterfyLibrary
import com.eddyizm.tempus.util.PiterfyMotion
import com.eddyizm.tempus.util.PiterfyStatsFormat as F
import com.eddyizm.tempus.util.PiterfyStatsModel
import com.eddyizm.tempus.util.PiterfyStatsModel.Tone
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Piterfy: „Statystyki dla nerdów” na pełnym ekranie, trzy zakładki: Biblioteka (anatomia biblioteki i rachunek jakości),
 * Serwer (przyrost, dysk, skuteczność pobierania) i Wrapped (podsumowanie miesiąca albo roku z kartą do udostępnienia).
 * Liczy je serwer (stats.py); ekran pokazuje ostatnią zapamiętaną odpowiedź od razu i odświeża ją w tle.
 */
class PiterfyStatsDialog : DialogFragment() {
    private var stats: PiterfyStatsModel.Stats? = null
    private var tab = 0
    private var periodKey: String? = null
    private var message: String? = null

    private lateinit var subtitle: TextView
    private lateinit var tabViews: List<TextView>
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private var ink = Color.WHITE
    private var muted = Color.LTGRAY
    private var accent = Color.GREEN
    private var surface = Color.BLACK
    private var track = 0x33888888
    private var cardBackground = 0x22888888

    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)
    private fun s(id: Int, vararg args: Any) = getString(id, *args)

    private fun toneColor(tone: Tone) = when (tone) {
        Tone.GOOD, Tone.ACCENT -> accent
        Tone.WARN -> 0xFFF2C94C.toInt()
        Tone.BAD -> 0xFFFF6B6B.toInt()
        Tone.MUTED -> ColorUtils.setAlphaComponent(ink, 90)
        Tone.FREE -> track
    }

    // ── cykl życia ──
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val anchor = View(ctx)
        ink = MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        muted = MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.LTGRAY)
        accent = MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorPrimary, Color.GREEN)
        surface = MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorSurface, Color.BLACK)
        track = ColorUtils.setAlphaComponent(ink, 40)
        cardBackground = ColorUtils.setAlphaComponent(ink, 22)

        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(text(s(R.string.stats_title), 22f, true).apply { setPadding(0, dp(6), 0, dp(2)) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(ctx).apply {
            text = "✕"; textSize = 20f; setTextColor(ink); gravity = Gravity.CENTER
            contentDescription = s(R.string.appearance_close)
            setOnClickListener { dismissAllowingStateLoss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)
        subtitle = text("", 12.5f, false, muted).apply { setPadding(0, 0, 0, dp(10)) }
        root.addView(subtitle)

        val tabRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val names = listOf(R.string.stats_tab_library, R.string.stats_tab_server, R.string.stats_tab_wrapped)
        tabViews = names.mapIndexed { i, id ->
            TextView(ctx).apply {
                text = s(id); textSize = 14f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                setPadding(dp(18), 0, dp(18), 0); minHeight = dp(44)
                setOnClickListener { tab = i; scroll.scrollTo(0, 0); render() }
                PiterfyMotion.pressable(this)
            }.also { tabRow.addView(it, LinearLayout.LayoutParams(-2, dp(44)).apply { rightMargin = dp(8) }) }
        }
        root.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(tabRow) })

        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(14), 0, dp(28)) }
        scroll = ScrollView(ctx).apply { addView(body); isVerticalScrollBarEnabled = false }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        load()
        return MaterialAlertDialogBuilder(requireActivity()).setView(root).create()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(surface))
        }
    }

    /** Przełącza zakładkę (0 biblioteka, 1 serwer, 2 Wrapped) i opcjonalnie okres Wrapped; używa tego test na urządzeniu. */
    fun showTab(index: Int, period: String? = null) {
        tab = index.coerceIn(0, 2)
        if (period != null) periodKey = period
        if (this::scroll.isInitialized) scroll.scrollTo(0, 0)
        render()
    }

    /** Przewija zawartość o [y] pikseli (test na urządzeniu robi zrzuty kolejnych ekranów). */
    fun scrollContentTo(y: Int) { if (this::scroll.isInitialized) scroll.scrollTo(0, y) }

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

    // ── klocki ──
    private fun text(value: CharSequence, size: Float, bold: Boolean = false, color: Int = ink) = TextView(requireContext()).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun add(view: View, top: Int = 0, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT): View {
        body.addView(view, LinearLayout.LayoutParams(-1, height).apply { topMargin = dp(top) })
        return view
    }

    private fun section(title: String, note: String? = null) {
        add(text(title, 19f, true).apply { setPadding(dp(2), 0, 0, dp(6)) }, 26)
        if (note != null) add(text(note, 13f, false, muted).apply { setPadding(dp(2), 0, 0, dp(10)) })
    }

    private fun tiles(items: List<Triple<String, String, String>>) {
        items.chunked(2).forEach { pair ->
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, (label, value, sub) ->
                val card = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL; background = rounded(cardBackground, 14); setPadding(dp(14), dp(12), dp(14), dp(12))
                }
                card.addView(text(label.uppercase(), 10.5f, true, muted).apply { letterSpacing = 0.08f })
                card.addView(text(value, 24f, true, accent).apply { setPadding(0, dp(4), 0, dp(2)) })
                if (sub.isNotEmpty()) card.addView(text(sub, 12f, false, muted))
                row.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i == 0) rightMargin = dp(4) else leftMargin = dp(4) })
            }
            if (pair.size == 1) row.addView(View(requireContext()), LinearLayout.LayoutParams(0, 0, 1f))
            add(row, 8)
            // kafelki w rzędzie mają tę samą wysokość (najwyższy wyznacza resztę); samotny kafelek zostaje na połowie szerokości
            row.post {
                val tallest = (0 until pair.size).maxOfOrNull { row.getChildAt(it).height } ?: return@post
                for (k in 0 until pair.size) row.getChildAt(k).layoutParams = (row.getChildAt(k).layoutParams as LinearLayout.LayoutParams).apply { height = tallest }
            }
        }
    }

    /** Wiersze wykresu słupkowego: podpis, pasek, wartość. */
    private fun barRows(rows: List<Triple<String, Double, String>>) {
        val max = rows.maxOfOrNull { it.second } ?: 0.0
        rows.forEachIndexed { i, (label, value, shown) ->
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(text(label, 13f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(dp(96), -2))
            row.addView(BarView(requireContext(), F.fraction(value, max), accent, track, i), LinearLayout.LayoutParams(0, dp(10), 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
            row.addView(text(shown, 12.5f, true).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(92), -2))
            add(row, 8)
        }
    }

    private fun legend(items: List<Triple<Int, String, String>>) {
        items.forEach { (color, label, value) ->
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(View(requireContext()).apply { background = rounded(color, 4) }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(10) })
            row.addView(text(label, 13f, false, muted), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text(value, 13f, true))
            add(row, 7)
        }
    }

    private fun fact(value: String) = add(text(value, 13.5f, false, muted).apply { setPadding(dp(2), 0, 0, 0) }, 10)

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
            add(text(message ?: "", 15f, false, muted).apply { setPadding(dp(2), dp(20), 0, dp(12)) })
            if (message != null && message != s(R.string.stats_loading)) {
                add(com.google.android.material.button.MaterialButton(requireContext()).apply {
                    text = s(R.string.piterfy_retry); isAllCaps = false; cornerRadius = dp(24)
                    setOnClickListener { load() }
                })
            }
            return
        }
        when (tab) { 0 -> library(data.library); 1 -> server(data); else -> wrapped(data) }
        for (i in 0 until body.childCount) PiterfyMotion.enter(body.getChildAt(i), i)
    }

    private fun stamp(iso: String?): String {
        if (iso.isNullOrBlank()) return "—"
        val patterns = listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mmXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm")
        for (pattern in patterns) {
            val format = SimpleDateFormat(pattern, Locale.ROOT)
            if (pattern.endsWith("'Z'")) format.timeZone = TimeZone.getTimeZone("UTC")
            val date: Date = runCatching { format.parse(iso.substringBefore('.').let { if (pattern.endsWith("'Z'") && !it.endsWith("Z")) it + "Z" else it }) }.getOrNull() ?: continue
            return DateFormat.getMediumDateFormat(requireContext()).format(date) + " " + DateFormat.getTimeFormat(requireContext()).format(date)
        }
        return iso
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

    // ── Wrapped ──
    private fun wrapped(data: PiterfyStatsModel.Stats) {
        if (data.periods.isEmpty()) {
            add(text(s(R.string.stats_wrapped_empty, data.historySince?.let { " (" + F.day(it) + ")" }.orEmpty()), 15f, false, muted).apply { setPadding(dp(2), dp(12), 0, 0) })
            return
        }
        val period = data.periods.firstOrNull { it.key == periodKey } ?: data.periods.first()
        val chips = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        data.periods.forEach { p ->
            val on = p.key == period.key
            chips.addView(text(p.label, 13.5f, true, if (on) surface else ink).apply {
                gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), 0); background = rounded(if (on) ink else cardBackground, 20)
                setOnClickListener { periodKey = p.key; render() }
                contentDescription = p.label
                PiterfyMotion.pressable(this)
            }, LinearLayout.LayoutParams(-2, dp(40)).apply { rightMargin = dp(8) })
        }
        add(HorizontalScrollView(requireContext()).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

        val image = ImageView(requireContext()).apply {
            adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
            val width = minOf(resources.displayMetrics.widthPixels - dp(32), 720)
            setImageBitmap(PiterfyWrappedCard.render(requireContext(), period, width))
            contentDescription = period.label
            clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            background = rounded(Color.TRANSPARENT, 20)
        }
        add(image, 14)
        add(com.google.android.material.button.MaterialButton(requireContext()).apply {
            text = s(R.string.stats_share); isAllCaps = false; cornerRadius = dp(24); minHeight = dp(48)
            setOnClickListener { share(period) }
        }, 12)

        val cmp = period.compare
        val listener = period.listener
        val box = { LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; background = rounded(cardBackground, 14); setPadding(dp(16), dp(14), dp(16), dp(14)) } }
        box().also { card ->
            card.addView(text(s(R.string.stats_listener).uppercase(), 10.5f, true, muted).apply { letterSpacing = 0.08f })
            if (listener != null) {
                card.addView(text(listener.name, 26f, true, accent).apply { setPadding(0, dp(4), 0, dp(2)) })
                card.addView(text(listener.blurb, 14f, false, muted))
                listener.why.forEach { card.addView(text("• $it", 13f).apply { setPadding(0, dp(4), 0, 0) }) }
            } else {
                card.addView(text(s(R.string.stats_listener_none), 14f, false, muted).apply { setPadding(0, dp(6), 0, 0) })
            }
            add(card, 16)
        }
        if (cmp != null) box().also { card ->
            card.addView(text(s(if (period.kind == "month") R.string.stats_compare_month else R.string.stats_compare_year).uppercase(), 10.5f, true, muted).apply { letterSpacing = 0.08f })
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
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
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
        const val TAG = "piterfy_stats"
    }
}
