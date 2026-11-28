package com.eddyizm.tempus.ui.dialog

import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
import com.eddyizm.tempus.ui.components.PiterfyLookPreview
import com.eddyizm.tempus.util.PiterfyDisplayPlan
import com.eddyizm.tempus.util.PiterfyMotionPlan
import com.eddyizm.tempus.util.Preferences
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

/**
 * Piterfy: strona „Wygląd” w jednym miejscu, z podglądem na żywo: motyw kolorystyczny, ikona aplikacji, Liquid Glass,
 * kolory z okładek, ruch, wibracje, prawdziwa czerń OLED, rozmiar tekstu i gęstość list oraz strona główna wg pory dnia.
 *
 * Makieta „Teraz gra” u góry reaguje od razu na każdą zmianę. Ustawienia, które zmieniają cały interfejs (szkło, tekst, gęstość,
 * ruch, czerń), przebudowują aplikację dopiero po zamknięciu okna, żeby podgląd nie znikał pod palcem; motyw i ikona działają od
 * razu jak dawniej. Ikona zmienia się, gdy aplikacja schodzi z ekranu.
 */
class PiterfyAppearanceDialog : DialogFragment() {
    private lateinit var root: LinearLayout
    private var preview: PiterfyLookPreview? = null
    private var dirty = false          // coś zmieniło się w ustawieniach obejmujących cały interfejs: przebuduj aplikację po zamknięciu

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
            .setTitle(R.string.ux_appearance_title)
            .setView(scroll)
            .setPositiveButton(R.string.appearance_close, null)
            .create()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (dirty) {
            dirty = false
            ThemeHelper.applyNightMode()
            activity?.recreate()
        }
    }

    private fun heading(s: String) = text(s, 14f, bold = true).apply { setPadding(0, dp(20), 0, dp(8)) }

    /** Wiersz „opis + przełącznik”; [onChange] dostaje nowy stan. */
    private fun switchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
        val labels = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        labels.addView(text(title, 15f, bold = true))
        labels.addView(text(summary, 12f, alpha = 0.7f))
        row.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(MaterialSwitch(ctx).apply {
            isChecked = checked
            setOnCheckedChangeListener { v, on -> v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onChange(on) }
        })
        return row
    }

    /** Grupa przycisków wyboru jednej z opcji (id → etykieta). */
    private fun choice(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit): View {
        val ctx = requireContext()
        val group = MaterialButtonToggleGroup(ctx).apply { isSingleSelection = true; isSelectionRequired = true }
        val ids = HashMap<Int, String>()
        options.forEach { (id, label) ->
            val button = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                this.id = View.generateViewId(); text = label; isAllCaps = false
            }
            ids[button.id] = id
            group.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (id == selected) group.check(button.id)
        }
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) ids[checkedId]?.let(onPick)
        }
        return group.apply { setPadding(0, dp(8), 0, 0) }
    }

    private fun touchedFullInterface() { dirty = true; updatePreview() }

    private fun updatePreview() {
        val glass = PiterfyAppearance.glassEnabled()
        val accent = PiterfyAppearance.effectiveTheme()?.accent?.toInt() ?: 0xFF1DB954.toInt()
        val systemAnimators = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || android.animation.ValueAnimator.areAnimatorsEnabled()
        val plan = PiterfyDisplayPlan.preview(
            glass = glass,
            intensity = PiterfyAppearance.glassIntensity(),
            coverColors = PiterfyAppearance.coverColorsEnabled(),
            trueBlack = Preferences.isDarkThemeBlack(),
            textScalePercent = PiterfyAppearance.textScalePercent(),
            density = PiterfyAppearance.density(),
            motionMode = PiterfyAppearance.motionMode(),
            systemAnimatorsOn = systemAnimators,
        )
        preview?.update(plan, accent, SAMPLE_COVER)
    }

    private fun build() {
        root.removeAllViews()
        val ctx = requireContext()

        // podgląd na żywo
        preview = PiterfyLookPreview(ctx)
        root.addView(text(getString(R.string.ux_section_preview), 14f, bold = true).apply { setPadding(0, 0, 0, dp(8)) })
        root.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // automat okolicznościowy
        root.addView(switchRow(getString(R.string.appearance_auto), getString(R.string.appearance_auto_summary), PiterfyAppearance.autoEnabled()) {
            PiterfyAppearance.setAutoEnabled(it); changed()
        }.apply { setPadding(0, dp(18), 0, 0) })
        PiterfyAppearance.summary(ctx).takeIf { it.isNotEmpty() }?.let {
            root.addView(text(it, 12.5f, alpha = 0.85f).apply { setPadding(0, dp(6), 0, 0) })
        }

        // Liquid Glass: niezależny od motywu; suwak siły tylko przy włączonym szkle
        root.addView(switchRow(getString(R.string.glass_title), getString(R.string.glass_summary), PiterfyAppearance.glassEnabled()) {
            PiterfyAppearance.setGlassEnabled(it); touchedFullInterface(); build()
        }.apply { setPadding(0, dp(18), 0, 0) })
        if (PiterfyAppearance.glassEnabled()) {
            root.addView(text(getString(R.string.glass_intensity), 12.5f, alpha = 0.85f).apply { setPadding(0, dp(10), 0, 0) })
            root.addView(Slider(ctx).apply {
                valueFrom = 0f; valueTo = 100f; stepSize = 5f
                value = PiterfyAppearance.glassIntensity().toFloat()
                addOnChangeListener { _, v, fromUser ->
                    if (fromUser) { PiterfyAppearance.setGlassIntensity(v.toInt()); touchedFullInterface() }
                }
            })
            if (Build.VERSION.SDK_INT < 31) root.addView(text(getString(R.string.glass_note_old_android), 12f, alpha = 0.7f))
        }

        // kolory z okładek
        root.addView(switchRow(getString(R.string.ux_cover_colors_title), getString(R.string.ux_cover_colors_summary), PiterfyAppearance.coverColorsEnabled()) {
            PiterfyAppearance.setCoverColorsEnabled(it); touchedFullInterface()
        })

        // odczucia: ruch i haptyka
        root.addView(heading(getString(R.string.ux_section_feel)))
        root.addView(text(getString(R.string.ux_motion_title), 15f, bold = true))
        root.addView(text(getString(R.string.ux_motion_summary), 12f, alpha = 0.7f))
        root.addView(choice(
            listOf(
                PiterfyMotionPlan.SYSTEM to getString(R.string.ux_motion_system),
                PiterfyMotionPlan.FULL to getString(R.string.ux_motion_full),
                PiterfyMotionPlan.REDUCED to getString(R.string.ux_motion_reduced),
            ),
            PiterfyAppearance.motionMode(),
        ) { PiterfyAppearance.setMotionMode(it); touchedFullInterface() })
        root.addView(switchRow(getString(R.string.ux_haptics_title), getString(R.string.ux_haptics_summary), PiterfyAppearance.hapticsEnabled()) {
            PiterfyAppearance.setHapticsEnabled(it)
        })

        // ekran: czerń, rozmiar tekstu, gęstość
        root.addView(heading(getString(R.string.ux_section_display)))
        root.addView(switchRow(getString(R.string.ux_oled_title), getString(R.string.ux_oled_summary), Preferences.isDarkThemeBlack()) {
            Preferences.setDarkThemeBlack(it); touchedFullInterface()
        })
        val sizeLabel = text(getString(R.string.ux_text_size, PiterfyAppearance.textScalePercent()), 12.5f, alpha = 0.85f).apply { setPadding(0, dp(14), 0, 0) }
        root.addView(sizeLabel)
        root.addView(Slider(ctx).apply {
            valueFrom = PiterfyDisplayPlan.TEXT_SCALE_MIN.toFloat(); valueTo = PiterfyDisplayPlan.TEXT_SCALE_MAX.toFloat()
            stepSize = PiterfyDisplayPlan.TEXT_SCALE_STEP.toFloat()
            value = PiterfyAppearance.textScalePercent().toFloat()
            addOnChangeListener { _, v, fromUser ->
                if (fromUser) {
                    PiterfyAppearance.setTextScalePercent(v.toInt())
                    sizeLabel.text = getString(R.string.ux_text_size, PiterfyAppearance.textScalePercent())
                    touchedFullInterface()
                }
            }
        })
        root.addView(text(getString(R.string.ux_density_title), 15f, bold = true).apply { setPadding(0, dp(10), 0, 0) })
        root.addView(choice(
            listOf(
                PiterfyDisplayPlan.COMFORTABLE to getString(R.string.ux_density_comfortable),
                PiterfyDisplayPlan.COMPACT to getString(R.string.ux_density_compact),
            ),
            PiterfyAppearance.density(),
        ) { PiterfyAppearance.setDensity(it); touchedFullInterface() })

        // strona główna wg pory dnia
        root.addView(heading(getString(R.string.ux_section_home)))
        root.addView(switchRow(getString(R.string.ux_home_smart_title), getString(R.string.ux_home_smart_summary), PiterfyAppearance.homeSmartEnabled()) {
            PiterfyAppearance.setHomeSmartEnabled(it); dirty = true
        })

        // motywy
        root.addView(heading(getString(R.string.appearance_themes)))
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
            root.addView(heading(getString(R.string.appearance_icons)))
            val current = PiterfyAppIcon.current(ctx)
            val iconRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            PiterfyCatalog.icons.forEach { icon ->
                iconRow.addView(iconTile(icon, icon.id == PiterfyAppearance.chosenIcon(), icon.id == current))
            }
            root.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(iconRow) })
            root.addView(text(getString(R.string.appearance_icon_note), 12f, alpha = 0.7f).apply { setPadding(0, dp(8), 0, 0) })
        }

        updatePreview()
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

    /** Motyw, ikona i automat okolicznościowy działają od razu: stosuje motyw i przebudowuje ekran (po tym nic nie czeka na zamknięcie). */
    private fun changed() {
        dirty = false
        ThemeHelper.applyNightMode()
        build()
        activity?.recreate()
    }

    private companion object {
        const val SAMPLE_COVER = 0xFF3D5A80.toInt()   // kolor „okładki” w makiecie
    }
}
