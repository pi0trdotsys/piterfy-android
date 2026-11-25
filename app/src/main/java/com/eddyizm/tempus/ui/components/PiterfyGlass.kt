package com.eddyizm.tempus.ui.components

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.PowerManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.eddyizm.tempus.appearance.PiterfyAppearance
import com.eddyizm.tempus.util.PiterfyGlassPlan
import com.eddyizm.tempus.util.PiterfyMotion
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.MaterialColors

/**
 * Piterfy: „Liquid Glass” — wspólne narzędzia: aktualny plan (siła, tryb), tła ze szkła, instalacja na dolnej nawigacji
 * i mini odtwarzaczu, rozluźnienie nieprzezroczystych teł ekranów i sprężyste naciśnięcie. Wszystko wyłącza się,
 * gdy opcja jest wyłączona (plan OFF), więc zwykły wygląd zostaje nietknięty.
 */
object PiterfyGlass {
    /** Aktualny plan: ustawienie + wersja systemu + oszczędzanie energii + systemowe animacje. */
    @JvmStatic
    fun plan(context: Context): PiterfyGlassPlan.Plan {
        val powerSave = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
        return PiterfyGlassPlan.plan(Build.VERSION.SDK_INT, PiterfyAppearance.glassEnabled(), PiterfyAppearance.glassIntensity(),
            powerSave, PiterfyMotion.enabled())
    }

    /** Czy naciśnięcia mają być sprężynami (szkło włączone i systemowe animacje działają). */
    @JvmStatic
    fun springEnabled(): Boolean = PiterfyAppearance.glassEnabled() && PiterfyMotion.enabled()

    /** Kolor powierzchni z bieżącego motywu (podstawa szkła). */
    @JvmStatic
    fun surfaceColor(context: Context): Int =
        MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.BLACK)

    private fun density(context: Context) = context.resources.displayMetrics.density

    /** Tło ze szkła o danym promieniu rogów albo null, gdy szkło jest wyłączone. */
    @JvmStatic @JvmOverloads
    fun drawable(context: Context, radiusDp: Float, topCornersOnly: Boolean = false, color: Int = surfaceColor(context)): PiterfyGlassDrawable? {
        val plan = plan(context)
        if (!plan.enabled) return null
        return PiterfyGlassDrawable(color, radiusDp * density(context), plan, density(context), topCornersOnly)
    }

    /** Szkło z falą dotyku (przyciski, kafelki) albo null, gdy szkło jest wyłączone. */
    @JvmStatic @JvmOverloads
    fun rippled(context: Context, radiusDp: Float, color: Int = surfaceColor(context)): Drawable? {
        val glass = drawable(context, radiusDp, false, color) ?: return null
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = radiusDp * density(context) }
        return RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), glass, mask)
    }

    /** Stosuje szkło jako tło widoku, o ile jest włączone; zwraca czy zastosowano. */
    @JvmStatic @JvmOverloads
    fun apply(view: View, radiusDp: Float, color: Int = surfaceColor(view.context)): Boolean {
        val d = rippled(view.context, radiusDp, color) ?: return false
        view.background = d
        return true
    }

    // ── rozmycie tła zawartości (Android 12+) ──

    /** Rysuje pod szkłem hosta rozmytą zawartość [source] i odświeża ją przy przewijaniu i zmianie układu. */
    @JvmStatic
    fun bindBlur(host: View, source: View, drawable: PiterfyGlassDrawable) {
        drawable.blurHost = host
        drawable.blurSource = source
        val listener = ViewTreeObserver.OnScrollChangedListener { host.invalidate() }
        val layout = ViewTreeObserver.OnGlobalLayoutListener { host.invalidate() }
        fun attach() {
            host.viewTreeObserver.addOnScrollChangedListener(listener)
            host.viewTreeObserver.addOnGlobalLayoutListener(layout)
        }
        fun detach() {
            val vto = host.viewTreeObserver
            if (vto.isAlive) { vto.removeOnScrollChangedListener(listener); vto.removeOnGlobalLayoutListener(layout) }
        }
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = attach()
            override fun onViewDetachedFromWindow(v: View) = detach()
        })
        if (host.isAttachedToWindow) attach()
    }

    // ── instalacja na ekranie głównym ──

    /** Wywołanie z MainActivity po zbudowaniu widoku: tło z poświatami, szkło dolnej nawigacji (z pigułką zaznaczenia). */
    @JvmStatic
    fun install(backdrop: PiterfyGlassBackdropView?, nav: View, content: View) {
        val plan = plan(nav.context)
        if (!plan.enabled) { backdrop?.visibility = View.GONE; return }
        backdrop?.visibility = View.VISIBLE
        backdrop?.apply(plan)
        val glass = drawable(nav.context, 22f, topCornersOnly = true) ?: return
        glass.pillProvider = { navPill(nav) }
        nav.background = glass
        if (plan.blur) bindBlur(nav, content, glass)
    }

    private fun navPill(nav: View): RectF? {
        val bn = nav as? BottomNavigationView ?: return null
        val menu = bn.getChildAt(0) as? ViewGroup ?: return null
        val item = menu.findViewById<View>(bn.selectedItemId) ?: return null
        if (item.width == 0) return null
        val d = density(nav.context)
        return RectF(menu.left + item.left + 10 * d, menu.top + item.top + 6 * d, menu.left + item.right - 10 * d, menu.top + item.bottom - 6 * d)
    }

    /**
     * Mini odtwarzacz: przy zwiniętym panelu tło panelu jest przezroczyste, a nagłówek ma szkło (z rozmyciem na Androidzie 12+);
     * w miarę rozwijania panel staje się nieprzezroczysty, a szkło nagłówka znika. Wywoływane przy każdym przesunięciu panelu.
     */
    @JvmStatic
    fun onSheetSlide(sheet: View, content: View, slideOffset: Float) {
        if (!PiterfyAppearance.glassEnabled()) return   // wołane przy każdej klatce przesuwania: bez szkła nic nie liczymy
        val plan = plan(sheet.context)
        if (!plan.enabled) return
        val header = sheet.findViewById<View>(com.eddyizm.tempus.R.id.player_header_layout) ?: return
        if (header.background !is PiterfyGlassDrawable) {
            val glass = drawable(sheet.context, 14f) ?: return
            header.background = glass
            if (plan.blur) bindBlur(header, content, glass)
        }
        val offset = slideOffset.coerceIn(0f, 1f)
        header.background.alpha = (255 * (1f - (offset * 3f).coerceAtMost(1f))).toInt()
        val bg = sheet.background
        if (bg != null) {
            val mutable = bg.mutate()
            mutable.alpha = (255 * (offset * 2.2f).coerceAtMost(1f)).toInt()
            sheet.background = mutable
        }
    }

    /** Korzeń ekranu z nieprzezroczystym tłem koloru powierzchni dostaje przezroczystość, żeby widać było poświaty pod spodem. */
    @JvmStatic
    fun relaxBackground(root: View) {
        val plan = plan(root.context)
        if (!plan.enabled) return
        val bg = root.background as? ColorDrawable ?: return
        if (bg.alpha != 255 || (bg.color ushr 24) != 255) return
        root.background = ColorDrawable((bg.color and 0x00FFFFFF) or ((plan.fillAlpha * 255).toInt().coerceIn(0, 255) shl 24))
    }

    // ── dialogi i arkusze dolne ──

    /**
     * Dialog dostaje tło ze szkła (arkusz dolny: z zaokrąglonymi górnymi rogami), a na Androidzie 12+ — gdy system pozwala
     * na rozmycie okien — także rozmycie tego, co jest za oknem. Wywoływane dla DialogFragmentów, gdy się pokazują.
     */
    @JvmStatic
    fun styleDialog(dialog: Dialog?) {
        val ctx = dialog?.context ?: return
        val window = dialog.window ?: return
        val plan = plan(ctx)
        if (!plan.enabled) return
        val d = density(ctx)
        val sheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        if (sheet != null) {
            sheet.background = drawable(ctx, 28f, topCornersOnly = true)
        } else {
            val glass = drawable(ctx, 28f) ?: return
            window.setBackgroundDrawable(InsetDrawable(glass, (24 * d).toInt()))
        }
        if (Build.VERSION.SDK_INT >= 31 && plan.blur) {
            val blurAllowed = (ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.isCrossWindowBlurEnabled == true
            if (blurAllowed) {
                window.setBackgroundBlurRadius((plan.blurDp * d).toInt())
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.attributes = window.attributes.also { it.blurBehindRadius = (plan.blurDp * d * 0.6f).toInt() }
            }
        }
    }

    // ── sprężyste naciśnięcie ──

    /** Naciśnięcie jak sprężyna: pod palcem zapada się do [depth], po puszczeniu wraca z odbiciem. Nie przechwytuje dotyku. */
    @SuppressLint("ClickableViewAccessibility")
    @JvmStatic @JvmOverloads
    fun springPress(view: View, depth: Float = 0.96f) {
        fun spring(property: DynamicAnimation.ViewProperty) = SpringAnimation(view, property, 1f).apply {
            spring = SpringForce(1f).setStiffness(SpringForce.STIFFNESS_MEDIUM).setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY)
        }
        val sx = spring(DynamicAnimation.SCALE_X)
        val sy = spring(DynamicAnimation.SCALE_Y)
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx.spring.finalPosition = depth - 0.02f; sy.spring.finalPosition = depth - 0.02f; sx.start(); sy.start() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { sx.spring.finalPosition = 1f; sy.spring.finalPosition = 1f; sx.start(); sy.start() }
            }
            false
        }
    }
}
