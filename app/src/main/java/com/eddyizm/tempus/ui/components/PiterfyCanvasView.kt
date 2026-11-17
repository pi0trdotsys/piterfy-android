package com.eddyizm.tempus.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.eddyizm.tempus.util.CanvasLayer
import com.eddyizm.tempus.util.CanvasMontage
import com.eddyizm.tempus.util.CanvasOp
import com.eddyizm.tempus.util.PiterfyCanvasScene
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Piterfy: „Canvas” — generowana animacja pod zawartością pełnoekranowego odtwarzacza. Utwór to „teledysk”: ciąg
 * ujęć (PiterfyCanvasScene.montageFrame — te same co na stronie), każde z własnym stylem, ruchem kamery i przejściem
 * (zanikanie, irys, przesunięcie, zoom, błysk). Ujęcie rysuje prymitywy z PiterfyCanvasScene.layout: plamy,
 * pierścienie, kropki i fale w kolorach okładki.
 * Oszczędność baterii: najwyżej 30 klatek/s, pętla stoi, gdy widok jest niewidoczny, odłączony albo wstrzymany
 * (setRunning(false)), a przy trybie oszczędzania energii i wyłączonych animacjach systemu zostaje jedna nieruchoma klatka.
 */
class PiterfyCanvasView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var scene: CanvasMontage? = null
    private var previous: CanvasMontage? = null
    private var previousClock = 0.0   // chwila poprzedniego utworu, w której zamarł (znika pod nowym)
    private var songId: String? = null
    private var accent: String? = null
    private var fadeStartMs = 0L
    private var clock = 0.0          // sekundy animacji; stoi, gdy pętla stoi
    private var lastFrameMs = 0L
    private var running = false
    private var looping = false

    private val choreographer: Choreographer = Choreographer.getInstance()
    private val frameCallback = Choreographer.FrameCallback { nanos -> onFrame(nanos / 1_000_000) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val colors = HashMap<String, Int>()
    private val glows = HashMap<Int, RadialGradient>()   // kolor → plama o promieniu 1 (rysowana przez skalowanie płótna)

    /** Ustawia montaż utworu; ten sam utwór i kolor nic nie zmieniają, nowy płynnie przechodzi w poprzedni. */
    fun setScene(songId: String?, accentHex: String?) {
        if (songId == this.songId && accentHex == accent && scene != null) return
        val sameSong = songId == this.songId
        previous = if (scene != null && width > 0) scene else null
        previousClock = clock
        fadeStartMs = SystemClock.uptimeMillis()
        this.songId = songId
        accent = accentHex
        scene = if (songId == null) null else PiterfyCanvasScene.montage(songId, accentHex)
        if (!sameSong) clock = 0.0
        updateLoop()
    }

    /** Ustawia miejsce w utworze (sekundy), żeby ujęcia zgadzały się z odtwarzaniem; bez tego liczymy od początku. */
    fun setTimeOffset(seconds: Double) {
        clock = max(0.0, seconds)
        invalidate()
    }

    /** Wywoływane okresowo z pozycją odtwarzania: dopiero przewinięcie o więcej niż 1,5 s względem zegara widoku synchronizuje go. */
    fun syncPosition(seconds: Double) {
        if (abs(clock - seconds) > RESYNC_SECONDS) setTimeOffset(seconds)
    }

    /** Animuj tylko wtedy, gdy panel jest rozwinięty i muzyka gra. */
    fun setRunning(value: Boolean) {
        if (running == value) return
        running = value
        updateLoop()
    }

    private fun effectsAllowed(): Boolean {
        val powerSave = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
        val animators = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
        return !powerSave && animators
    }

    private fun updateLoop() {
        val animate = running && scene != null && isShown && isAttachedToWindow && effectsAllowed()
        if (animate && !looping) {
            looping = true
            lastFrameMs = 0L
            choreographer.postFrameCallback(frameCallback)
        } else if (!animate && looping) {
            looping = false
            choreographer.removeFrameCallback(frameCallback)
        }
        if (!animate) {
            if (previous != null) previous = null   // bez animacji przejście od razu do nowej sceny
            invalidate()
        }
    }

    private fun onFrame(nowMs: Long) {
        if (!looping) return
        choreographer.postFrameCallback(frameCallback)
        if (lastFrameMs == 0L) { lastFrameMs = nowMs; invalidate(); return }
        val gap = nowMs - lastFrameMs
        if (gap < 1000L / PiterfyCanvasScene.FPS - 4) return   // 60 Hz → co druga klatka
        clock += min(0.25, gap / 1000.0)
        lastFrameMs = nowMs
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateLoop() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); updateLoop() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); updateLoop() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); invalidate() }

    override fun onDraw(canvas: Canvas) {
        val current = scene ?: return
        val fade = if (previous == null) 1f else min(1f, (SystemClock.uptimeMillis() - fadeStartMs) / FADE_MS.toFloat())
        if (fade >= 1f) previous = null
        previous?.let { drawMontage(canvas, it, previousClock, 1f) }
        drawMontage(canvas, current, clock, fade)
        if (previous != null && !looping) invalidate()   // przejście bez pętli: dokończ je kolejnymi klatkami
    }

    private fun colorOf(hex: String) = colors.getOrPut(hex) { PiterfyCanvasScene.argb(hex) }

    private fun glowFor(color: Int) = glows.getOrPut(color) {
        RadialGradient(0f, 0f, 1f, color or (0xFF shl 24), color and 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    /** Montaż w chwili t: warstwy ujęcia (jedna, a w trakcie przejścia dwie) i ewentualny biały błysk. */
    private fun drawMontage(canvas: Canvas, m: CanvasMontage, t: Double, mix: Float) {
        val frame = PiterfyCanvasScene.montageFrame(m, t)
        for (layer in frame.layers) drawLayer(canvas, layer, t, mix)
        if (frame.flash > 0.0) {
            fill.shader = null
            fill.color = 0xFFFFFFFF.toInt()
            fill.alpha = (max(0.0, min(1.0, frame.flash)) * mix * 255).toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        }
    }

    /** Jedna warstwa: przycięcie kołem (irys), przesunięcie (slide), kamera (przesunięcie, obrót, skala), tło i prymitywy sceny. */
    private fun drawLayer(canvas: Canvas, layer: CanvasLayer, t: Double, mix: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = min(w, h)
        val m = (mix * layer.alpha).toFloat()
        if (m <= 0f) return
        val sc = layer.scene
        canvas.save()
        layer.clip?.let { c ->
            path.rewind()
            path.addCircle((c.x * w).toFloat(), (c.y * h).toFloat(), (c.r * max(w, h)).toFloat(), Path.Direction.CW)
            canvas.clipPath(path)
        }
        canvas.translate((layer.dx * w).toFloat(), (layer.dy * h).toFloat())
        val cam = layer.cam
        canvas.translate((w / 2 + cam.tx * w).toFloat(), (h / 2 + cam.ty * h).toFloat())
        canvas.rotate((cam.rot * 180.0 / Math.PI).toFloat())
        canvas.scale(cam.s.toFloat(), cam.s.toFloat())
        canvas.translate(-w / 2, -h / 2)
        fill.shader = null
        fill.color = colorOf(sc.palette.base)
        fill.alpha = (m * 255).toInt()
        canvas.drawRect(-w, -h, 2 * w, 2 * h, fill)   // tło poza ekranem, żeby kamera nigdy nie odsłaniała brzegów
        for (op in PiterfyCanvasScene.layout(sc, t)) drawOp(canvas, op, w, h, unit, m)
        canvas.restore()
    }

    private fun drawOp(canvas: Canvas, op: CanvasOp, w: Float, h: Float, unit: Float, mix: Float) {
        val alpha = (max(0.0, min(1.0, op.alpha)) * mix * 255).toInt()
        val color = colorOf(op.color)
        when (op.kind) {
            "blob", "dot" -> {
                val radius = (op.r * unit * (if (op.kind == "dot") 2.5 else 1.0)).toFloat()
                fill.shader = glowFor(color)
                fill.alpha = alpha
                // plama o promieniu 1 skalowana płótnem: shader zostaje niezmieniony (bezpieczne dla renderowania sprzętowego)
                canvas.save()
                canvas.translate((op.x * w).toFloat(), (op.y * h).toFloat())
                canvas.scale(radius, radius)
                canvas.drawCircle(0f, 0f, 1f, fill)
                canvas.restore()
                fill.shader = null
            }
            "ring" -> {
                stroke.color = (color and 0x00FFFFFF) or (alpha shl 24)
                stroke.strokeWidth = max(1f, (op.w * unit).toFloat())
                canvas.drawCircle((op.x * w).toFloat(), (op.y * h).toFloat(), (op.r * unit).toFloat(), stroke)
            }
            "wave" -> {
                val pts = op.pts ?: return
                path.rewind()
                for (n in 0 until pts.size / 2) {
                    val px = (pts[n * 2] * w).toFloat()
                    val py = (pts[n * 2 + 1] * h).toFloat()
                    if (n == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                stroke.color = (color and 0x00FFFFFF) or (alpha shl 24)
                stroke.strokeWidth = max(1f, (op.w * unit).toFloat())
                canvas.drawPath(path, stroke)
            }
        }
    }

    private companion object { const val FADE_MS = 600L; const val RESYNC_SECONDS = 1.5 }
}
