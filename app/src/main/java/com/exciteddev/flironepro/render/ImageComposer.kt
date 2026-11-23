package com.exciteddev.flironepro.render

import com.exciteddev.flironepro.radiometry.ThermalImage
import kotlin.math.abs
import kotlin.math.floor

/**
 * Composes the displayed picture: the colorized, smoothly enlarged thermal image and the overlay of the visible camera's
 * edges (MSX). Works on arrays with no Android dependency, so it can be unit-tested on a computer.
 */
object ImageComposer {
    const val OUT_W = 640
    const val OUT_H = 480

    private const val SRC_W = ThermalImage.W
    private const val SRC_H = ThermalImage.H

    // Bilinear interpolation tables 160x120 -> 640x480.
    private val colX0 = IntArray(OUT_W)
    private val colX1 = IntArray(OUT_W)
    private val colFx = FloatArray(OUT_W)
    private val rowY0 = IntArray(OUT_H)
    private val rowY1 = IntArray(OUT_H)
    private val rowFy = FloatArray(OUT_H)

    init {
        for (ox in 0 until OUT_W) {
            val s = (ox + 0.5f) * SRC_W / OUT_W - 0.5f
            var x0 = floor(s).toInt()
            var f = s - x0
            if (x0 < 0) {
                x0 = 0
                f = 0f
            }
            colX0[ox] = x0
            colX1[ox] = minOf(x0 + 1, SRC_W - 1)
            colFx[ox] = f
        }
        for (oy in 0 until OUT_H) {
            val s = (oy + 0.5f) * SRC_H / OUT_H - 0.5f
            var y0 = floor(s).toInt()
            var f = s - y0
            if (y0 < 0) {
                y0 = 0
                f = 0f
            }
            rowY0[oy] = y0
            rowY1[oy] = minOf(y0 + 1, SRC_H - 1)
            rowFy[oy] = f
        }
    }

    /** Work buffers, so memory is not allocated for every frame. */
    class Scratch {
        val luma = IntArray(OUT_W * OUT_H)
        val tmp = IntArray(OUT_W * OUT_H)
        val small = IntArray(OUT_W * OUT_H)
        val large = IntArray(OUT_W * OUT_H)
        val hp = IntArray(OUT_W * OUT_H)
    }

    /** Thermal 160x120 (raw values) -> 640x480 ARGB with the palette over the range lo..hi, bilinearly smoothed. */
    fun thermalToArgb(raw: IntArray, lo: Double, hi: Double, lut: IntArray, out: IntArray) {
        val scale = 255.0 / maxOf(1.0, hi - lo)
        val row = FloatArray(SRC_W)
        for (oy in 0 until OUT_H) {
            val y0 = rowY0[oy] * SRC_W
            val y1 = rowY1[oy] * SRC_W
            val fy = rowFy[oy]
            for (x in 0 until SRC_W) row[x] = raw[y0 + x] * (1f - fy) + raw[y1 + x] * fy
            var o = oy * OUT_W
            for (ox in 0 until OUT_W) {
                val fx = colFx[ox]
                val v = row[colX0[ox]] * (1f - fx) + row[colX1[ox]] * fx
                var idx = ((v - lo) * scale).toInt()
                if (idx < 0) {
                    idx = 0
                } else if (idx > 255) {
                    idx = 255
                }
                out[o++] = lut[idx]
            }
        }
    }

    /** Luma (0..255) from ARGB pixels. */
    fun lumaFromArgb(argb: IntArray, count: Int, out: IntArray) {
        for (i in 0 until count) {
            val p = argb[i]
            out[i] = (77 * ((p shr 16) and 0xFF) + 150 * ((p shr 8) and 0xFF) + 29 * (p and 0xFF)) shr 8
        }
    }

    /**
     * Luma of the visible frame srcW x srcH -> the thermal frame grid OUT_W x OUT_H according to the alignment (bilinear).
     * Coordinates outside the visible frame are clamped to the edge.
     */
    fun alignLuma(src: IntArray, srcW: Int, srcH: Int, a: Alignment, out: IntArray) {
        val sx0 = IntArray(OUT_W)
        val sx1 = IntArray(OUT_W)
        val sfx = FloatArray(OUT_W)
        for (ox in 0 until OUT_W) {
            val s = a.thermalToVisibleU((ox + 0.5f) / OUT_W) * srcW - 0.5f
            val c = s.coerceIn(0f, srcW - 1.001f)
            val i = c.toInt()
            sx0[ox] = i
            sx1[ox] = i + 1
            sfx[ox] = c - i
        }
        for (oy in 0 until OUT_H) {
            val s = a.thermalToVisibleV((oy + 0.5f) / OUT_H) * srcH - 0.5f
            val c = s.coerceIn(0f, srcH - 1.001f)
            val y0 = c.toInt()
            val fy = c - y0
            val r0 = y0 * srcW
            val r1 = (y0 + 1) * srcW
            var o = oy * OUT_W
            for (ox in 0 until OUT_W) {
                val fx = sfx[ox]
                val a0 = src[r0 + sx0[ox]] * (1f - fx) + src[r0 + sx1[ox]] * fx
                val a1 = src[r1 + sx0[ox]] * (1f - fx) + src[r1 + sx1[ox]] * fx
                out[o++] = (a0 * (1f - fy) + a1 * fy).toInt()
            }
        }
    }

    /** Averaging over (2r+1)x(2r+1) with edge replication; separable, hence fast. */
    fun boxBlur(src: IntArray, dst: IntArray, tmp: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val base = y * w
            var sum = 0
            for (i in -r..r) sum += src[base + i.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[base + x] = sum / div
                sum += src[base + minOf(x + r + 1, w - 1)] - src[base + maxOf(x - r, 0)]
            }
        }
        for (x in 0 until w) {
            var sum = 0
            for (i in -r..r) sum += tmp[i.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = sum / div
                sum += tmp[minOf(y + r + 1, h - 1) * w + x] - tmp[maxOf(y - r, 0) * w + x]
            }
        }
    }

    /** High-frequency component (edges): m(r=1) - m(r=5). */
    fun highPass(luma: IntArray, w: Int, h: Int, s: Scratch, out: IntArray) {
        boxBlur(luma, s.small, s.tmp, w, h, 1)
        boxBlur(luma, s.large, s.tmp, w, h, 5)
        for (i in 0 until w * h) out[i] = s.small[i] - s.large[i]
    }

    /**
     * MSX: adds the edges to the colorized thermal image (in place). gain is the overlay strength,
     * small noise (|hp| <= DEADZONE) is discarded.
     */
    fun applyMsx(thermal: IntArray, hp: IntArray, gain: Float) {
        for (i in thermal.indices) {
            var d = hp[i]
            d = if (d > DEADZONE) {
                d - DEADZONE
            } else if (d < -DEADZONE) {
                d + DEADZONE
            } else {
                0
            }
            if (d == 0) continue
            val add = (d * gain).toInt()
            val p = thermal[i]
            val r = (((p shr 16) and 0xFF) + add).coerceIn(0, 255)
            val g = (((p shr 8) and 0xFF) + add).coerceIn(0, 255)
            val b = ((p and 0xFF) + add).coerceIn(0, 255)
            thermal[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** Full MSX composition: thermal + edges from the visible luma srcW x srcH. */
    fun msx(thermalArgb: IntArray, visibleLuma: IntArray, srcW: Int, srcH: Int, a: Alignment, gain: Float, s: Scratch) {
        alignLuma(visibleLuma, srcW, srcH, a, s.luma)
        highPass(s.luma, OUT_W, OUT_H, s, s.hp)
        applyMsx(thermalArgb, s.hp, gain)
    }

    /** Largest absolute value of the edge component (for tests / debugging). */
    fun maxAbs(a: IntArray): Int = a.maxOf { abs(it) }

    private const val DEADZONE = 2
}
