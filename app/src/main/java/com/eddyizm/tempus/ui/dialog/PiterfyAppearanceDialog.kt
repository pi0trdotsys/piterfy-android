package com.eddyizm.tempus.ui.dialog

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.eddyizm.tempus.R
import com.eddyizm.tempus.appearance.PiterfyAppIcon
import com.eddyizm.tempus.appearance.PiterfyAppearance
import com.eddyizm.tempus.appearance.PiterfyCatalog
import com.eddyizm.tempus.helper.ThemeHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

/**
 * Piterfy: wybór motywu kolorystycznego i ikony aplikacji oraz przełącznik automatycznych zmian w dni okolicznościowe
 * (od 2 dni przed do 2 dni po święcie). Motyw działa od razu, ikona zmienia się, gdy aplikacja schodzi z ekranu.
 */
class PiterfyAppearanceDialog : DialogFragment() {
    private lateinit var root: LinearLayout

    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)
    private fun text(s: CharSequence, sizeSp: Float, bold: Boolean = false, alpha: Float = 1f) = TextView(requireContext()).apply {
        text = s; textSize = sizeSp; this.alpha = alpha
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        root = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val scroll = android.widget.ScrollView(requireContext()).apply { addView(root) }
        build()
        return MaterialAlertDialogBuilder(requireActivity())
            .setTitle(R.string.appearance_title)
            .setView(scroll)
            .setPositiveButton(R.string.appearance_close, null)
            .create()
    }

    private fun build() {
        root.removeAllViews()
        val ctx = requireContext()

        // automat okolicznościowy
        val autoRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val labels = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(getString(R.string.appearance_auto), 15f, bold = true))
        labels.addView(text(getString(R.string.appearance_auto_summary), 12f, alpha = 0.7f))
        autoRow.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val auto = MaterialSwitch(ctx).apply {
            isChecked = PiterfyAppearance.autoEnabled()
            setOnCheckedChangeListener { _, on -> PiterfyAppearance.setAutoEnabled(on); changed() }
        }
        autoRow.addView(auto)
        root.addView(autoRow)
        PiterfyAppearance.summary(ctx).takeIf { it.isNotEmpty() }?.let {
            root.addView(text(it, 12.5f, alpha = 0.85f).apply { setPadding(0, dp(6), 0, 0) })
        }

        // Liquid Glass: niezależny od motywu; suwak siły tylko przy włączonym szkle
        root.addView(glassRow().apply { setPadding(0, dp(18), 0, 0) })
        if (PiterfyAppearance.glassEnabled()) {
            root.addView(text(getString(R.string.glass_intensity), 12.5f, alpha = 0.85f).apply { setPadding(0, dp(10), 0, 0) })
            root.addView(Slider(ctx).apply {
                valueFrom = 0f; valueTo = 100f; stepSize = 5f
                value = PiterfyAppearance.glassIntensity().toFloat()
                // zapis i przebudowa ekranu dopiero po puszczeniu suwaka, a nie przy każdym kroku
                addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) {}
                    override fun onStopTrackingTouch(slider: Slider) { PiterfyAppearance.setGlassIntensity(slider.value.toInt()); changed() }
                })
            })
            if (Build.VERSION.SDK_INT < 31) root.addView(text(getString(R.string.glass_note_old_android), 12f, alpha = 0.7f))
        }

        // motywy
        root.addView(text(getString(R.string.appearance_themes), 14f, bold = true).apply { setPadding(0, dp(18), 0, dp(8)) })
        val chosen = PiterfyAppearance.chosenTheme()
        val themeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        themeRow.addView(themeCard(getString(R.string.appearance_theme_classic), 0xFF000000, 0xFF1DB954, 0xFFA4EDC2, 0xFFFFFFFF, chosen == PiterfyAppearance.CLASSIC) {
            pickTheme(PiterfyAppearance.CLASSIC)
        })
        PiterfyCatalog.themes.forEach { t ->
            themeRow.addView(themeCard(getString(t.nameRes), t.background, t.accent, t.accent2, t.text, chosen == t.id) { pickTheme(t.id) })
        }
        root.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(themeRow) })

        // ikony (adaptacyjne ikony istnieją od API 26)
        if (Build.VERSION.SDK_INT >= 26) {
            root.addView(text(getString(R.string.appearance_icons), 14f, bold = true).apply { setPadding(0, dp(18), 0, dp(8)) })
            val current = PiterfyAppIcon.current(ctx)
            val iconRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            PiterfyCatalog.icons.forEach { icon ->
                iconRow.addView(iconTile(icon, icon.id == PiterfyAppearance.chosenIcon(), icon.id == current))
            }
            root.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(iconRow) })
            root.addView(text(getString(R.string.appearance_icon_note), 12f, alpha = 0.7f).apply { setPadding(0, dp(8), 0, 0) })
        }
    }

    private fun glassRow(): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val labels = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(getString(R.string.glass_title), 15f, bold = true))
        labels.addView(text(getString(R.string.glass_summary), 12f, alpha = 0.7f))
        row.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(MaterialSwitch(ctx).apply {
            isChecked = PiterfyAppearance.glassEnabled()
            setOnCheckedChangeListener { _, on -> PiterfyAppearance.setGlassEnabled(on); changed() }
        })
        return row
    }

    private fun themeCard(name: String, bg: Long, accent: Long, accent2: Long, textColor: Long, selected: Boolean, onClick: () -> Unit): View {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(8))
            background = rounded(bg.toInt(), 14, if (selected) accent.toInt() else 0x33888888, if (selected) 3 else 1)
            setOnClickListener { onClick() }
            contentDescription = name
        }
        val chips = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        chips.addView(View(context).apply { background = rounded(accent.toInt(), 6) }, LinearLayout.LayoutParams(dp(34), dp(12)).apply { rightMargin = dp(6) })
        chips.addView(View(context).apply { background = rounded(accent2.toInt(), 6) }, LinearLayout.LayoutParams(dp(20), dp(12)))
        card.addView(chips)
        card.addView(text(name, 11.5f, bold = selected).apply { setTextColor(textColor.toInt()); setPadding(0, dp(14), 0, 0); maxLines = 1 })
        return card.also { it.layoutParams = LinearLayout.LayoutParams(dp(112), dp(78)).apply { rightMargin = dp(10) } }
    }

    private fun iconTile(icon: PiterfyCatalog.Icon, selected: Boolean, active: Boolean): View {
        val ctx = requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(Color.TRANSPARENT, 14, if (selected) 0xFF1DB954.toInt() else 0, if (selected) 3 else 0)
            setOnClickListener { pickIcon(icon.id) }
            contentDescription = getString(icon.nameRes)
        }
        val image = ImageView(ctx).apply {
            setImageDrawable(ContextCompat.getDrawable(ctx, icon.iconRes))
            clipToOutline = true
            background = rounded(Color.TRANSPARENT, 16)
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        box.addView(image, LinearLayout.LayoutParams(dp(60), dp(60)))
        box.addView(text(getString(icon.nameRes), 11f, bold = active).apply { gravity = Gravity.CENTER; maxLines = 1 }, LinearLayout.LayoutParams(dp(68), LinearLayout.LayoutParams.WRAP_CONTENT))
        return box.also { it.layoutParams = LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(6) } }
    }

    private fun pickTheme(id: String) {
        val occasion = PiterfyAppearance.activeOccasion() != null
        PiterfyAppearance.setChosenTheme(id)
        if (occasion) Toast.makeText(requireContext(), R.string.appearance_overridden, Toast.LENGTH_LONG).show()
        changed()
    }

    private fun pickIcon(id: String) {
        val occasion = PiterfyAppearance.activeOccasion() != null
        PiterfyAppearance.setChosenIcon(id)
        if (occasion) Toast.makeText(requireContext(), R.string.appearance_overridden, Toast.LENGTH_LONG).show()
        changed()
    }

    /** Odświeża okno i stosuje motyw (zmiana sygnatury motywu przebudowuje ekran przy powrocie do aplikacji). */
    private fun changed() {
        ThemeHelper.applyNightMode()
        build()
        activity?.recreate()
    }
}
