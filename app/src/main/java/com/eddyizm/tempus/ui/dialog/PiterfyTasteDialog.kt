package com.eddyizm.tempus.ui.dialog

import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide
import com.eddyizm.tempus.R
import com.eddyizm.tempus.ui.components.ClockHeatView
import com.eddyizm.tempus.ui.components.ColumnsView
import com.eddyizm.tempus.ui.components.DailyBarsView
import com.eddyizm.tempus.ui.components.DonutView
import com.eddyizm.tempus.ui.components.GaugeView
import com.eddyizm.tempus.ui.components.MoodMapView
import com.eddyizm.tempus.util.PiterfyLibrary
import com.eddyizm.tempus.util.PiterfyStatsFormat as F
import com.eddyizm.tempus.util.PiterfyTasteModel
import com.eddyizm.tempus.util.PiterfyTasteModel.Quadrant
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Piterfy: „Mój gust” jako ekran w aplikacji (wcześniej przycisk otwierał stronę w przeglądarce). Pokazuje to samo co
 * strona connect/stack/gust: podsumowanie okresu z wykonawcą miesiąca, rytm dni, wykonawców i utwory na zapętleniu,
 * mapę nastroju, gatunki, dekady, zegar słuchania (gdy są scrobble Last.fm), odkrycia discovera i porównanie ze
 * Spotify (gdy jest eksport). Układ pionowy pod telefon. Dane to publiczne pliki serwera (nocne wyliczenie taste.py);
 * ekran od razu pokazuje ostatnio zapamiętane i odświeża je w tle.
 */
class PiterfyTasteDialog : PiterfyPageDialog() {
    private var taste: PiterfyTasteModel.Taste? = null
    private var baseline: PiterfyTasteModel.Baseline? = null
    private var message: String? = null

    override fun pageTitle() = s(R.string.taste_title)

    override fun startLoading() = load()

    private val moodColors = mapOf(
        Quadrant.EUFORIA to 0xFFE0B44A.toInt(), Quadrant.NAPIECIE to 0xFFE06B5A.toInt(),
        Quadrant.SPOKOJ to 0xFF4FC193.toInt(), Quadrant.MELANCHOLIA to 0xFF7C8CEA.toInt(),
    )

    // ── dane ──
    private fun load() {
        taste = PiterfyTasteModel.parse(PiterfyLibrary.cachedTaste())
        baseline = PiterfyTasteModel.parseBaseline(PiterfyLibrary.cachedTasteBaseline())
        message = if (taste == null) s(R.string.taste_loading) else null
        render()
        PiterfyLibrary.taste({ json, base ->
            if (!isAdded) return@taste
            PiterfyTasteModel.parse(json)?.let { taste = it; baseline = PiterfyTasteModel.parseBaseline(base); message = null; render() }
        }, { reason ->
            if (!isAdded || taste != null) return@taste
            message = s(when (reason) { "not_ready" -> R.string.taste_error_not_ready; "no_server" -> R.string.taste_error_no_server; else -> R.string.taste_error_generic })
            render()
        })
    }

    private fun render() {
        if (!isAdded) return
        val data = taste
        subtitle.text = if (data?.generated != null) s(R.string.taste_updated, stamp(data.generated)) else ""
        body.removeAllViews()
        if (data == null) {
            showMessage(message ?: "", if (message != null && message != s(R.string.taste_loading)) ({ load() }) else null)
            return
        }
        hero(data)
        daily(data)
        artists(data)
        tracks(data)
        mood(data)
        genres(data)
        decades(data)
        data.clock?.let { clock(it) }
        discover(data.discover)
        baseline?.let { vs(data, it) }
        add(text(s(R.string.taste_footer), 11.5f, false, muted).apply { setPadding(dp(2), 0, dp(2), 0) }, 28)
        animateBody()
    }

    private fun empty(value: String) = add(text(value, 14f, false, muted).apply { setPadding(dp(2), dp(4), 0, dp(4)) })

    // ── hero ──
    private fun hero(t: PiterfyTasteModel.Taste) {
        add(text(s(R.string.taste_kicker), 11f, true, accent).apply { letterSpacing = 0.14f; setPadding(dp(2), 0, 0, 0) })
        add(text(s(R.string.taste_hero_title, t.periodDays), 30f, true).apply { setPadding(dp(2), dp(2), 0, 0) })
        val lead = if (t.estimated) s(R.string.taste_estimated, t.historySince?.let { F.day(it) } ?: s(R.string.taste_today)) else s(R.string.taste_hero_lead)
        add(text(lead, 13.5f, false, muted).apply { setPadding(dp(2), dp(4), 0, 0) })
        tiles(listOf(
            Triple(s(R.string.taste_kpi_plays), F.num(t.totals.plays), ""),
            Triple(s(R.string.taste_kpi_hours), F.num(t.totals.minutes / 60.0), ""),
            Triple(s(R.string.taste_kpi_artists), F.num(t.totals.artists), ""),
            Triple(s(R.string.taste_kpi_tracks), F.num(t.totals.tracks), ""),
        ))
        t.artists.firstOrNull()?.let { top ->
            val star = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = rounded(cardBackground, 16); setPadding(dp(14), dp(14), dp(14), dp(14))
            }
            star.addView(avatar(top, 84), LinearLayout.LayoutParams(dp(84), dp(84)).apply { rightMargin = dp(14) })
            val info = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            info.addView(text(s(R.string.taste_star), 10.5f, true, accent).apply { letterSpacing = 0.1f })
            info.addView(text(top.name, 22f, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, dp(2)) })
            info.addView(text(s(R.string.taste_star_plays, F.num(top.plays), (top.plays * 100.0 / maxOf(1L, t.totals.plays)).roundToInt()), 12.5f, false, muted))
            star.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            add(star, 12)
        }
    }

    /** Okrągłe zdjęcie wykonawcy (Deezer) z inicjałem, gdy zdjęcia nie ma albo się nie wczytało. */
    private fun avatar(a: PiterfyTasteModel.Artist, size: Int): View {
        val ctx = requireContext()
        val frame = FrameLayout(ctx).apply { background = rounded(track, size / 2) }
        val initial = a.name.trim().take(1).uppercase().ifEmpty { "?" }
        frame.addView(text(initial, size * 0.38f, true, muted).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
        if (a.img != null) {
            val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = a.name }
            frame.addView(image, FrameLayout.LayoutParams(-1, -1))
            Glide.with(ctx.applicationContext).load(a.img).circleCrop().into(image)
        }
        return frame
    }

    // ── rytm dni ──
    private fun daily(t: PiterfyTasteModel.Taste) {
        section(s(R.string.taste_daily), s(R.string.taste_daily_note))
        if (t.daily.size < 2) {
            empty(s(R.string.taste_daily_empty, t.historySince?.let { F.day(it) } ?: s(R.string.taste_today)))
            return
        }
        val box = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; background = rounded(cardBackground, 14); setPadding(dp(12), dp(14), dp(12), dp(6)) }
        box.addView(DailyBarsView(requireContext(), t.daily.map { it.day to it.n }, t.daily.map { F.shortDay(it.day) }, accent, track, muted),
            LinearLayout.LayoutParams(-1, -2))
        add(box)
    }

    // ── wykonawcy i utwory ──
    private fun artists(t: PiterfyTasteModel.Taste) {
        section(s(R.string.taste_artists), s(R.string.taste_artists_note, t.periodDays))
        if (t.artists.isEmpty()) { empty(s(R.string.taste_none)); return }
        t.artists.take(6).chunked(3).forEachIndexed { rowIndex, chunk ->
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until 3) {
                val artist = chunk.getOrNull(col)
                val cell = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
                if (artist != null) {
                    val photo = FrameLayout(requireContext())
                    photo.addView(avatar(artist, 72), FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER_HORIZONTAL))
                    photo.addView(text("#${rowIndex * 3 + col + 1}", 11f, true, Color.BLACK).apply {
                        gravity = Gravity.CENTER; setPadding(dp(7), dp(1), dp(7), dp(1)); background = rounded(accent, 10)
                    }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { leftMargin = dp(2) })
                    cell.addView(photo, LinearLayout.LayoutParams(-1, dp(72)))
                    cell.addView(text(artist.name, 13f, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) },
                        LinearLayout.LayoutParams(-1, -2))
                    cell.addView(text(s(R.string.taste_plays, F.num(artist.plays)), 11.5f, false, muted).apply { gravity = Gravity.CENTER })
                }
                row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { if (col < 2) rightMargin = dp(6) })
            }
            add(row, if (rowIndex == 0) 4 else 16)
        }
        t.artists.drop(6).forEachIndexed { i, a ->
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(2), 0, dp(2), 0) }
            row.addView(text("${i + 7}. ${a.name}", 13.5f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text(F.num(a.plays), 13f, true, muted))
            add(row, if (i == 0) 16 else 8)
        }
    }

    private fun tracks(t: PiterfyTasteModel.Taste) {
        section(s(R.string.taste_tracks), s(R.string.taste_tracks_note))
        if (t.tracks.isEmpty()) { empty(s(R.string.taste_none)); return }
        t.tracks.forEachIndexed { i, song ->
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(text("${i + 1}", 16f, true, accent), LinearLayout.LayoutParams(dp(28), -2))
            val names = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            names.addView(text(song.title, 14f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            names.addView(text(listOfNotNull(song.artist.takeIf { it.isNotEmpty() }, song.album).joinToString(" · "), 12f, false, muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            row.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text("${F.num(song.plays)}×", 13f, true, muted))
            add(row, 12)
        }
    }

    // ── nastrój ──
    private fun mood(t: PiterfyTasteModel.Taste) {
        section(s(R.string.taste_mood), s(R.string.taste_mood_note))
        val avg = t.mood.averages
        if (avg == null || t.mood.dots.isEmpty()) { empty(s(R.string.taste_mood_empty)); return }
        val gauges = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        val items = listOf(
            Triple(s(R.string.taste_g_energy), avg.energy.toFloat(), "${(avg.energy * 100).roundToInt()}%"),
            Triple(s(R.string.taste_g_dance), avg.dance.toFloat(), "${(avg.dance * 100).roundToInt()}%"),
            Triple(s(R.string.taste_g_valence), avg.valence.toFloat(), "${(avg.valence * 100).roundToInt()}%"),
            Triple(s(R.string.taste_g_tempo), PiterfyTasteModel.tempoFraction(avg.tempo), "${avg.tempo.roundToInt()} BPM"),
        )
        items.forEachIndexed { i, (label, fraction, shown) ->
            gauges.addView(GaugeView(requireContext(), fraction, shown, label, accent, track, ink, muted),
                LinearLayout.LayoutParams(0, -2, 1f).apply { if (i < 3) rightMargin = dp(4) })
        }
        add(gauges, 4)
        val names = Quadrant.values().associateWith { s(when (it) {
            Quadrant.EUFORIA -> R.string.taste_q_euforia; Quadrant.NAPIECIE -> R.string.taste_q_napiecie
            Quadrant.SPOKOJ -> R.string.taste_q_spokoj; Quadrant.MELANCHOLIA -> R.string.taste_q_melancholia
        }) }
        add(MoodMapView(requireContext(), t.mood.dots, moodColors, names, s(R.string.taste_axis_x), s(R.string.taste_axis_y), track, muted), 10)
        val shares = PiterfyTasteModel.moodShares(t.mood.dots)
        legend(Quadrant.values().map { q -> Triple(moodColors.getValue(q), names.getValue(q), "${(shares.getValue(q) * 100).roundToInt()}%") })
        if (t.mood.coverage < 0.95) fact(s(R.string.taste_mood_coverage, (t.mood.coverage * 100).roundToInt()))
    }

    // ── gatunki, dekady, zegar ──
    private fun genres(t: PiterfyTasteModel.Taste) {
        section(s(R.string.taste_genres), s(R.string.taste_genres_note))
        if (t.genres.isEmpty()) { empty(s(R.string.taste_genres_empty)); return }
        barRows(t.genres.map { Triple(it.label, it.n.toDouble(), F.num(it.n)) })
    }

    private fun decades(t: PiterfyTasteModel.Taste) {
        section(s(R.string.taste_decades), s(R.string.taste_decades_note))
        if (t.decades.isEmpty()) { empty(s(R.string.taste_decades_empty)); return }
        val max = t.decades.maxOf { it.n }
        add(HorizontalScrollView(requireContext()).apply {
            isHorizontalScrollBarEnabled = false
            addView(ColumnsView(requireContext(), t.decades.map { Triple("'" + it.label.takeLast(2), F.num(it.n), PiterfyTasteModel.barFraction(it.n, max)) }, accent, track, ink, muted))
        })
    }

    private fun clock(clock: PiterfyTasteModel.Clock) {
        section(s(R.string.taste_clock), s(R.string.taste_clock_note))
        val box = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; background = rounded(cardBackground, 14); setPadding(dp(10), dp(10), dp(10), dp(10)) }
        box.addView(ClockHeatView(requireContext(), clock.days, clock.grid, accent, track, muted), LinearLayout.LayoutParams(-1, -2))
        add(box)
    }

    // ── odkrycia ──
    private fun discover(d: PiterfyTasteModel.Discover?) {
        section(s(R.string.taste_discover), s(R.string.taste_discover_note))
        if (d == null || d.downloaded <= 0) { empty(s(R.string.taste_discover_empty)); return }
        val (liked, trashed, other) = PiterfyTasteModel.discoverParts(d)
        val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(DonutView(requireContext(), listOf(liked to moodColors.getValue(Quadrant.SPOKOJ), trashed to moodColors.getValue(Quadrant.NAPIECIE), other to track),
            d.hitRate?.let { "${(it * 100).roundToInt()}%" } ?: "—", s(R.string.taste_hits), track, ink, muted), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(18) })
        val legendBox = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        fun chip(color: Int?, n: Int, label: String) {
            val line = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            if (color != null) line.addView(View(requireContext()).apply { background = rounded(color, 4) }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(10) })
            line.addView(text(F.num(n), 20f, true, ink).apply { setPadding(0, 0, dp(8), 0) })
            line.addView(text(label, 13f, false, muted))
            legendBox.addView(line)
        }
        chip(null, d.downloaded, s(R.string.taste_downloaded))
        chip(moodColors.getValue(Quadrant.SPOKOJ), d.liked, s(R.string.taste_liked))
        chip(moodColors.getValue(Quadrant.NAPIECIE), d.trashed, s(R.string.taste_trashed))
        row.addView(legendBox, LinearLayout.LayoutParams(0, -2, 1f))
        add(row, 6)
        if (d.recentLiked.isEmpty()) {
            fact(s(R.string.taste_liked_empty))
        } else {
            d.recentLiked.forEach { item ->
                val line = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                line.addView(text("♥", 16f, true, moodColors.getValue(Quadrant.SPOKOJ)), LinearLayout.LayoutParams(dp(28), -2))
                val names = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
                names.addView(text(item.title, 14f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                val sub = item.artist + if (item.via.isNotBlank()) " · " + s(R.string.taste_liked_via, item.via) else ""
                names.addView(text(sub, 12f, false, muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                line.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
                add(line, 12)
            }
        }
    }

    // ── porównanie ze Spotify ──
    private fun vs(t: PiterfyTasteModel.Taste, base: PiterfyTasteModel.Baseline) {
        section(s(R.string.taste_vs), s(R.string.taste_vs_note, F.num(base.totalTracks)))
        val nowArtists = t.artists.take(8).map { it.name }
        val nowGenres = t.genres.take(8).map { it.label }
        listOf(
            Triple(s(R.string.taste_vs_artists_then) to s(R.string.taste_vs_artists_now), base.artists.take(8), nowArtists),
            Triple(s(R.string.taste_vs_genres_then) to s(R.string.taste_vs_genres_now), base.genres.take(8), nowGenres),
        ).forEachIndexed { i, (titles, then, now) ->
            val fresh = PiterfyTasteModel.newOnes(now, then)
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            fun column(title: String, items: List<String>, highlight: Set<String>) = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL; background = rounded(cardBackground, 14); setPadding(dp(12), dp(10), dp(12), dp(10))
                addView(text(title.uppercase(), 10f, true, muted).apply { letterSpacing = 0.06f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                items.forEachIndexed { k, name ->
                    val isNew = name in highlight
                    addView(text("${k + 1}. $name", 13f, isNew, if (isNew) accent else ink).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(5), 0, 0) })
                }
            }
            row.addView(column(titles.first, then, emptySet()), LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = dp(4) })
            row.addView(column(titles.second, now, fresh), LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(4) })
            add(row, if (i == 0) 6 else 12)
        }
        val names = mapOf("energy" to s(R.string.taste_g_energy), "dance" to s(R.string.taste_g_dance), "valence" to s(R.string.taste_g_valence), "tempo" to s(R.string.taste_g_tempo))
        val deltas = PiterfyTasteModel.deltas(t.mood.averages, base.averages)
        if (deltas.isNotEmpty()) {
            val flow = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
            deltas.chunked(2).forEach { pair ->
                val line = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEachIndexed { i, delta ->
                    val arrow = when (delta.direction) { 1 -> "▲ "; -1 -> "▼ "; else -> "• " }
                    val color = when (delta.direction) { 1 -> 0xFF5FF0A3.toInt(); -1 -> 0xFFFF9A9A.toInt(); else -> ink }
                    val chip = TextView(requireContext()).apply {
                        text = "${names.getValue(delta.key)}: $arrow${abs(delta.diff).roundToInt()} ${delta.unit}"
                        textSize = 13f; setTextColor(color); setTypeface(typeface, Typeface.BOLD)
                        background = rounded(cardBackground, 20); setPadding(dp(12), dp(8), dp(12), dp(8)); gravity = Gravity.CENTER
                    }
                    line.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i == 0) rightMargin = dp(4) else leftMargin = dp(4) })
                }
                flow.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
            add(flow, 4)
        }
    }

    companion object {
        const val TAG = "piterfy_taste"
    }
}
