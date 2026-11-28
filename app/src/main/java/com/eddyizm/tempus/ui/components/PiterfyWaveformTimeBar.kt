package com.eddyizm.tempus.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.OptIn
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.TimeBar
import com.eddyizm.tempus.util.PiterfyCoverTheme
import com.eddyizm.tempus.util.PiterfyHaptics
import com.eddyizm.tempus.util.PiterfyMotion
import com.eddyizm.tempus.util.PiterfyPeaksModel
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Piterfy: pasek postępu z falą dźwięku. To pełnoprawny [TimeBar] — media3 (PlayerControlView) znajduje go po `exo_progress`
 * i sam podaje pozycję, bufor i czas trwania, więc zastępuje DefaultTimeBar bez zmian w kontrolerze. Dopóki nie ma fali
 * ([setPeaks], dane z serwera) rysuje zwykły pasek, taki jak dotychczas; z falą rysuje słupki: odtworzona część w kolorze
 * akcentu ([setAccent]), reszta przyćmiona. Dotknięcie i przeciąganie przewija (dymek z czasem nad palcem, impulsy co 5 %),
 * a gdy utwór gra, słupki wokół znacznika lekko „oddychają” (tylko przy włączonych animacjach systemu).
 *
 * Dostępność: klasa SeekBar, stan „pozycja z czasu trwania”, akcje przewiń w przód/wstecz i ustaw postęp oraz strzałki
 * klawiatury i pilota. Nic nie wymaga fali, więc TalkBack działa tak samo w obu trybach.
 */
@OptIn(UnstableApi::class)
class PiterfyWaveformTimeBar @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs), TimeBar {
    private val d = resources.displayMetrics.density
    private val sp = d * resources.configuration.fontScale

    private val listeners = CopyOnWriteArraySet<TimeBar.OnScrubListener>()
    private var position = 0L
    private var buffered = 0L
    private var duration = 0L
    private var scrubbing = false
    private var scrubPosition = 0L
    private var keyTimeIncrement = C.TIME_UNSET
    private var keyCountIncrement = 20

    private var peaks: PiterfyPeaksModel.Peaks? = null
    private var bars = FloatArray(0)
    private var barsWidth = -1
    private var accent = 0xFFFFFFFF.toInt()
    private var playing = false
    private var phase = 0f
    private var breathing: ValueAnimator? = null
    private val haptics = PiterfyHaptics.Throttle()
    private var lastDetent = -1
    private var announcedSecond = -1L

    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val unplayedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4DFFFFFF }
    private val bufferedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4DFFFFFF }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF0202020.toInt() }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 11f * sp; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val rect = RectF()

    private val sidePad get() = 11f * d
    private val bubbleZone get() = 18f * d

    init {
        isFocusable = true
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    // ── dane ──

    /** Fala utworu z serwera albo null (wtedy zwykły pasek). Zmiana utworu powinna wołać z null, zanim dojdzie nowa fala. */
    fun setPeaks(value: PiterfyPeaksModel.Peaks?) {
        if (peaks == value) return
        peaks = value
        barsWidth = -1
        updateBreathing()
        invalidate()
    }

    fun hasPeaks() = peaks != null

    /** Kolor odtworzonej części; dobierany tak, żeby był widoczny na ciemnej karcie (kontrast ≥ 3 z tłem odtwarzacza). */
    fun setAccent(color: Int) {
        val shown = PiterfyCoverTheme.visibleOnDark(color, DARK_BACKGROUND)
        if (shown == accent) return
        accent = shown
        invalidate()
    }

    /** Czy utwór gra: tylko wtedy słupki przy znaczniku „oddychają”. */
    fun setPlaying(value: Boolean) {
        if (playing == value) return
        playing = value
        updateBreathing()
    }

    // ── TimeBar ──

    override fun addListener(listener: TimeBar.OnScrubListener) { listeners.add(listener) }
    override fun removeListener(listener: TimeBar.OnScrubListener) { listeners.remove(listener) }
    override fun setKeyTimeIncrement(time: Long) { keyTimeIncrement = time }
    override fun setKeyCountIncrement(count: Int) { keyCountIncrement = max(1, count) }

    override fun setPosition(positionMs: Long) {
        if (position == positionMs) return
        position = max(0L, positionMs)
        if (!scrubbing) invalidate()
        announce()
    }

    override fun setBufferedPosition(bufferedPositionMs: Long) {
        buffered = max(0L, bufferedPositionMs)
        if (peaks == null) invalidate()
    }

    override fun setDuration(durationMs: Long) {
        val value = if (durationMs == C.TIME_UNSET || durationMs < 0) 0L else durationMs
        if (duration == value) return
        duration = value
        if (scrubbing && duration <= 0L) stopScrub(false)
        invalidate()
    }

    override fun getPreferredUpdateDelay(): Long = 200L

    override fun setAdGroupTimesMs(adGroupTimesMs: LongArray?, playedAdGroups: BooleanArray?, adGroupCount: Int) { /* reklam nie ma */ }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (scrubbing && !enabled) stopScrub(true)
        invalidate()   // wyłączony pasek (np. strumień na żywo) rysujemy przyćmiony w onDraw; nie ruszamy alpha widoku (animuje ją szkło)
    }

    /** Środek toru (px od góry widoku) — dla szklanej pigułki pod paskiem w trybie Liquid Glass. */
    fun trackCenterY(): Float = (areaTop() + areaBottom()) / 2f

    /** Połowa wysokości toru (px); fala sięga tak wysoko nad i pod środek. */
    fun trackHalfHeight(): Float = (areaBottom() - areaTop()) / 2f

    // ── rysowanie ──

    private fun areaLeft() = sidePad
    private fun areaRight() = width - sidePad
    private fun areaTop() = bubbleZone
    private fun areaBottom() = height - 4f * d

    private fun fraction(): Float {
        if (duration <= 0L) return 0f
        val p = if (scrubbing) scrubPosition else position
        return (p.toFloat() / duration).coerceIn(0f, 1f)
    }

    private fun ensureBars() {
        val w = (areaRight() - areaLeft()).toInt()
        if (w == barsWidth) return
        barsWidth = w
        val count = max(8, ((w + GAP_DP * d) / ((BAR_DP + GAP_DP) * d)).toInt())
        bars = peaks?.let { PiterfyPeaksModel.downsample(it.values, count) } ?: FloatArray(0)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val dimmed = !isEnabled
        val layer = if (dimmed) canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), 128) else -1
        if (peaks != null) { ensureBars(); if (bars.isNotEmpty()) drawBars(canvas) else drawPlain(canvas) } else drawPlain(canvas)
        if (scrubbing) drawBubble(canvas)
        if (dimmed) canvas.restoreToCount(layer)
    }

    private fun drawPlain(canvas: Canvas) {
        val left = areaLeft()
        val right = areaRight()
        val mid = (areaTop() + areaBottom()) / 2f
        val half = 3f * d
        rect.set(left, mid - half, right, mid + half)
        canvas.drawRoundRect(rect, half, half, unplayedPaint)
        if (duration > 0L) {
            val bufferedX = left + (right - left) * (buffered.toFloat() / duration).coerceIn(0f, 1f)
            rect.set(left, mid - half, bufferedX, mid + half)
            canvas.drawRoundRect(rect, half, half, bufferedPaint)
        }
        val x = left + (right - left) * fraction()
        playedPaint.color = accent
        rect.set(left, mid - half, x, mid + half)
        canvas.drawRoundRect(rect, half, half, playedPaint)
        val radius = (if (scrubbing) 11f else 8f) * d
        canvas.drawCircle(x, mid, radius, playedPaint)
    }

    private fun drawBars(canvas: Canvas) {
        val left = areaLeft()
        val right = areaRight()
        val top = areaTop()
        val bottom = areaBottom()
        val mid = (top + bottom) / 2f
        val maxHalf = (bottom - top) / 2f
        val n = bars.size
        val slot = (right - left) / n
        val barWidth = min(BAR_DP * d, slot * 0.7f)
        val f = fraction()
        val head = f * n
        val animate = playing && !scrubbing && PiterfyMotion.enabled() && isShown
        playedPaint.color = accent
        for (i in 0 until n) {
            var h = PiterfyPeaksModel.barHeight(bars[i])
            val distance = i + 0.5f - head
            if (scrubbing) {
                h *= 1f + 0.35f * exp(-distance * distance / 6f)          // pod palcem słupki rosną
            } else if (animate) {
                val envelope = exp(-distance * distance / 18f)               // oddychają tylko przy znaczniku
                h *= 1f + 0.10f * envelope * (0.5f + 0.5f * sin(phase * TWO_PI - distance * 0.5f))
            }
            val half = (h * maxHalf).coerceAtMost(maxHalf)
            val cx = left + slot * (i + 0.5f)
            rect.set(cx - barWidth / 2f, mid - half, cx + barWidth / 2f, mid + half)
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, if ((i + 0.5f) <= head) playedPaint else unplayedPaint)
        }
        // znacznik pozycji: cienka linia z kropką u dołu
        val x = left + (right - left) * f
        val stroke = 2f * d
        rect.set(x - stroke / 2f, top - 2f * d, x + stroke / 2f, bottom + 2f * d)
        canvas.drawRoundRect(rect, stroke / 2f, stroke / 2f, playedPaint)
    }

    private fun drawBubble(canvas: Canvas) {
        val text = PiterfyPeaksModel.clock(scrubPosition)
        val w = bubbleText.measureText(text) + 18f * d
        val h = 17f * d
        val x = (areaLeft() + (areaRight() - areaLeft()) * fraction()).coerceIn(w / 2f, width - w / 2f)
        rect.set(x - w / 2f, 0f, x + w / 2f, h)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, bubblePaint)
        canvas.drawText(text, x, h / 2f - (bubbleText.ascent() + bubbleText.descent()) / 2f, bubbleText)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wanted = (bubbleZone + 30f * d + 4f * d).toInt()
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> min(wanted, MeasureSpec.getSize(heightMeasureSpec))
            else -> wanted
        }
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        barsWidth = -1
    }

    // ── oddychanie ──

    private fun updateBreathing() {
        val run = playing && peaks != null && PiterfyMotion.enabled() && isAttachedToWindow
        if (run && breathing == null) {
            breathing = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2600L
                repeatCount = ValueAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { phase = it.animatedValue as Float; if (isShown && !scrubbing) invalidate() }
                start()
            }
        } else if (!run && breathing != null) {
            breathing?.cancel(); breathing = null
            invalidate()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateBreathing() }
    override fun onDetachedFromWindow() { breathing?.cancel(); breathing = null; super.onDetachedFromWindow() }

    // ── dotyk ──

    private fun positionAt(x: Float): Long {
        val width = areaRight() - areaLeft()
        return (PiterfyPeaksModel.fractionAt(x - areaLeft(), width) * duration).toLong()
    }

    private fun startScrub(x: Float) {
        scrubbing = true
        scrubPosition = positionAt(x)
        lastDetent = PiterfyHaptics.detent(fraction(), DETENTS)
        PiterfyHaptics.gestureStart(this)
        for (l in listeners) l.onScrubStart(this, scrubPosition)
        invalidate()
    }

    private fun moveScrub(x: Float) {
        scrubPosition = positionAt(x)
        val detent = PiterfyHaptics.detent(fraction(), DETENTS)
        if (detent != lastDetent) { lastDetent = detent; haptics.tick(this) }
        for (l in listeners) l.onScrubMove(this, scrubPosition)
        invalidate()
    }

    private fun stopScrub(canceled: Boolean) {
        if (!scrubbing) return
        scrubbing = false
        if (!canceled) PiterfyHaptics.gestureEnd(this)
        for (l in listeners) l.onScrubStop(this, scrubPosition, canceled)
        if (!canceled) position = scrubPosition
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || duration <= 0L) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)   // pionowy pager i panel nie przejmują przeciągania paska
                startScrub(event.x)
            }
            MotionEvent.ACTION_MOVE -> if (scrubbing) moveScrub(event.x)
            MotionEvent.ACTION_UP -> { if (scrubbing) { moveScrub(event.x); stopScrub(false) }; parent?.requestDisallowInterceptTouchEvent(false) }
            MotionEvent.ACTION_CANCEL -> { stopScrub(true); parent?.requestDisallowInterceptTouchEvent(false) }
            else -> return true
        }
        return true
    }

    // ── klawiatura, pilot, TalkBack ──

    private fun stepMs(): Long = if (keyTimeIncrement != C.TIME_UNSET) keyTimeIncrement else if (duration > 0) max(1000L, duration / keyCountIncrement) else 0L

    /** Przewija o [delta] jako krótkie „przeciągnięcie” (te same wywołania zwrotne co dotyk, więc kontroler robi seek). */
    private fun nudge(delta: Long): Boolean {
        if (duration <= 0L || delta == 0L) return false
        val target = (position + delta).coerceIn(0L, duration)
        scrubbing = true
        scrubPosition = target
        for (l in listeners) l.onScrubStart(this, target)
        scrubbing = false
        for (l in listeners) l.onScrubStop(this, target, false)
        position = target
        invalidate()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isEnabled) when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_LEFT -> if (nudge(-stepMs())) return true
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_RIGHT -> if (nudge(stepMs())) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun description(): String = "${PiterfyPeaksModel.clock(position)} / ${PiterfyPeaksModel.clock(duration)}"

    /** Odczyt pozycji dla TalkBacka: zapowiadamy tylko zmiany co kilka sekund, żeby nie zasypywać czytnika. */
    private fun announce() {
        val second = position / 5000L
        if (second == announcedSecond) return
        announcedSecond = second
        ViewCompat.setStateDescription(this, description())
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        val compat = AccessibilityNodeInfoCompat.wrap(info)
        compat.stateDescription = description()
        if (isEnabled && duration > 0L) {
            compat.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
            compat.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD)
            compat.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SET_PROGRESS)
            compat.rangeInfo = AccessibilityNodeInfoCompat.RangeInfoCompat.obtain(
                AccessibilityNodeInfoCompat.RangeInfoCompat.RANGE_TYPE_INT, 0f, duration.toFloat(), position.toFloat())
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (super.performAccessibilityAction(action, arguments)) return true
        if (duration <= 0L || !isEnabled) return false
        return when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> nudge(stepMs()).also { announcedSecond = -1; announce() }
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> nudge(-stepMs()).also { announcedSecond = -1; announce() }
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> {
                val value = arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, -1f) ?: -1f
                if (value < 0f) false else nudge(value.toLong().coerceIn(0L, duration) - position).also { announcedSecond = -1; announce() }
            }
            else -> false
        }
    }

    private companion object {
        const val BAR_DP = 3f
        const val GAP_DP = 2f
        const val DETENTS = 20
        const val DARK_BACKGROUND = 0xFF121212.toInt()
        const val TWO_PI = (2.0 * Math.PI).toFloat()
    }
}
