package com.eddyizm.tempus.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.style.LineBackgroundSpan
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import androidx.core.widget.ImageViewCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.eddyizm.tempus.R
import com.eddyizm.tempus.appearance.PiterfyAppearance
import com.eddyizm.tempus.util.PiterfyAmbient
import com.eddyizm.tempus.util.PiterfyGlassPlan
import com.eddyizm.tempus.util.PiterfyMotion
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min

/**
 * Piterfy: „Liquid Glass” w pełnoekranowym widoku „Teraz gra”. Przy włączonym szkle panel sterowania (tytuł, wykonawca, pasek
 * postępu, przyciski, skróty tekstu i kolejki) leży na jednej dużej szklanej karcie, pod którą jest rozmyta okładka (albo
 * Canvas); przyciski są szklanymi kółkami ze sprężystym naciśnięciem, pasek postępu ma szklaną pigułkę pod torem, a skrót
 * tekstów ma szklany wskaźnik, który sprężyście przesuwa się i znika. Przy wyłączonym szkle nic z tego nie jest instalowane.
 *
 * Rozmycie (Android 12+) bierze zawartość spod karty przez [PiterfyGlassDrawable]; niżej karta jest samą półprzezroczystością
 * z obwódką. Animacje respektują [PiterfyGlassPlan.Plan.animate] (systemowe animacje, oszczędzanie energii).
 */
object PiterfyGlassPlayer {
    private var current: WeakReference<Handle>? = null

    /** Kolor akcentu szkła z koloru okładki: rozjaśniony, żeby na ciemnej karcie był wyraźny. */
    @JvmStatic
    fun accentOf(ambient: Int): Int = ColorUtils.blendARGB(ambient, Color.WHITE, 0.32f) or (0xFF shl 24)

    /** Instalacja na układzie kontrolera odtwarzacza (ConstraintLayout z paskiem postępu i przyciskami); null, gdy szkło wyłączone. */
    @JvmStatic
    fun install(owner: LifecycleOwner, root: ViewGroup): Handle? {
        val ctx = root.context
        if (ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) return null
        val plan = PiterfyGlass.plan(ctx)
        if (!plan.enabled) return null
        val handle = Handle(root, plan)
        handle.install()
        current = WeakReference(handle)
        PiterfyAmbient.observe(owner) { color -> handle.setAccent(color) }
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                handle.release()
                if (current?.get() === handle) current = null
            }
        })
        return handle
    }

    /** Z MainActivity przez PlayerBottomSheetFragment: rozwinięcie odtwarzacza odpala wejście karty, zwinięcie wstrzymuje poświatę. */
    @JvmStatic
    fun onSheetState(state: Int) {
        current?.get()?.onSheetState(state)
    }

    /** Rozmyta okładka jako tło pełnego odtwarzacza (zamiast Canvasa): przybliżona, na Androidzie 12+ rozmyta efektem, niżej mała i wygładzona. */
    @JvmStatic
    fun styleBackdrop(image: ImageView) {
        val plan = PiterfyGlass.plan(image.context)
        val d = image.resources.displayMetrics.density
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.scaleX = BACKDROP_SCALE
        image.scaleY = BACKDROP_SCALE
        val saturate = ColorMatrix().apply { setSaturation(1.35f) }
        image.colorFilter = ColorMatrixColorFilter(saturate)
        if (Build.VERSION.SDK_INT >= 31 && plan.blur) {
            val radius = (plan.blurDp * 1.5f + 14f) * d
            image.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
        } else if (Build.VERSION.SDK_INT >= 31) {
            image.setRenderEffect(null)
        }
    }

    /** Ładuje okładkę do tła; na Androidzie poniżej 12 w bardzo małej rozdzielczości (rozciągnięta wygląda jak rozmycie). */
    @JvmStatic
    fun loadBackdrop(image: ImageView, model: Any?) {
        if (model == null) return
        val plan = PiterfyGlass.plan(image.context)
        var request = Glide.with(image).load(model)
        if (!(Build.VERSION.SDK_INT >= 31 && plan.blur)) request = request.override(40, 40)
        request.listener(object : RequestListener<Drawable> {
            override fun onLoadFailed(e: GlideException?, m: Any?, t: Target<Drawable>, first: Boolean) = false
            override fun onResourceReady(r: Drawable, m: Any, t: Target<Drawable>?, source: DataSource, first: Boolean): Boolean {
                current?.get()?.coverChanged()
                return false
            }
        }).into(image)
    }

    private const val BACKDROP_SCALE = 1.25f

    /**
     * Tło wiersza aktualnie granego utworu w kolejce: szklana pigułka. Zwraca true, gdy szkło jest włączone (wtedy wiersz
     * dostaje tło szkła albo je traci); przy wyłączonym szkle nie dotykamy wyglądu wiersza.
     */
    @JvmStatic
    fun styleQueueRow(row: View, current: Boolean): Boolean {
        if (!PiterfyAppearance.glassEnabled()) return false
        val ctx = row.context
        val d = ctx.resources.displayMetrics.density
        row.background = if (current) {
            val glass = PiterfyGlass.drawable(ctx, 18f, false, Color.WHITE)
            glass?.alpha = 70
            glass?.let { android.graphics.drawable.InsetDrawable(it, (8 * d).toInt(), (2 * d).toInt(), (8 * d).toInt(), (2 * d).toInt()) }
        } else null
        return true
    }

    /** Tło zaznaczonej linijki tekstu piosenki: szklana pigułka pod całą linią (używa PlayerLyricsFragment). */
    @JvmStatic
    fun lyricLineBackground(context: Context): LineBackgroundSpan? {
        val plan = PiterfyGlass.plan(context)
        if (!plan.enabled) return null
        return GlassLineSpan(context.resources.displayMetrics.density, plan)
    }

    private class GlassLineSpan(private val density: Float, plan: PiterfyGlassPlan.Plan) : LineBackgroundSpan {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(Color.WHITE, (46 + plan.sheenAlpha * 120).toInt().coerceAtMost(110)) }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = density
            color = ColorUtils.setAlphaComponent(Color.WHITE, (plan.edgeAlpha * 255 * 0.7f).toInt())
        }
        private val rect = RectF()
        override fun drawBackground(c: Canvas, p: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
                                    text: CharSequence, start: Int, end: Int, lineNumber: Int) {
            rect.set(left - 10 * density, top + 1 * density, right + 10 * density, bottom - 1 * density)
            val r = 14 * density
            c.drawRoundRect(rect, r, r, fill)
            c.drawRoundRect(rect, r, r, edge)
        }
    }

    /** Uchwyt zainstalowanej szklanej karty: akcent, strona (okładka/tekst), stan odtwarzania i animacja wejścia. */
    class Handle internal constructor(private val root: ViewGroup, private val plan: PiterfyGlassPlan.Plan) {
        private val ctx = root.context
        private val d = ctx.resources.displayMetrics.density
        private fun v(id: Int): View? = root.findViewById(id)

        private val title = v(R.id.player_media_title_label)
        private val artist = v(R.id.player_artist_name_label)
        private val rating = v(R.id.rating_container)
        private val progress = v(R.id.exo_progress)
        private val position = v(R.id.exo_position)
        private val duration = v(R.id.exo_duration)
        private val favorite = v(R.id.button_favorite)
        private val overflow = v(R.id.player_overflow_button)
        private val quick = v(R.id.player_quick_action_view)
        private val lyricsButton = v(R.id.player_open_lyrics_button)
        private val play = v(R.id.exo_play_pause)
        private val transport = listOf(R.id.exo_prev, R.id.exo_next, R.id.exo_shuffle, R.id.exo_repeat_toggle, R.id.exo_rew, R.id.exo_ffwd)
            .mapNotNull { v(it) }
        private val regionViews = listOfNotNull(title, artist, rating, progress, position, duration, favorite, overflow, play, quick) + transport
        private val controls = listOfNotNull(title, artist, progress, position, duration, favorite, overflow, play, quick) + transport

        private val card = PiterfyGlass.drawable(ctx, 30f, false, ColorUtils.blendARGB(PiterfyGlass.surfaceColor(ctx), Color.WHITE, 0.10f))!!
        private val panel = PanelDrawable(card, plan, d)
        private var playGlass: PiterfyGlassDrawable? = null
        private var accent = PiterfyGlassPlayer.accentOf(PiterfyAmbient.DEFAULT)
        private var page = 0
        private var sheetOpen = false
        private var playing = false
        private var breathing: ValueAnimator? = null
        private var entering = false

        /** Tylko testy: źródło rozmycia i tła niezależne od drzewa widoków arkusza odtwarzacza. */
        internal var sourceOverride: View? = null

        private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> root.invalidate() }

        internal fun install() {
            root.background = panel
            root.addOnLayoutChangeListener(layoutListener)
            card.blurHost = root
            card.blurSourceProvider = { blurSource() }
            if (Build.VERSION.SDK_INT >= 31 && plan.blur) PiterfyGlass.bindBlur(root, null, card)
            panel.region = { region() }
            panel.track = { trackRect() }
            panel.dock = { dockRect() }
            panel.indicatorTarget = { indicatorTarget() }

            val surface = ColorUtils.blendARGB(PiterfyGlass.surfaceColor(ctx), Color.WHITE, 0.32f)
            transport.forEach { button ->
                PiterfyGlass.apply(button, 24f, surface)
                PiterfyGlass.springPress(button, 0.92f)
            }
            play?.let { button ->
                val glass = PiterfyGlass.drawable(ctx, 36f, false, accent)
                playGlass = glass
                if (glass != null) {
                    val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 36 * d }
                    button.background = RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), glass, mask)
                }
                (button as? ImageView)?.let { ImageViewCompat.setImageTintList(it, ColorStateList.valueOf(Color.WHITE)) }
                PiterfyGlass.springPress(button, 0.9f)
            }
            listOfNotNull(favorite, overflow).forEach { PiterfyGlass.springPress(it, 0.9f) }
            listOfNotNull(quick?.let { it as? ViewGroup }).forEach { group ->
                for (i in 0 until group.childCount) PiterfyGlass.springPress(group.getChildAt(i), 0.9f)
            }
            (title as? android.widget.TextView)?.setShadowLayer(8 * d, 0f, 2 * d, 0x66000000)
            (artist as? android.widget.TextView)?.setTextColor(0xFFE0E0E0.toInt())

            // okładka z miękkim cieniem i lekkim uniesieniem (cień rysuje sam kompozyt okładki, tu zdejmujemy przycinanie)
            val pager = v(R.id.player_media_cover_view_pager) as? ViewGroup
            root.clipChildren = false
            pager?.clipChildren = false
            (pager?.getChildAt(0) as? ViewGroup)?.clipChildren = false
            pager?.translationY = -6 * d
        }

        fun release() {
            breathing?.cancel(); breathing = null
            root.removeOnLayoutChangeListener(layoutListener)
        }

        fun setAccent(ambient: Int) {
            accent = accentOf(ambient)
            playGlass?.surfaceColor = accent
            panel.accent = accent
        }

        /** Strona okładka (0) / tekst (1): wskaźnik na przycisku tekstów sprężyście wjeżdża i znika. */
        fun setPage(position: Int) {
            page = position
            panel.invalidateSelf()
        }

        fun coverChanged() {
            card.invalidateBlur()
            root.invalidate()
        }

        fun onPlaying(isPlaying: Boolean) {
            val changed = playing != isPlaying
            playing = isPlaying
            updateBreathing()
            val button = play ?: return
            if (!changed || !plan.animate || !PiterfyMotion.enabled()) return
            // morf play ↔ pauza: przycisk zapada się i sprężyście wraca, ikona wyłania się z lekkim zanikiem
            button.scaleX = 0.78f; button.scaleY = 0.78f; button.alpha = 0.6f
            listOf(DynamicAnimation.SCALE_X, DynamicAnimation.SCALE_Y, DynamicAnimation.ALPHA).forEach { property ->
                SpringAnimation(button, property, 1f).apply {
                    spring = SpringForce(1f).setStiffness(SpringForce.STIFFNESS_LOW).setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY)
                    start()
                }
            }
        }

        fun onSheetState(state: Int) {
            val wasOpen = sheetOpen
            sheetOpen = state != STATE_COLLAPSED && state != STATE_HIDDEN
            if (state == STATE_EXPANDED && !wasOpen) enter()
            updateBreathing()
        }

        /** Wejście przy rozwinięciu odtwarzacza: karta wyłania się, a elementy wjeżdżają jeden po drugim (sprężyście). */
        fun enter() {
            if (!plan.animate || !PiterfyMotion.enabled() || entering) return
            entering = true
            panel.fadeIn()
            controls.forEachIndexed { index, view ->
                view.alpha = 0f
                view.translationY = 24 * d
                view.animate().alpha(1f).translationY(0f).setStartDelay(PiterfyMotion.delayFor(index / 2))
                    .setDuration(460).setInterpolator(OvershootInterpolator(0.7f)).start()
            }
            root.postDelayed({ entering = false }, 900)
        }

        /** Delikatne „oddychanie” rozmytej okładki za kartą, tylko gdy gra muzyka, panel jest rozwinięty i animacje są dozwolone. */
        private fun updateBreathing() {
            val image = backdropImage()
            val run = plan.animate && sheetOpen && playing && image != null && image.isShown
            if (run && breathing == null) {
                val target = image!!
                breathing = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 5200
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = DecelerateInterpolator(0.6f)
                    addUpdateListener {
                        val f = it.animatedValue as Float
                        target.scaleX = BACKDROP_SCALE + 0.06f * f
                        target.scaleY = BACKDROP_SCALE + 0.06f * f
                        target.alpha = 0.86f + 0.14f * f
                    }
                    start()
                }
            } else if (!run && breathing != null) {
                breathing?.cancel(); breathing = null
                image?.let { it.scaleX = BACKDROP_SCALE; it.scaleY = BACKDROP_SCALE; it.alpha = 1f }
            }
        }

        private fun backdropImage(): ImageView? =
            (sourceOverride as? ImageView) ?: (root.rootView.findViewById<View>(R.id.player_glass_backdrop) as? ImageView)?.takeIf { it.visibility == View.VISIBLE }

        /** Źródło rozmycia karty: animowany Canvas, gdy jest widoczny, w przeciwnym razie rozmyta okładka (statyczna, więc z pamięcią podręczną). */
        private fun blurSource(): View? {
            sourceOverride?.let { card.blurStatic = true; return it }
            val canvas = root.rootView.findViewById<View>(R.id.player_canvas_view)
            if (canvas != null && canvas.visibility == View.VISIBLE) { card.blurStatic = false; return canvas }
            val image = backdropImage()
            card.blurStatic = true
            return image
        }

        // ── geometria ──

        private fun region(): Rect? {
            var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = Int.MIN_VALUE; var b = Int.MIN_VALUE
            for (view in regionViews) {
                if (view.visibility != View.VISIBLE || view.width == 0 || view.height == 0) continue
                l = min(l, view.left); t = min(t, view.top); r = max(r, view.right); b = max(b, view.bottom)
            }
            if (l == Int.MAX_VALUE) return null
            val pad = (14 * d).toInt(); val inset = (8 * d).toInt()
            return Rect(max(inset, l - pad), max(inset, t - pad), min(root.width - inset, r + pad), min(root.height - inset / 2, b + pad / 2))
        }

        private fun trackRect(): RectF? {
            val p = progress ?: return null
            if (p.visibility != View.VISIBLE || p.width == 0) return null
            val cy = (p.top + p.bottom) / 2f
            return RectF(p.left + 12 * d, cy - 9 * d, p.right - 12 * d, cy + 9 * d)
        }

        private fun dockRect(): RectF? {
            val q = quick ?: return null
            if (q.visibility != View.VISIBLE || q.width == 0) return null
            return RectF(q.left + 20 * d, q.top + 4 * d, q.right - 20 * d, q.bottom - 4 * d)
        }

        private fun indicatorTarget(): RectF? {
            val q = quick ?: return null
            val b = lyricsButton ?: return null
            if (page != 1 || q.visibility != View.VISIBLE || b.width == 0) return null
            return RectF(q.left + b.left - 4 * d, q.top + b.top + 2 * d, q.left + b.right + 4 * d, q.top + b.bottom - 2 * d)
        }
    }

    private const val STATE_EXPANDED = 3
    private const val STATE_COLLAPSED = 4
    private const val STATE_HIDDEN = 5

    /**
     * Tło kontrolera odtwarzacza: duża szklana karta pod sterowaniem + szklana pigułka pod torem postępu + szklany „dok” pod
     * skrótami tekstu i kolejki + wskaźnik aktywnej strony. Karta jest zwykłym [PiterfyGlassDrawable] (z rozmyciem na Androidzie 12+).
     */
    internal class PanelDrawable(
        private val card: PiterfyGlassDrawable,
        private val plan: PiterfyGlassPlan.Plan,
        private val density: Float,
    ) : Drawable() {
        var region: (() -> Rect?)? = null
        var track: (() -> RectF?)? = null
        var dock: (() -> RectF?)? = null
        var indicatorTarget: (() -> RectF?)? = null
        var accent = Color.WHITE

        private var userAlpha = 255
        private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val pillEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density }
        private val indicator = RectF()
        private val tmp = RectF()
        private var indicatorAlpha = 0f
        private var lastTarget: RectF? = null
        private var indicatorAnimator: ValueAnimator? = null
        private var fadeAnimator: ValueAnimator? = null

        fun fadeIn() {
            fadeAnimator?.cancel()
            fadeAnimator = ValueAnimator.ofInt(0, 255).apply {
                duration = 520
                addUpdateListener { setAlpha(it.animatedValue as Int) }
                start()
            }
        }

        override fun draw(canvas: Canvas) {
            val r = region?.invoke() ?: return
            card.alpha = userAlpha
            card.setBounds(r.left, r.top, r.right, r.bottom)
            card.draw(canvas)
            dock?.invoke()?.let { drawPill(canvas, it, 0.07f, 0.16f) }
            track?.invoke()?.let { drawPill(canvas, it, 0.10f, 0.22f) }
            drawIndicator(canvas)
        }

        private fun drawPill(canvas: Canvas, rect: RectF, fill: Float, edge: Float) {
            val k = (0.7f + plan.sheenAlpha * 1.6f) * userAlpha / 255f
            pillFill.color = ColorUtils.setAlphaComponent(Color.WHITE, (255 * fill * k).toInt().coerceIn(0, 255))
            pillEdge.color = ColorUtils.setAlphaComponent(Color.WHITE, (255 * edge * k * (0.6f + plan.edgeAlpha)).toInt().coerceIn(0, 255))
            val radius = rect.height() / 2f
            canvas.drawRoundRect(rect, radius, radius, pillFill)
            canvas.drawRoundRect(rect, radius, radius, pillEdge)
        }

        private fun drawIndicator(canvas: Canvas) {
            val target = indicatorTarget?.invoke()
            if (!sameRect(target, lastTarget)) moveIndicator(target)
            if (indicatorAlpha <= 0.01f) return
            val a = (255 * indicatorAlpha * userAlpha / 255f).toInt().coerceIn(0, 255)
            pillFill.color = ColorUtils.setAlphaComponent(accent, (a * 0.42f).toInt())
            pillEdge.color = ColorUtils.setAlphaComponent(Color.WHITE, (a * 0.5f).toInt())
            val radius = indicator.height() / 2f
            canvas.drawRoundRect(indicator, radius, radius, pillFill)
            canvas.drawRoundRect(indicator, radius, radius, pillEdge)
        }

        private fun sameRect(a: RectF?, b: RectF?): Boolean =
            (a == null && b == null) || (a != null && b != null && kotlin.math.abs(a.left - b.left) < 1f && kotlin.math.abs(a.top - b.top) < 1f &&
                kotlin.math.abs(a.right - b.right) < 1f)

        private fun moveIndicator(target: RectF?) {
            val previous = lastTarget
            lastTarget = target?.let { RectF(it) }
            indicatorAnimator?.cancel()
            if (target == null) {
                val from = indicatorAlpha
                indicatorAnimator = ValueAnimator.ofFloat(from, 0f).apply {
                    duration = if (plan.animate) 220 else 0
                    addUpdateListener { indicatorAlpha = it.animatedValue as Float; invalidateSelf() }
                    start()
                }
                return
            }
            val startRect = if (previous == null || indicatorAlpha < 0.05f) RectF(target.centerX() - 1f, target.centerY() - 1f, target.centerX() + 1f, target.centerY() + 1f)
            else RectF(indicator)
            val endRect = RectF(target)
            val fromAlpha = indicatorAlpha
            if (!plan.animate) { indicator.set(endRect); indicatorAlpha = 1f; invalidateSelf(); return }
            indicatorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 420
                interpolator = OvershootInterpolator(1.1f)
                addUpdateListener {
                    val f = it.animatedValue as Float
                    tmp.set(startRect.left + (endRect.left - startRect.left) * f, startRect.top + (endRect.top - startRect.top) * f,
                        startRect.right + (endRect.right - startRect.right) * f, startRect.bottom + (endRect.bottom - startRect.bottom) * f)
                    indicator.set(tmp)
                    indicatorAlpha = (fromAlpha + (1f - fromAlpha) * it.animatedFraction.coerceIn(0f, 1f)).coerceIn(0f, 1f)
                    invalidateSelf()
                }
                start()
            }
        }

        override fun setAlpha(alpha: Int) { userAlpha = alpha; invalidateSelf() }
        override fun getAlpha() = userAlpha
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
