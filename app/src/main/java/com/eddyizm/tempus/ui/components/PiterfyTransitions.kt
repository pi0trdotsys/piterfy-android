package com.eddyizm.tempus.ui.components

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.Navigation
import androidx.navigation.fragment.FragmentNavigator
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import com.eddyizm.tempus.R
import com.eddyizm.tempus.appearance.PiterfyAppearance
import com.eddyizm.tempus.util.PiterfyMorphPlan
import com.eddyizm.tempus.util.PiterfyMotion
import com.eddyizm.tempus.util.Preferences
import com.google.android.material.color.MaterialColors
import com.google.android.material.transition.MaterialContainerTransform
import java.util.concurrent.TimeUnit

/**
 * Piterfy: okładka „leci” z mini odtwarzacza na miejsce dużej okładki w miarę rozwijania panelu (duch rysowany nad panelem,
 * prawdziwe okładki ukryte na czas lotu, przenikanie na końcu drogi). Geometria: [PiterfyMorphPlan].
 *
 * Wszystko się wycofuje do dotychczasowej animacji panelu, gdy układ jest nietypowy (inna strona pagera, brak rozmiaru okładki,
 * telewizor, wyłączone animacje): wtedy [onSlide] po prostu nic nie robi.
 */
class PiterfyCoverMorph(
    private val root: FrameLayout,
    private val mini: ImageView,
    private val bigCover: () -> View?,
    private val television: Boolean,
) {
    private val density = root.resources.displayMetrics.density
    private var ghost: ImageView? = null
    private val outline = RoundOutline()
    private var active = false
    private var refused = false          // w tym geście morf odrzucony; nie próbujemy ponownie w połowie ruchu
    private var cachedMini: PiterfyMorphPlan.Rect? = null
    private var miniRect: PiterfyMorphPlan.Rect? = null
    private var bigRect: PiterfyMorphPlan.Rect? = null
    private var big: View? = null
    private var savedBigAlpha = 1f
    private var savedMiniVisibility = View.VISIBLE
    private var miniRadiusPx = 4f * density
    private var bigRadiusPx = 0f

    /** Wołane przy każdej zmianie przesunięcia panelu odtwarzacza (0 = zwinięty, 1 = rozwinięty). */
    fun onSlide(offset: Float) {
        if (!PiterfyMorphPlan.ghostVisible(offset)) { // panel w jednym ze stanów: pozbywamy się ducha i uczymy się, gdzie jest mini okładka
            if (offset <= PiterfyMorphPlan.EPS) cacheMini()
            end()
            return
        }
        if (refused) return
        if (!active && !begin()) { refused = true; return }
        update(offset)
    }

    /** Przywraca prawdziwe okładki i chowa ducha (wołane też przy niszczeniu widoku). */
    fun end() {
        if (active) {
            mini.visibility = savedMiniVisibility
            big?.alpha = savedBigAlpha
        }
        ghost?.visibility = View.GONE
        big = null
        active = false
        refused = false
    }

    private fun cacheMini() {
        val r = rectOf(mini) ?: return
        if (!r.isEmpty) cachedMini = r
    }

    private fun begin(): Boolean {
        cacheMini()
        val m = (rectOf(mini)?.takeUnless { it.isEmpty }) ?: cachedMini
        val bigView = bigCover()
        val b = bigView?.let { rectOf(it) }
        if (!PiterfyMorphPlan.shouldMorphCover(PiterfyMotion.enabled(), television, m, b, root.width, root.height)) return false
        val drawable = mini.drawable ?: return false
        val view = ghost ?: ImageView(root.context).also {
            it.scaleType = ImageView.ScaleType.CENTER_CROP
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            it.outlineProvider = outline
            it.clipToOutline = true
            it.pivotX = 0f
            it.pivotY = 0f
            root.addView(it, FrameLayout.LayoutParams(1, 1))
            ghost = it
        }
        val lp = view.layoutParams
        val bw = b!!.width.toInt().coerceAtLeast(1)
        val bh = b.height.toInt().coerceAtLeast(1)
        if (lp.width != bw || lp.height != bh) { lp.width = bw; lp.height = bh; view.layoutParams = lp }
        view.setImageDrawable(drawable.constantState?.newDrawable(root.resources)?.mutate() ?: drawable)
        view.visibility = View.VISIBLE
        bringToFront(view)
        miniRect = m
        bigRect = b
        big = bigView
        savedBigAlpha = bigView.alpha
        savedMiniVisibility = mini.visibility
        miniRadiusPx = 4f * density
        bigRadiusPx = if (Preferences.isCornerRoundingEnabled()) Preferences.getRoundedCornerSize() * density else 0f
        mini.visibility = View.INVISIBLE
        active = true
        return true
    }

    private fun bringToFront(view: View) {
        if (root.indexOfChild(view) != root.childCount - 1) view.bringToFront()
    }

    private fun update(offset: Float) {
        val g = ghost ?: return
        val from = miniRect ?: return
        val to = bigRect ?: return
        val r = PiterfyMorphPlan.lerp(from, to, offset)
        val scale = r.width / to.width
        g.translationX = r.left
        g.translationY = r.top
        g.scaleX = scale
        g.scaleY = scale
        g.alpha = PiterfyMorphPlan.ghostAlpha(offset)
        outline.radius = PiterfyMorphPlan.radiusAt(offset, miniRadiusPx, bigRadiusPx) / scale.coerceAtLeast(0.01f)
        g.invalidateOutline()
        big?.alpha = savedBigAlpha * PiterfyMorphPlan.bigCoverAlpha(offset)
    }

    /** Prostokąt widoku we współrzędnych korzenia panelu (uwzględnia przesunięcia i własną skalę widoku); null, gdy widok niegotowy. */
    private fun rectOf(view: View): PiterfyMorphPlan.Rect? {
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return null
        val a = IntArray(2)
        val b = IntArray(2)
        root.getLocationInWindow(a)
        view.getLocationInWindow(b)
        val left = (b[0] - a[0]).toFloat()
        val top = (b[1] - a[1]).toFloat()
        return PiterfyMorphPlan.Rect(left, top, left + view.width * view.scaleX, top + view.height * view.scaleY)
    }
}

private class RoundOutline : ViewOutlineProvider() {
    var radius = 0f
    override fun getOutline(view: View, out: Outline) { out.setRoundRect(0, 0, view.width, view.height, radius) }
}

/**
 * Piterfy: przejścia z morfem między listą a stroną albumu, wykonawcy lub playlisty (MaterialContainerTransform): kliknięta
 * karta rozwija się w całą stronę, a po powrocie strona zwija się z powrotem do karty. Karty nie trzeba oznaczać w adapterach:
 * kliknięty element znajdujemy po ostatnim dotknięciu ([recordTouch] z MainActivity) jako element RecyclerView pod palcem.
 *
 * Bezpieczeństwo: wszystko jest za bramką [PiterfyMotion.enabled], nie działa na telewizorze, a przy braku klikniętej karty
 * zostaje zwykła nawigacja. Strony czekają na rysowanie najwyżej [PiterfyMorphPlan.POSTPONE_TIMEOUT_MS] ms, więc ekran nie zawisa.
 */
object PiterfyTransitions {
    const val ARG_NAME = "pf_morph_name"

    private class Pending(
        val sourceId: Int, val sourceClass: String?, val recyclerId: Int, val position: Int, val name: String,
        var returning: Boolean = false,
    )

    private val stack = ArrayDeque<Pending>()
    private var touchX = 0f
    private var touchY = 0f
    private var touchAt = 0L
    private var television = false
    private var forwardAt = 0L          // chwila ostatniej nawigacji „do przodu” z morfem (odróżnia ją od powrotu)

    /** Podpina śledzenie nawrotów do ekranu źródłowego: wołane raz z MainActivity po utworzeniu NavController. */
    @JvmStatic fun install(navController: NavController, isTelevision: Boolean) {
        television = isTelevision
        navController.addOnDestinationChangedListener { _, destination, _ -> onDestination(destination) }
    }

    /** Zapamiętuje ostatnie dotknięcie (współrzędne ekranu); wołane z MainActivity.dispatchTouchEvent. */
    @JvmStatic fun recordTouch(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchX = event.rawX
            touchY = event.rawY
            touchAt = System.currentTimeMillis()
        }
    }

    /**
     * Nawigacja do strony szczegółów z morfem z klikniętej karty; bez karty (albo bez animacji) zwykła nawigacja.
     * Zastępuje `Navigation.findNavController(view).navigate(destinationId, args)`.
     */
    @JvmStatic fun navigate(from: View, destinationId: Int, args: Bundle?) {
        val nav = Navigation.findNavController(from)
        val target = if (PiterfyMorphPlan.touchFresh(System.currentTimeMillis(), touchAt) && PiterfyMotion.enabled() && !television)
            itemUnderTouch(from) else null
        if (!PiterfyMorphPlan.shouldMorphList(PiterfyMotion.enabled(), television, PiterfyMorphPlan.touchFresh(System.currentTimeMillis(), touchAt), target != null)) {
            nav.navigate(destinationId, args)
            return
        }
        target!!
        val name = PiterfyMorphPlan.transitionName(destinationId, target.recycler.id, target.position)
        target.item.transitionName = name
        val bundle = Bundle(args ?: Bundle.EMPTY).apply { putString(ARG_NAME, name) }
        val current = nav.currentDestination
        stack.addLast(Pending(current?.id ?: 0, (current as? FragmentNavigator.Destination)?.className, target.recycler.id, target.position, name))
        while (stack.size > 8) stack.removeFirst()
        val extras = FragmentNavigator.Extras.Builder().addSharedElement(target.item, name).build()
        forwardAt = System.currentTimeMillis()
        nav.navigate(destinationId, bundle, null, extras)
    }

    // ── ekran docelowy (strona albumu, wykonawcy, playlisty) ──

    /** Wołane z FragmentLifecycleCallbacks.onFragmentCreated: ustawia przejścia kontenera dla strony wywołanej z morfem. */
    @JvmStatic fun onCreated(fragment: Fragment, saved: Bundle?) {
        if (!isMorphDestination(fragment)) return
        val context = fragment.context ?: return
        if (saved == null) fragment.sharedElementEnterTransition = containerTransform(context)
        fragment.sharedElementReturnTransition = containerTransform(context)
    }

    /**
     * Wołane z FragmentLifecycleCallbacks.onFragmentViewCreated. Zwraca true, gdy ten ekran bierze udział w morfie (cel albo
     * ekran, na który wracamy) — wtedy MainActivity nie dokłada własnego wejścia ekranu (zanik + wjazd), żeby się nie dublowało.
     */
    @JvmStatic fun onViewCreated(fragment: Fragment, view: View, saved: Bundle?): Boolean {
        if (isMorphDestination(fragment)) {
            view.transitionName = fragment.arguments?.getString(ARG_NAME)
            if (saved == null) startWhenDrawn(fragment, view)
            return saved == null
        }
        val pending = stack.lastOrNull()
        if (pending != null && pending.returning && PiterfyMotion.enabled() && !television &&
            fragment.parentFragment is NavHostFragment && fragment.javaClass.name == pending.sourceClass
        ) {
            stack.removeLast()
            startWhenDrawn(fragment, view, ready = { findItem(view, pending) != null }) { findItem(view, pending)?.transitionName = pending.name }
            return true
        }
        return false
    }

    private fun isMorphDestination(fragment: Fragment): Boolean =
        fragment.parentFragment is NavHostFragment && fragment.arguments?.getString(ARG_NAME) != null &&
            PiterfyMotion.enabled() && !television

    private fun onDestination(destination: NavDestination) {
        if (System.currentTimeMillis() - forwardAt < 1500) { forwardAt = 0L; return } // nawigacja do przodu: stos zostaje
        val index = stack.indexOfLast { PiterfyMorphPlan.isReturnTo(it.sourceId, destination.id) }
        if (index < 0) { stack.clear(); return }                    // przejście gdzie indziej (np. dolna nawigacja)
        while (stack.size > index + 1) stack.removeLast()
        stack.last().returning = true
    }

    // ── czekanie na układ z limitem czasu ──

    /**
     * Wstrzymuje przejście do pierwszego narysowania (i do chwili, gdy [ready] jest spełnione, np. lista na ekranie powrotnym
     * zbudowała kartę), ale nie dłużej niż [PiterfyMorphPlan.POSTPONE_TIMEOUT_MS]: po limicie czasu start wymusza system.
     */
    private fun startWhenDrawn(fragment: Fragment, view: View, ready: () -> Boolean = { true }, prepare: () -> Unit = {}) {
        fragment.postponeEnterTransition(PiterfyMorphPlan.POSTPONE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val deadline = System.currentTimeMillis() + PiterfyMorphPlan.POSTPONE_TIMEOUT_MS - 60
        var done = false
        lateinit var listener: ViewTreeObserver.OnPreDrawListener
        listener = ViewTreeObserver.OnPreDrawListener {
            if (!done && (ready() || System.currentTimeMillis() >= deadline)) {
                done = true
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(listener)
                prepare()
                fragment.startPostponedEnterTransition()
            }
            true
        }
        view.viewTreeObserver.addOnPreDrawListener(listener)
    }

    // ── szukanie karty ──

    private class Target(val recycler: RecyclerView, val item: View, val position: Int)

    private fun itemUnderTouch(root: View): Target? {
        val x = touchX.toInt()
        val y = touchY.toInt()
        val rv = deepestRecycler(root, x, y) ?: return null
        val at = IntArray(2)
        rv.getLocationOnScreen(at)
        val item = rv.findChildViewUnder((x - at[0]).toFloat(), (y - at[1]).toFloat()) ?: return null
        val position = rv.getChildAdapterPosition(item)
        if (position < 0 || item.width <= 0 || item.height <= 0 || rv.id == View.NO_ID) return null
        return Target(rv, item, position)
    }

    private fun deepestRecycler(view: View, x: Int, y: Int): RecyclerView? {
        if (view.visibility != View.VISIBLE) return null
        val box = Rect()
        if (!view.getGlobalVisibleRect(box) || !box.contains(x, y)) return null
        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                deepestRecycler(view.getChildAt(i), x, y)?.let { return it }
            }
        }
        return view as? RecyclerView
    }

    /** Karta na ekranie powrotnym: element listy o tej samej pozycji w tej samej liście; null, gdy lista jeszcze się nie zbudowała. */
    private fun findItem(root: View, pending: Pending): View? {
        val rv = root.findViewById<RecyclerView>(pending.recyclerId) ?: return null
        return rv.findViewHolderForAdapterPosition(pending.position)?.itemView
    }

    // ── samo przejście ──

    private fun containerTransform(context: Context): MaterialContainerTransform = MaterialContainerTransform().apply {
        drawingViewId = R.id.nav_host_fragment
        duration = PiterfyMorphPlan.DURATION_MS
        interpolator = PathInterpolator(0.2f, 0.9f, 0.25f, 1f)      // szybki start i miękkie wyhamowanie (jak sprężyna bez odbicia)
        scrimColor = Color.TRANSPARENT
        fadeMode = MaterialContainerTransform.FADE_MODE_THROUGH
        // przy Liquid Glass tło strony jest półprzezroczyste, więc kontener też nie może być nieprzezroczysty
        setAllContainerColors(
            if (PiterfyAppearance.glassEnabled()) Color.TRANSPARENT
            else MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, Color.BLACK),
        )
    }
}
