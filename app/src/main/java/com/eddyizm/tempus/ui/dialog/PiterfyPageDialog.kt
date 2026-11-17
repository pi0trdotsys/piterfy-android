package com.eddyizm.tempus.ui.dialog

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.DialogFragment
import com.eddyizm.tempus.R
import com.eddyizm.tempus.ui.components.BarView
import com.eddyizm.tempus.util.PiterfyMotion
import com.eddyizm.tempus.util.PiterfyStatsFormat as F
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Piterfy: wspólny szkielet pełnoekranowych stron w aplikacji (Statystyki dla nerdów, Wrapped, Mój gust): nagłówek
 * z tytułem i przyciskiem zamknięcia, podtytuł, pionowo przewijana treść i klocki do jej układania (kafelki, słupki,
 * legenda, sekcje), w kolorach aktualnego motywu. Klasy pochodne budują treść w [body] i wołają [showMessage]
 * w stanach ładowania i błędu.
 */
abstract class PiterfyPageDialog : DialogFragment() {
    protected lateinit var subtitle: TextView
    protected lateinit var body: LinearLayout
    protected lateinit var scroll: ScrollView
    protected var ink = Color.WHITE
    protected var muted = Color.LTGRAY
    protected var accent = Color.GREEN
    protected var surface = Color.BLACK
    protected var track = 0x33888888
    protected var cardBackground = 0x22888888

    /** Czy widoki są już zbudowane (dane mogą dojść przed [onCreateDialog] albo po zamknięciu). */
    protected val viewsReady: Boolean get() = this::scroll.isInitialized

    protected abstract fun pageTitle(): String

    /** Wiersz pod podtytułem (np. zakładki); domyślnie nic. */
    protected open fun addHeaderExtras(root: LinearLayout) {}

    /** Wołane raz, gdy widoki są gotowe: tu czytamy zapamiętane dane i pytamy serwer. */
    protected abstract fun startLoading()

    protected fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)
    protected fun s(id: Int, vararg args: Any): String = getString(id, *args)

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
        header.addView(text(pageTitle(), 22f, true).apply { setPadding(0, dp(6), 0, dp(2)) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextView(ctx).apply {
            text = "✕"; textSize = 20f; setTextColor(ink); gravity = Gravity.CENTER
            contentDescription = s(R.string.appearance_close)
            setOnClickListener { dismissAllowingStateLoss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)
        subtitle = text("", 12.5f, false, muted).apply { setPadding(0, 0, 0, dp(10)) }
        root.addView(subtitle)
        addHeaderExtras(root)

        body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(14), 0, dp(28)) }
        scroll = ScrollView(ctx).apply { addView(body); isVerticalScrollBarEnabled = false }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        startLoading()
        return MaterialAlertDialogBuilder(requireActivity()).setView(root).create()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(surface))
        }
    }

    /** Przewija zawartość o [y] pikseli (test na urządzeniu robi zrzuty kolejnych ekranów). */
    fun scrollContentTo(y: Int) { if (this::scroll.isInitialized) scroll.scrollTo(0, y) }

    // ── klocki ──
    protected fun text(value: CharSequence, size: Float, bold: Boolean = false, color: Int = ink) = TextView(requireContext()).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    protected fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    protected fun add(view: View, top: Int = 0, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT): View {
        body.addView(view, LinearLayout.LayoutParams(-1, height).apply { topMargin = dp(top) })
        return view
    }

    protected fun section(title: String, note: String? = null) {
        add(text(title, 19f, true).apply { setPadding(dp(2), 0, 0, dp(6)) }, 26)
        if (note != null) add(text(note, 13f, false, muted).apply { setPadding(dp(2), 0, 0, dp(10)) })
    }

    /** Karta z zaokrąglonym tłem na treść sekcji. */
    protected fun card(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL; background = rounded(cardBackground, 14); setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    protected fun caption(value: String) = text(value.uppercase(), 10.5f, true, muted).apply { letterSpacing = 0.08f }

    /** Kafelki po dwa w rzędzie (podpis, wartość, opis); kafelki w rzędzie mają tę samą wysokość. */
    protected fun tiles(items: List<Triple<String, String, String>>) {
        items.chunked(2).forEach { pair ->
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, (label, value, sub) ->
                val tile = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL; background = rounded(cardBackground, 14); setPadding(dp(14), dp(12), dp(14), dp(12))
                }
                tile.addView(caption(label))
                tile.addView(text(value, 24f, true, accent).apply { setPadding(0, dp(4), 0, dp(2)) })
                if (sub.isNotEmpty()) tile.addView(text(sub, 12f, false, muted))
                row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i == 0) rightMargin = dp(4) else leftMargin = dp(4) })
            }
            if (pair.size == 1) row.addView(View(requireContext()), LinearLayout.LayoutParams(0, 0, 1f))
            add(row, 8)
            row.post {
                val tallest = (0 until pair.size).maxOfOrNull { row.getChildAt(it).height } ?: return@post
                for (k in 0 until pair.size) row.getChildAt(k).layoutParams = (row.getChildAt(k).layoutParams as LinearLayout.LayoutParams).apply { height = tallest }
            }
        }
    }

    /** Wiersze wykresu słupkowego: podpis, pasek, wartość. */
    protected fun barRows(rows: List<Triple<String, Double, String>>, color: Int = accent) {
        val max = rows.maxOfOrNull { it.second } ?: 0.0
        rows.forEachIndexed { i, (label, value, shown) ->
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(text(label, 13f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(dp(96), -2))
            row.addView(BarView(requireContext(), F.fraction(value, max), color, track, i), LinearLayout.LayoutParams(0, dp(10), 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
            row.addView(text(shown, 12.5f, true).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(92), -2))
            add(row, 8)
        }
    }

    protected fun legend(items: List<Triple<Int, String, String>>) {
        items.forEach { (color, label, value) ->
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(View(requireContext()).apply { background = rounded(color, 4) }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(10) })
            row.addView(text(label, 13f, false, muted), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text(value, 13f, true))
            add(row, 7)
        }
    }

    protected fun fact(value: String) = add(text(value, 13.5f, false, muted).apply { setPadding(dp(2), 0, 0, 0) }, 10)

    /** Poziomy pasek chipów (okresy, filtry); [onPick] dostaje indeks. */
    protected fun chips(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        labels.forEachIndexed { i, label ->
            val on = i == selected
            row.addView(text(label, 13.5f, true, if (on) surface else ink).apply {
                gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), 0); background = rounded(if (on) ink else cardBackground, 20)
                setOnClickListener { onPick(i) }
                contentDescription = label
                PiterfyMotion.pressable(this)
            }, LinearLayout.LayoutParams(-2, dp(40)).apply { rightMargin = dp(8) })
        }
        add(HorizontalScrollView(requireContext()).apply { isHorizontalScrollBarEnabled = false; addView(row) })
    }

    /** Stan bez danych: komunikat (ładowanie albo błąd) i, gdy jest [retry], przycisk „Spróbuj ponownie”. */
    protected fun showMessage(message: String, retry: (() -> Unit)? = null) {
        body.removeAllViews()
        add(text(message, 15f, false, muted).apply { setPadding(dp(2), dp(20), 0, dp(12)) })
        if (retry != null) {
            add(MaterialButton(requireContext()).apply {
                text = s(R.string.piterfy_retry); isAllCaps = false; cornerRadius = dp(24)
                setOnClickListener { retry() }
            })
        }
    }

    /** Wjazd kolejnych klocków po zbudowaniu treści. */
    protected fun animateBody() { for (i in 0 until body.childCount) PiterfyMotion.enter(body.getChildAt(i), i) }

    /** „16 lis 2026, 15:05” z zapisu ISO serwera (kilka wariantów), a przy nieczytelnym zapisie sam napis. */
    protected fun stamp(iso: String?): String {
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
}
