package com.eddyizm.tempus.util

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PiterfyCanvasSceneTest {
    // wzorce z connect/web/canvas-core.js (tools/canvas_vectors.cjs); pola opcjonalne zostają 0 / null
    private class Pal(val base: String, val a: String, val b: String, val c: String)
    private class El(val x: Double, val y: Double, val r: Double, val o: Double, val p: Double, val s: Double)
    private class Op(val k: String, val x: Double, val y: Double, val r: Double, val w: Double, val c: String, val a: Double, val pts: List<Double>?)
    private class Frame(val t: Double, val ops: List<Op>)
    private class Vec(val id: String, val accent: String?, val hash: Long, val style: String, val speed: Double,
                      val palette: Pal, val elements: List<El>, val frames: List<Frame>)

    private val vectors: List<Vec> by lazy {
        val text = javaClass.classLoader!!.getResourceAsStream("piterfy_canvas_vectors.json")!!.bufferedReader().readText()
        Gson().fromJson(text, object : TypeToken<List<Vec>>() {}.type)
    }

    @Test fun vectorsCoverEveryStyle() {
        assertTrue(vectors.size >= 10)
        assertEquals(PiterfyCanvasScene.STYLES.toSet(), vectors.map { it.style }.toSet())
    }

    @Test fun hashMatchesTheWebImplementation() {
        assertEquals(0x811c9dc5L, PiterfyCanvasScene.hash32(""))
        assertEquals(0xe40c292cL, PiterfyCanvasScene.hash32("a"))
        vectors.forEach { assertEquals(it.id, it.hash, PiterfyCanvasScene.hash32(it.id)) }
    }

    @Test fun sceneAndPaletteMatchTheWebImplementation() {
        vectors.forEach { v ->
            val sc = PiterfyCanvasScene.scene(v.id, v.accent)
            assertEquals(v.id, v.style, sc.style)
            assertEquals(v.id, v.hash, sc.hash)
            assertEquals(v.id, v.speed, sc.speed, 1e-12)
            assertEquals(v.id, listOf(v.palette.base, v.palette.a, v.palette.b, v.palette.c),
                listOf(sc.palette.base, sc.palette.a, sc.palette.b, sc.palette.c))
            assertEquals(v.id, v.elements.size, sc.elements.size)
            v.elements.zip(sc.elements).forEachIndexed { i, (want, got) ->
                val tag = "${v.id}[$i]"
                assertEquals(tag, want.x, got.x, 1e-12); assertEquals(tag, want.y, got.y, 1e-12); assertEquals(tag, want.r, got.r, 1e-12)
                assertEquals(tag, want.o, got.o, 1e-12); assertEquals(tag, want.p, got.p, 1e-12); assertEquals(tag, want.s, got.s, 1e-12)
            }
        }
    }

    @Test fun layoutMatchesTheWebImplementationOverTime() {
        vectors.forEach { v ->
            val sc = PiterfyCanvasScene.scene(v.id, v.accent)
            v.frames.forEach { f ->
                val ops = PiterfyCanvasScene.layout(sc, f.t)
                assertEquals("${v.id} t=${f.t}", f.ops.size, ops.size)
                f.ops.zip(ops).forEachIndexed { i, (want, got) ->
                    val tag = "${v.id} t=${f.t} op$i"
                    assertEquals(tag, want.k, got.kind); assertEquals(tag, want.c, got.color)
                    assertEquals(tag, want.a, got.alpha, 1e-6)
                    if (want.k == "wave") {
                        assertEquals(tag, want.w, got.w, 1e-9)
                        val pts = got.pts!!
                        assertEquals(tag, want.pts!!.size, pts.size)
                        want.pts.forEachIndexed { n, value -> assertEquals("$tag pt$n", value, pts[n], 1e-6) }
                    } else {
                        assertEquals(tag, want.x, got.x, 1e-6); assertEquals(tag, want.y, got.y, 1e-6); assertEquals(tag, want.r, got.r, 1e-6)
                        if (want.k == "ring") assertEquals(tag, want.w, got.w, 1e-9)
                    }
                }
            }
        }
    }

    @Test fun sceneIsDeterministicAndEveryStyleOccurs() {
        val a = PiterfyCanvasScene.scene("s86", "#1db954")
        assertEquals(a, PiterfyCanvasScene.scene("s86", "#1db954"))
        assertNotEquals(a.hash, PiterfyCanvasScene.scene("s87", "#1db954").hash)
        val seen = (0 until 300).map { PiterfyCanvasScene.scene("song-$it", null).style }.toSet()
        assertEquals(PiterfyCanvasScene.STYLES.toSet(), seen)
    }

    @Test fun layoutIsBoundedAndHasNoHiddenState() {
        val styles = HashMap<String, CanvasScene>()
        var i = 0
        while (styles.size < PiterfyCanvasScene.STYLES.size && i < 1000) { val sc = PiterfyCanvasScene.scene("id$i", "#3b6fd6"); styles.putIfAbsent(sc.style, sc); i++ }
        assertEquals(PiterfyCanvasScene.STYLES.size, styles.size)
        styles.forEach { (style, sc) ->
            listOf(0.0, 1.7, 33.0, 600.0, 36000.0).forEach { t ->
                val ops = PiterfyCanvasScene.layout(sc, t)
                assertTrue(style, ops.isNotEmpty())
                ops.forEach { op ->
                    assertTrue("$style alpha ${op.alpha}", op.alpha in 0.0..1.0)
                    assertTrue(style, Regex("^#[0-9a-f]{6}$").matches(op.color))
                    if (op.kind == "wave") assertEquals(80, op.pts!!.size) else assertTrue("$style ${op.kind}", op.r > 0 && op.x.isFinite() && op.y.isFinite())
                }
            }
            assertEquals(PiterfyCanvasScene.layout(sc, 5.0).map { it.alpha }, PiterfyCanvasScene.layout(sc, 5.0).map { it.alpha })
        }
    }

    @Test fun paletteFallsBackToPiterfyGreenAndKeepsTheBackgroundDark() {
        assertEquals(PiterfyCanvasScene.palette(PiterfyCanvasScene.FALLBACK_ACCENT), PiterfyCanvasScene.palette("not-a-color"))
        assertEquals(PiterfyCanvasScene.palette("#1db954"), PiterfyCanvasScene.palette(null))
        fun lum(hex: String) = 0.299 * hex.substring(1, 3).toInt(16) + 0.587 * hex.substring(3, 5).toInt(16) + 0.114 * hex.substring(5, 7).toInt(16)
        assertTrue(lum(PiterfyCanvasScene.palette("#cd1916").base) < 40)
        assertTrue(lum(PiterfyCanvasScene.palette("#101040").a) > 50)
    }

    @Test fun defaultAmbientDoesNotTurnIntoRed() {
        assertEquals(null, PiterfyCanvasScene.hexOf(0xFF2A2A2A.toInt(), 0xFF2A2A2A.toInt()))
        assertEquals("#3b6fd6", PiterfyCanvasScene.hexOf(0xFF3B6FD6.toInt(), 0xFF2A2A2A.toInt()))
        assertEquals(0xFF3B6FD6.toInt(), PiterfyCanvasScene.argb("#3b6fd6"))
    }
}
