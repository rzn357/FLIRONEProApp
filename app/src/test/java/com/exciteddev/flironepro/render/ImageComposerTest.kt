package com.exciteddev.flironepro.render

import com.exciteddev.flironepro.radiometry.ThermalImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageComposerTest {
    private val w = ImageComposer.OUT_W
    private val h = ImageComposer.OUT_H

    private fun gray() = Palettes.lut(Palette.GRAY)
    private fun r(p: Int) = (p shr 16) and 0xFF

    private fun assertNear(expected: Int, actual: Int, tol: Int) =
        assertTrue("expected $expected ± $tol but was $actual", Math.abs(expected - actual) <= tol)

    @Test
    fun uniformThermalGivesUniformImage() {
        val raw = IntArray(160 * 120) { 3000 }
        val out = IntArray(w * h)
        ImageComposer.thermalToArgb(raw, 2900.0, 3100.0, gray(), out)
        assertTrue(out.all { it == out[0] })
        assertNear(127, r(out[0]), 2)
    }

    @Test
    fun horizontalGradientIsMonotonicAndSmooth() {
        val raw = IntArray(160 * 120) { 1000 + (it % 160) * 10 }
        val out = IntArray(w * h)
        ImageComposer.thermalToArgb(raw, 1000.0, 2590.0, gray(), out)
        var prev = -1
        for (x in 0 until w) {
            val v = r(out[100 * w + x])
            assertTrue("x=$x v=$v prev=$prev", v >= prev)
            assertTrue("the jump between neighbouring pixels must be small", prev < 0 || v - prev <= 3)
            prev = v
        }
        assertNear(0, r(out[100 * w]), 3)
        assertNear(255, r(out[100 * w + w - 1]), 3)
    }

    @Test
    fun valuesOutsideRangeAreClamped() {
        val raw = IntArray(160 * 120) { if (it % 2 == 0) 0 else 60000 }
        val out = IntArray(w * h)
        ImageComposer.thermalToArgb(raw, 1000.0, 2000.0, gray(), out)
        assertTrue(out.all { r(it) in 0..255 })
    }

    @Test
    fun identityAlignmentKeepsLuma() {
        val src = IntArray(w * h) { (it % w) * 255 / w }
        val out = IntArray(w * h)
        ImageComposer.alignLuma(src, w, h, Alignment(1f, 0f, 0f), out)
        for (i in intArrayOf(5 * w + 10, 240 * w + 320, 400 * w + 600)) assertNear(src[i], out[i], 2)
    }

    @Test
    fun alignmentZoomsIntoCentralPart() {
        // source: the left half is black, the right half is white; the thermal frame covers the central part
        val src = IntArray(w * h) { if (it % w < w / 2) 0 else 255 }
        val out = IntArray(w * h)
        ImageComposer.alignLuma(src, w, h, Alignment(0.5f, 0f, 0f), out)
        assertNear(0, out[240 * w + w / 2 - 40], 1)
        assertNear(255, out[240 * w + w / 2 + 40], 1)
        // near the frame edges we still get the matching sides of the boundary
        assertNear(0, out[240 * w + 10], 1)
        assertNear(255, out[240 * w + w - 10], 1)
    }

    @Test
    fun alignmentShiftMovesContent() {
        val src = IntArray(w * h) { if (it % w < w / 2) 0 else 255 }
        val out = IntArray(w * h)
        ImageComposer.alignLuma(src, w, h, Alignment(1f, 0.25f, 0f), out)
        // dx = +0.25: the visible zone is shifted to the right, the boundary (0.5) is now at u = 0.25
        assertNear(0, out[240 * w + (0.25 * w).toInt() - 20], 1)
        assertNear(255, out[240 * w + (0.25 * w).toInt() + 20], 1)
    }

    @Test
    fun highPassIsZeroOnFlatAndNonZeroAtEdge() {
        val luma = IntArray(w * h) { if (it % w < w / 2) 40 else 200 }
        val hp = IntArray(w * h)
        ImageComposer.highPass(luma, w, h, ImageComposer.Scratch(), hp)
        assertEquals(0, hp[240 * w + 50])
        assertEquals(0, hp[240 * w + w - 50])
        assertTrue(Math.abs(hp[240 * w + w / 2]) > 5 || Math.abs(hp[240 * w + w / 2 - 1]) > 5)
        // the sign differs on the two sides of the edge (darker / lighter)
        assertTrue(hp[240 * w + w / 2 - 2] < 0)
        assertTrue(hp[240 * w + w / 2 + 1] > 0)
    }

    @Test
    fun msxBrightensAndDarkensAroundEdges() {
        val thermal = IntArray(w * h) { 0xFF808080.toInt() }
        val hp = IntArray(w * h)
        hp[0] = 50
        hp[1] = -50
        hp[2] = 1 // inside the dead zone - unchanged
        ImageComposer.applyMsx(thermal, hp, 1.0f)
        assertTrue(r(thermal[0]) > 0x80)
        assertTrue(r(thermal[1]) < 0x80)
        assertEquals(0x80, r(thermal[2]))
        assertEquals(0x80, r(thermal[3]))
    }

    @Test
    fun msxClampsColorChannels() {
        val thermal = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        val hp = IntArray(w * h) { 200 }
        ImageComposer.applyMsx(thermal, hp, 3f)
        assertEquals(255, r(thermal[0]))
    }

    @Test
    fun boxBlurPreservesConstantAndMean() {
        val src = IntArray(w * h) { 100 }
        val dst = IntArray(w * h)
        ImageComposer.boxBlur(src, dst, IntArray(w * h), w, h, 5)
        assertTrue(dst.all { it == 100 })
    }

    @Test
    fun lumaFromArgbUsesWeights() {
        val argb = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFF0000.toInt())
        val out = IntArray(3)
        ImageComposer.lumaFromArgb(argb, 3, out)
        assertNear(255, out[0], 1)
        assertEquals(0, out[1])
        assertTrue(out[2] in 60..90)
    }

    @Test
    fun smoothAndExtremesIgnoreBorderAndNoise() {
        val raw = IntArray(160 * 120) { 3000 }
        raw[0] = 0 // noise at the frame edge - must not become the minimum
        raw[60 * 160 + 100] = 3600 // a "hot" area
        raw[59 * 160 + 100] = 3600
        raw[61 * 160 + 100] = 3600
        raw[60 * 160 + 99] = 3600
        raw[60 * 160 + 101] = 3600
        raw[20 * 160 + 30] = 2400
        val s = IntArray(160 * 120)
        ThermalImage.smooth3(raw, s)
        val ex = ThermalImage.extremes(s)
        assertEquals(100, ex.maxX)
        assertEquals(60, ex.maxY)
        assertTrue(ex.minValue >= 2400 && ex.minValue < 3000)
        // after the 3x3 smoothing the minimum is the same for all 9 neighbours of the pixel, so the position is any of them
        assertTrue(ex.minX in 29..31)
        assertTrue(ex.minY in 19..21)
    }

    @Test
    fun sample3AveragesNeighbourhood() {
        val raw = IntArray(160 * 120) { 100 }
        raw[60 * 160 + 80] = 1000
        // 9 pixels: one 1000, eight 100 -> 200
        assertEquals(200.0, ThermalImage.sample3(raw, 80.5f / 160, 60.5f / 120), 0.001)
        // near the edge it does not go out of bounds
        assertEquals(100.0, ThermalImage.sample3(raw, 0f, 0f), 0.001)
        assertEquals(100.0, ThermalImage.sample3(raw, 1f, 1f), 0.001)
    }

    @Test
    fun allPalettesHaveDistinctEndsAndSixOfThem() {
        assertEquals(6, Palette.entries.size)
        for (p in Palette.entries) {
            val l = Palettes.lut(p)
            assertTrue("$p", l[0] != l[255])
        }
    }
}
