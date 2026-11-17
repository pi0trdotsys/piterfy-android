package com.eddyizm.tempus.util

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Montaż („teledysk”) — zgodność z connect/web/canvas-core.js na wzorcach z piterfy_canvas_montage_vectors.json. */
class PiterfyCanvasMontageTest {
    private class Pal(val base: String, val a: String, val b: String, val c: String)
    private class Cam(val s0: Double, val s1: Double, val px0: Double, val py0: Double, val px1: Double, val py1: Double, val r0: Double, val r1: Double)
    private class Shot(val i: Int, val start: Double, val len: Double, val style: String, val shift: Double, val kind: String, val dir: Int,
                       val cx: Double, val cy: Double, val cam: Cam, val sceneHash: Long, val sceneSpeed: Double, val palette: Pal)
    private class FCam(val s: Double, val tx: Double, val ty: Double, val rot: Double)
    private class Clip(val x: Double, val y: Double, val r: Double)
    private class Layer(val shot: Int, val style: String, val alpha: Double, val dx: Double, val dy: Double, val clip: Clip?, val cam: FCam)
    private class Frame(val t: Double, val shot: Int, val flash: Double, val layers: List<Layer>)
    private class Vec(val id: String, val accent: String?, val shots: List<Shot>, val frames: List<Frame>)

    private val vectors: List<Vec> by lazy {
        val text = javaClass.classLoader!!.getResourceAsStream("piterfy_canvas_montage_vectors.json")!!.bufferedReader().readText()
        Gson().fromJson(text, object : TypeToken<List<Vec>>() {}.type)
    }

    @Test fun shotScheduleMatchesTheWebImplementation() {
        assertTrue(vectors.size >= 10)
        vectors.forEach { v ->
            val m = PiterfyCanvasScene.montage(v.id, v.accent)
            PiterfyCanvasScene.montageFrame(m, 301.7) // jak w generatorze: najpierw wszystkie ujęcia do tej chwili
            assertTrue(v.id, m.shots.size >= v.shots.size)
            v.shots.zip(m.shots).forEach { (want, got) ->
                val tag = "${v.id} shot${want.i}"
                assertEquals(tag, want.i, got.i)
                assertEquals(tag, want.start, got.start, 1e-9); assertEquals(tag, want.len, got.len, 1e-9)
                assertEquals(tag, want.style, got.style); assertEquals(tag, want.kind, got.kind); assertEquals(tag, want.dir, got.dir)
                assertEquals(tag, want.shift, got.shift, 1e-12)
                assertEquals(tag, want.cx, got.cx, 1e-12); assertEquals(tag, want.cy, got.cy, 1e-12)
                assertEquals(tag, want.sceneHash, got.scene.hash); assertEquals(tag, want.sceneSpeed, got.scene.speed, 1e-12)
                assertEquals(tag, listOf(want.palette.base, want.palette.a, want.palette.b, want.palette.c),
                    listOf(got.scene.palette.base, got.scene.palette.a, got.scene.palette.b, got.scene.palette.c))
                val c = want.cam; val g = got.cam
                assertEquals(tag, listOf(c.s0, c.s1, c.px0, c.py0, c.px1, c.py1, c.r0, c.r1),
                    listOf(g.s0, g.s1, g.px0, g.py0, g.px1, g.py1, g.r0, g.r1))
            }
        }
    }

    @Test fun framesMatchTheWebImplementation() {
        vectors.forEach { v ->
            val m = PiterfyCanvasScene.montage(v.id, v.accent)
            v.frames.forEach { want ->
                val got = PiterfyCanvasScene.montageFrame(m, want.t)
                val tag = "${v.id} t=${want.t}"
                assertEquals(tag, want.shot, got.shot)
                assertEquals(tag, want.flash, got.flash, 1e-6)
                assertEquals(tag, want.layers.size, got.layers.size)
                want.layers.zip(got.layers).forEachIndexed { n, (w, g) ->
                    val lt = "$tag layer$n"
                    assertEquals(lt, w.shot, g.shot); assertEquals(lt, w.style, g.scene.style)
                    assertEquals(lt, w.alpha, g.alpha, 1e-6); assertEquals(lt, w.dx, g.dx, 1e-6); assertEquals(lt, w.dy, g.dy, 1e-6)
                    assertEquals(lt, w.cam.s, g.cam.s, 1e-6); assertEquals(lt, w.cam.tx, g.cam.tx, 1e-6)
                    assertEquals(lt, w.cam.ty, g.cam.ty, 1e-6); assertEquals(lt, w.cam.rot, g.cam.rot, 1e-6)
                    val wc = w.clip
                    val gc = g.clip
                    if (wc == null) assertNull(lt, gc) else {
                        assertTrue(lt, gc != null)
                        assertEquals(lt, wc.x, gc!!.x, 1e-6); assertEquals(lt, wc.y, gc.y, 1e-6); assertEquals(lt, wc.r, gc.r, 1e-6)
                    }
                }
            }
        }
    }

    @Test fun montageIsDeterministicAndStartsWithTheBaseScene() {
        val a = PiterfyCanvasScene.montage("s86", "#1db954"); val b = PiterfyCanvasScene.montage("s86", "#1db954")
        PiterfyCanvasScene.montageFrame(a, 200.0); PiterfyCanvasScene.montageFrame(b, 200.0)
        assertEquals(a.shots.size, b.shots.size)
        a.shots.zip(b.shots).forEach { (x, y) ->
            assertEquals(x.start, y.start, 0.0); assertEquals(x.style, y.style); assertEquals(x.kind, y.kind); assertEquals(x.cam, y.cam)
        }
        val base = PiterfyCanvasScene.scene("s86", "#1db954")
        assertEquals(base.style, a.shots[0].style)
        assertEquals(base.elements, a.shots[0].scene.elements)
        assertEquals("none", a.shots[0].kind)
        assertEquals(0.0, a.shots[0].start, 0.0)
    }

    @Test fun noStyleRepeatsBackToBackAndShotsAreContiguous() {
        val m = PiterfyCanvasScene.montage("al-0f3a", "#3b6fd6")
        PiterfyCanvasScene.montageFrame(m, 400.0)
        for (i in 1 until m.shots.size) {
            assertNotEquals(m.shots[i - 1].style, m.shots[i].style)
            assertEquals(m.shots[i - 1].start + m.shots[i - 1].len, m.shots[i].start, 1e-9)
            assertTrue(m.shots[i].len >= PiterfyCanvasScene.SHOT_MIN && m.shots[i].len <= PiterfyCanvasScene.SHOT_MIN + PiterfyCanvasScene.SHOT_SPAN)
            assertTrue(PiterfyCanvasScene.KINDS.contains(m.shots[i].kind))
        }
    }

    @Test fun cameraAlwaysCoversTheFrameAndTransitionsUseTwoLayers() {
        val m = PiterfyCanvasScene.montage("s86", "#cd1916")
        var t = 0.0
        while (t < 120.0) {
            val f = PiterfyCanvasScene.montageFrame(m, t)
            f.layers.forEach { assertTrue("scale ${it.cam.s}", it.cam.s >= 1.1) ; assertTrue(Math.abs(it.cam.rot) <= 0.04 + 1e-9) }
            val shot = m.shots[f.shot]
            val inTransition = f.shot > 0 && t - shot.start < PiterfyCanvasScene.TRANS
            assertEquals("t=$t", if (inTransition) 2 else 1, f.layers.size)
            t += 0.37
        }
        // przed początkiem utworu i NaN trzymają pierwsze ujęcie
        assertEquals(0, PiterfyCanvasScene.montageFrame(m, -5.0).shot)
        assertEquals(0, PiterfyCanvasScene.montageFrame(m, Double.NaN).shot)
    }
}
