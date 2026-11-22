package com.exciteddev.flironepro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageComposerTest {
    private val W = ImageComposer.OUT_W
    private val H = ImageComposer.OUT_H

    private fun gray() = Palettes.lut(Palette.GRAY)
    private fun r(p: Int) = (p shr 16) and 0xFF

    private fun assertNear(expected: Int, actual: Int, tol: Int) =
        assertTrue("expected $expected ± $tol but was $actual", Math.abs(expected - actual) <= tol)

    @Test
    fun uniformThermalGivesUniformImage() {
        val raw = IntArray(160 * 120) { 3000 }
        val out = IntArray(W * H)
        ImageComposer.thermalToArgb(raw, 2900.0, 3100.0, gray(), out)
        assertTrue(out.all { it == out[0] })
        assertNear(127, r(out[0]), 2)
    }

    @Test
    fun horizontalGradientIsMonotonicAndSmooth() {
        val raw = IntArray(160 * 120) { 1000 + (it % 160) * 10 }
        val out = IntArray(W * H)
        ImageComposer.thermalToArgb(raw, 1000.0, 2590.0, gray(), out)
        var prev = -1
        for (x in 0 until W) {
            val v = r(out[100 * W + x])
            assertTrue("x=$x v=$v prev=$prev", v >= prev)
            assertTrue("стрибок між сусідніми пікселями має бути малим", prev < 0 || v - prev <= 3)
            prev = v
        }
        assertNear(0, r(out[100 * W]), 3)
        assertNear(255, r(out[100 * W + W - 1]), 3)
    }

    @Test
    fun valuesOutsideRangeAreClamped() {
        val raw = IntArray(160 * 120) { if (it % 2 == 0) 0 else 60000 }
        val out = IntArray(W * H)
        ImageComposer.thermalToArgb(raw, 1000.0, 2000.0, gray(), out)
        assertTrue(out.all { r(it) in 0..255 })
    }

    @Test
    fun identityAlignmentKeepsLuma() {
        val src = IntArray(W * H) { (it % W) * 255 / W }
        val out = IntArray(W * H)
        ImageComposer.alignLuma(src, W, H, Alignment(1f, 0f, 0f), out)
        for (i in intArrayOf(5 * W + 10, 240 * W + 320, 400 * W + 600)) assertNear(src[i], out[i], 2)
    }

    @Test
    fun alignmentZoomsIntoCentralPart() {
        // джерело: ліва половина чорна, права біла; тепловий кадр охоплює центральну частину
        val src = IntArray(W * H) { if (it % W < W / 2) 0 else 255 }
        val out = IntArray(W * H)
        ImageComposer.alignLuma(src, W, H, Alignment(0.5f, 0f, 0f), out)
        assertNear(0, out[240 * W + W / 2 - 40], 1)
        assertNear(255, out[240 * W + W / 2 + 40], 1)
        // біля країв кадру теж маємо відповідні сторони межі
        assertNear(0, out[240 * W + 10], 1)
        assertNear(255, out[240 * W + W - 10], 1)
    }

    @Test
    fun alignmentShiftMovesContent() {
        val src = IntArray(W * H) { if (it % W < W / 2) 0 else 255 }
        val out = IntArray(W * H)
        ImageComposer.alignLuma(src, W, H, Alignment(1f, 0.25f, 0f), out)
        // dx = +0,25: видима зона зсунута вправо, межа (0,5) тепер на u = 0,25
        assertNear(0, out[240 * W + (0.25 * W).toInt() - 20], 1)
        assertNear(255, out[240 * W + (0.25 * W).toInt() + 20], 1)
    }

    @Test
    fun highPassIsZeroOnFlatAndNonZeroAtEdge() {
        val luma = IntArray(W * H) { if (it % W < W / 2) 40 else 200 }
        val hp = IntArray(W * H)
        ImageComposer.highPass(luma, W, H, ImageComposer.Scratch(), hp)
        assertEquals(0, hp[240 * W + 50])
        assertEquals(0, hp[240 * W + W - 50])
        assertTrue(Math.abs(hp[240 * W + W / 2]) > 5 || Math.abs(hp[240 * W + W / 2 - 1]) > 5)
        // по різні боки краю знак різний (темніше / світліше)
        assertTrue(hp[240 * W + W / 2 - 2] < 0)
        assertTrue(hp[240 * W + W / 2 + 1] > 0)
    }

    @Test
    fun msxBrightensAndDarkensAroundEdges() {
        val thermal = IntArray(W * H) { 0xFF808080.toInt() }
        val hp = IntArray(W * H)
        hp[0] = 50
        hp[1] = -50
        hp[2] = 1 // у «мертвій зоні» - без змін
        ImageComposer.applyMsx(thermal, hp, 1.0f)
        assertTrue(r(thermal[0]) > 0x80)
        assertTrue(r(thermal[1]) < 0x80)
        assertEquals(0x80, r(thermal[2]))
        assertEquals(0x80, r(thermal[3]))
    }

    @Test
    fun msxClampsColorChannels() {
        val thermal = IntArray(W * H) { 0xFFFFFFFF.toInt() }
        val hp = IntArray(W * H) { 200 }
        ImageComposer.applyMsx(thermal, hp, 3f)
        assertEquals(255, r(thermal[0]))
    }

    @Test
    fun boxBlurPreservesConstantAndMean() {
        val src = IntArray(W * H) { 100 }
        val dst = IntArray(W * H)
        ImageComposer.boxBlur(src, dst, IntArray(W * H), W, H, 5)
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
        raw[0] = 0 // шум на краю кадру - не повинен стати мінімумом
        raw[60 * 160 + 100] = 3600 // «гаряча» ділянка
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
        // після згладжування 3x3 мінімум однаковий для всіх 9 сусідів піксела, тож позиція - будь-яка з них
        assertTrue(ex.minX in 29..31)
        assertTrue(ex.minY in 19..21)
    }

    @Test
    fun sample3AveragesNeighbourhood() {
        val raw = IntArray(160 * 120) { 100 }
        raw[60 * 160 + 80] = 1000
        // 9 пікселів: один 1000, вісім по 100 -> 200
        assertEquals(200.0, ThermalImage.sample3(raw, 80.5f / 160, 60.5f / 120), 0.001)
        // біля краю не виходить за межі
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
