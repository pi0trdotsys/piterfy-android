package com.eddyizm.tempus.util

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Piterfy: „Canvas” — generowana animacja do pełnoekranowego odtwarzacza. To port connect/web/canvas-core.js:
 * ta sama kolejność losowań i te same wzory, więc strona i aplikacja rysują tę samą scenę dla tego samego utworu.
 * Zgodność pilnuje PiterfyCanvasSceneTest na wzorcach z app/src/test/resources/piterfy_canvas_vectors.json
 * (generuje je tools/canvas_vectors.cjs; zmieniając rdzeń JS, popraw tutaj to samo).
 */
data class CanvasElement(val x: Double, val y: Double, val r: Double, val o: Double, val p: Double, val s: Double)

data class CanvasPalette(val base: String, val a: String, val b: String, val c: String)

data class CanvasScene(val style: String, val hash: Long, val speed: Double, val elements: List<CanvasElement>, val palette: CanvasPalette)

/** Prymityw do narysowania: kind = blob | dot | ring | wave; współrzędne 0–1, promienie i grubości względem krótszego boku. */
class CanvasOp(
    val kind: String, val x: Double = 0.0, val y: Double = 0.0, val r: Double = 0.0, val w: Double = 0.0,
    val color: String, val alpha: Double, val pts: DoubleArray? = null,
)

object PiterfyCanvasScene {
    val STYLES = listOf("aurora", "waves", "fireflies", "pulses", "mesh", "vinyl")
    const val FPS = 30
    const val FALLBACK_ACCENT = "#1db954"
    private const val TAU = PI * 2

    /** FNV-1a 32 bit po jednostkach UTF-16 (jak hash32 w JS); wynik bez znaku. */
    fun hash32(text: String?): Long {
        var h = 0x811c9dc5.toInt()
        for (ch in text.orEmpty()) { h = h xor ch.code; h *= 0x01000193 }
        return h.toLong() and 0xFFFFFFFFL
    }

    /** mulberry32 z ziarnem jako 32 bitami (jak rng w JS): kolejne wywołania dają 0 ≤ x < 1. */
    fun rng(seed: Int): () -> Double {
        var a = seed
        return {
            a += 0x6d2b79f5
            var t = a
            t = (t xor (t ushr 15)) * (t or 1)
            t = t xor (t + (t xor (t ushr 7)) * (t or 61))
            ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL) / 4294967296.0
        }
    }

    private fun clamp(v: Double, lo: Double, hi: Double) = min(hi, max(lo, v))
    private fun frac(v: Double) = v - floor(v)

    private fun parseHex(hex: String?): DoubleArray? {
        val m = Regex("^#?([0-9a-fA-F]{6})$").find(hex.orEmpty()) ?: return null
        val s = m.groupValues[1]
        return DoubleArray(3) { s.substring(it * 2, it * 2 + 2).toInt(16) / 255.0 }
    }

    private fun rgbToHsl(r: Double, g: Double, b: Double): DoubleArray {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val l = (mx + mn) / 2
        if (mx == mn) return doubleArrayOf(0.0, 0.0, l)
        val d = mx - mn
        val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> (g - b) / d + (if (g < b) 6 else 0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        }
        return doubleArrayOf(h / 6, s, l)
    }

    private fun hslToHex(h0: Double, s: Double, l: Double): String {
        val h = frac(h0)
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun channel(t0: Double): Double {
            val t = frac(t0)
            return when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 1.0 / 2 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
        }
        return "#" + listOf(channel(h + 1.0 / 3), channel(h), channel(h - 1.0 / 3))
            .joinToString("") { "%02x".format(Math.round(clamp(it, 0.0, 1.0) * 255).toInt()) }
    }

    /** Paleta z koloru okładki („#rrggbb”): ciemne tło i trzy kolory akcentów; bez koloru — zieleń Piterfy. */
    fun palette(accentHex: String?): CanvasPalette {
        val rgb = parseHex(accentHex) ?: parseHex(FALLBACK_ACCENT)!!
        val (h, s, l) = rgbToHsl(rgb[0], rgb[1], rgb[2]).toList()
        val sat = clamp(s, 0.45, 0.9)
        val light = clamp(l, 0.45, 0.62)
        return CanvasPalette(
            base = hslToHex(h, clamp(sat * 0.6, 0.25, 0.6), 0.07),
            a = hslToHex(h, sat, light),
            b = hslToHex(h + 0.11, 0.7, 0.55),
            c = hslToHex(h - 0.085, 0.65, 0.62),
        )
    }

    /** Scena utworu: styl, tempo i parametry elementów — wszystko z ID, więc ten sam utwór wygląda zawsze tak samo. */
    fun scene(songId: String?, accentHex: String?): CanvasScene {
        val hash = hash32(songId)
        val style = STYLES[(hash % STYLES.size).toInt()]
        val next = rng(hash.toInt() xor 0x9e3779b9.toInt())
        val speed = 0.7 + next() * 0.6
        val count = when (style) { "aurora" -> 5; "waves" -> 4; "fireflies" -> 22; "pulses" -> 5; "mesh" -> 6; else -> 8 }
        val elements = ArrayList<CanvasElement>(count)
        for (i in 0 until count) {
            // kolejność jak w literale obiektu w JS: x, y, r, o, p, s
            val x = 0.1 + next() * 0.8
            val y = 0.1 + next() * 0.8
            val r = next()
            val o = next()
            val p = next() * TAU
            val s = next()
            elements.add(CanvasElement(x, y, r, o, p, s))
        }
        return CanvasScene(style, hash, speed, elements, palette(accentHex))
    }

    /** Prymitywy do narysowania w chwili t (sekundy od startu), w kolejności rysowania. */
    fun layout(sc: CanvasScene, t: Double): List<CanvasOp> {
        val out = ArrayList<CanvasOp>(sc.elements.size + 4)
        val pal = sc.palette
        val tone = listOf(pal.a, pal.b, pal.c)
        val k = t * sc.speed
        fun blob(x: Double, y: Double, r: Double, c: String, a: Double) { out.add(CanvasOp("blob", x = x, y = y, r = r, color = c, alpha = a)) }
        sc.elements.forEachIndexed { i, e ->
            val c = tone[i % 3]
            when (sc.style) {
                "aurora" -> blob(
                    e.x + (0.08 + e.o * 0.14) * cos(e.p + k * (0.05 + e.s * 0.07)),
                    e.y + (0.08 + e.o * 0.14) * sin(e.p * 1.3 + k * (0.04 + e.s * 0.06)), 0.5 + e.r * 0.35, c, 0.35 + e.o * 0.2,
                )
                "mesh" -> blob(
                    e.x + 0.18 * sin(e.p + k * (0.06 + e.s * 0.05)),
                    e.y + 0.14 * sin(2 * (e.p + k * (0.06 + e.s * 0.05))), 0.7 + e.r * 0.4, c, 0.4 + e.o * 0.15,
                )
                "waves" -> {
                    val by = 0.3 + i * 0.14
                    val amp = 0.04 + e.o * 0.05
                    val freq = 1.2 + e.r * 1.2
                    val speedI = 0.25 + e.s * 0.35
                    val pts = DoubleArray(80)
                    for (n in 0 until 40) {
                        val x = n / 39.0
                        pts[n * 2] = x
                        pts[n * 2 + 1] = by + amp * sin(TAU * freq * x + e.p + k * speedI * TAU * 0.35)
                    }
                    if (i == 0) { blob(0.3, 0.35, 0.7, pal.a, 0.3); blob(0.75, 0.7, 0.6, pal.b, 0.25) }
                    out.add(CanvasOp("wave", w = 0.006, color = c, alpha = 0.55, pts = pts))
                }
                "fireflies" -> {
                    val v = 0.02 + e.s * 0.03
                    val y = 1.1 - frac(e.r + k * v) * 1.2
                    if (i == 0) { blob(0.25, 0.3, 0.6, pal.a, 0.28); blob(0.8, 0.75, 0.55, pal.c, 0.22) }
                    out.add(CanvasOp("dot", x = e.x + 0.05 * sin(e.p + k * (0.2 + e.o * 0.3)), y = y, r = 0.006 + e.o * 0.014,
                        color = c, alpha = 0.25 + 0.55 * Math.abs(sin(e.p + k * (0.4 + e.s)))))
                }
                "pulses" -> {
                    val p = frac(k * 0.07 + i / 5.0)
                    if (i == 0) blob(0.5, 0.45, 0.45, pal.a, 0.4)
                    out.add(CanvasOp("ring", x = 0.5, y = 0.45, r = 0.05 + p * 0.95, w = 0.006, color = c, alpha = (1 - p) * 0.55))
                }
                "vinyl" -> {
                    if (i == 0) {
                        blob(0.5, 0.45, 0.3, pal.a, 0.35)
                        blob(0.5 + 0.28 * cos(k * 0.12), 0.45 + 0.28 * sin(k * 0.12), 0.4, pal.b, 0.3)
                    }
                    out.add(CanvasOp("ring", x = 0.5, y = 0.45, r = 0.1 + i * 0.065, w = 0.004, color = c, alpha = 0.12 + (i % 3) * 0.07))
                }
            }
        }
        return out
    }

    /** „#rrggbb” → ARGB z pełną alfą; zły napis → czarny. */
    fun argb(hex: String): Int {
        val rgb = parseHex(hex) ?: return 0xFF000000.toInt()
        return (0xFF shl 24) or (Math.round(rgb[0] * 255).toInt() shl 16) or (Math.round(rgb[1] * 255).toInt() shl 8) or Math.round(rgb[2] * 255).toInt()
    }

    /** Kolor akcentu (ARGB) → „#rrggbb” do palette(); null dla koloru domyślnego, żeby nie wyszła czerwień z szarości. */
    fun hexOf(color: Int, ignore: Int): String? =
        if (color == ignore) null else "#%06x".format(color and 0xFFFFFF)
}
