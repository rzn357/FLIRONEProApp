package com.exciteddev.flironepro

import kotlin.math.abs

enum class Palette {
    IRON,
    RAINBOW,
    GRAY,
    BLACK_HOT,
    LAVA,
    ARCTIC,
}

object Palettes {
    /** 256 ARGB colors for the given palette (index 0 = coldest, 255 = hottest). */
    fun lut(p: Palette): IntArray = IntArray(256) { i ->
        val t = i / 255f
        when (p) {
            Palette.GRAY -> rgb(i, i, i)
            Palette.BLACK_HOT -> rgb(255 - i, 255 - i, 255 - i)
            Palette.IRON -> gradient(ironStops, t)
            Palette.LAVA -> gradient(lavaStops, t)
            Palette.ARCTIC -> gradient(arcticStops, t)
            Palette.RAINBOW -> rainbow(t)
        }
    }

    private val ironStops = arrayOf(
        floatArrayOf(0.00f, 0f, 0f, 0f),
        floatArrayOf(0.18f, 40f, 0f, 90f),
        floatArrayOf(0.42f, 190f, 20f, 70f),
        floatArrayOf(0.62f, 245f, 90f, 10f),
        floatArrayOf(0.82f, 255f, 190f, 30f),
        floatArrayOf(1.00f, 255f, 255f, 220f),
    )

    private val lavaStops = arrayOf(
        floatArrayOf(0.00f, 0f, 0f, 0f),
        floatArrayOf(0.25f, 120f, 0f, 0f),
        floatArrayOf(0.50f, 255f, 120f, 0f),
        floatArrayOf(0.75f, 255f, 230f, 0f),
        floatArrayOf(1.00f, 255f, 255f, 255f),
    )

    private val arcticStops = arrayOf(
        floatArrayOf(0.00f, 0f, 0f, 80f),
        floatArrayOf(0.20f, 0f, 80f, 255f),
        floatArrayOf(0.40f, 0f, 220f, 255f),
        floatArrayOf(0.55f, 255f, 255f, 255f),
        floatArrayOf(0.72f, 255f, 230f, 0f),
        floatArrayOf(0.90f, 255f, 40f, 0f),
        floatArrayOf(1.00f, 140f, 0f, 0f),
    )

    private fun gradient(stops: Array<FloatArray>, t: Float): Int {
        for (k in 1 until stops.size) {
            val a = stops[k - 1]
            val b = stops[k]
            if (t <= b[0]) {
                val f = (t - a[0]) / (b[0] - a[0])
                return rgb(
                    (a[1] + (b[1] - a[1]) * f).toInt(),
                    (a[2] + (b[2] - a[2]) * f).toInt(),
                    (a[3] + (b[3] - a[3]) * f).toInt(),
                )
            }
        }
        val last = stops.last()
        return rgb(last[1].toInt(), last[2].toInt(), last[3].toInt())
    }

    /** Blue (cold) -> red (hot). */
    private fun rainbow(t: Float): Int {
        val h = (1f - t) * 240f / 60f // 0..4
        val x = 1f - abs(h % 2f - 1f)
        val (r, g, b) = when {
            h < 1f -> Triple(1f, x, 0f)
            h < 2f -> Triple(x, 1f, 0f)
            h < 3f -> Triple(0f, 1f, x)
            else -> Triple(0f, x, 1f)
        }
        return rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
    }

    private fun rgb(r: Int, g: Int, b: Int) =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}

class ThermalStats(
    val min: Int,
    val max: Int,
    val center: Int,
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
    val mean: Int = 0,
)

/** Minimum and maximum of the smoothed thermal image (for the hottest / coldest point markers). */
class Extremes(
    val minX: Int,
    val minY: Int,
    val minValue: Int,
    val maxX: Int,
    val maxY: Int,
    val maxValue: Int,
)

object ThermalImage {
    const val W = 160
    const val H = 120

    /**
     * Extracts the 160x120 raw 16-bit values from a frame. Returns null if the thermal block size does not match
     * any known layout (then the frame has to be analyzed manually).
     *
     * Layout A: a row is 164 "pixels" (4 bytes of service header before each half of the row).
     * Layout B: a dense 160 pixels per row.
     */
    fun decode(frame: FlirFrame, out: IntArray = IntArray(W * H)): IntArray? {
        val d = frame.data
        val base = frame.thermalOffset
        when {
            frame.thermalSize >= W_STRIDE_A * H * 2 + 4 && base + W_STRIDE_A * H * 2 + 4 <= d.size -> {
                for (y in 0 until H) {
                    for (x in 0 until W) {
                        val skip = if (x < W / 2) 0 else 4
                        val o = base + 4 + 2 * (y * W_STRIDE_A + x) + skip
                        out[y * W + x] = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
                    }
                }
            }

            frame.thermalSize >= W * H * 2 && base + W * H * 2 <= d.size -> {
                for (i in 0 until W * H) {
                    val o = base + 2 * i
                    out[i] = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
                }
            }

            else -> return null
        }
        return out
    }

    fun layoutName(thermalSize: Int): String = when {
        thermalSize >= W_STRIDE_A * H * 2 + 4 -> "A(164)"
        thermalSize >= W * H * 2 -> "B(160)"
        else -> "unknown"
    }

    /** Min / max / mean / center (2x2) of the raw values. */
    fun stats(raw: IntArray): ThermalStats {
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        var minI = 0
        var maxI = 0
        var sum = 0L
        for (i in raw.indices) {
            val v = raw[i]
            sum += v
            if (v < min) { min = v; minI = i }
            if (v > max) { max = v; maxI = i }
        }
        val center = (raw[59 * W + 79] + raw[59 * W + 80] + raw[60 * W + 79] + raw[60 * W + 80]) / 4
        return ThermalStats(min, max, center, minI % W, minI / W, maxI % W, maxI / W, (sum / raw.size).toInt())
    }

    /** Linear stretch of min..max over 256 colors. Fills out (ARGB) and returns the statistics. */
    fun render(raw: IntArray, lut: IntArray, out: IntArray, stats: ThermalStats = stats(raw)): ThermalStats {
        val delta = maxOf(1, stats.max - stats.min)
        for (i in raw.indices) {
            out[i] = lut[(raw[i] - stats.min) * 255 / delta]
        }
        return stats
    }

    /** White crosshair in the center of the frame (with a gap), showing where the center measurement looks. */
    fun drawCrosshair(pixels: IntArray, color: Int = 0xFFFFFFFF.toInt()) {
        val cx = W / 2
        val cy = H / 2
        for (d in 3..7) {
            pixels[cy * W + cx - d] = color
            pixels[cy * W + cx + d - 1] = color
            pixels[(cy - d) * W + cx] = color
            pixels[(cy + d - 1) * W + cx] = color
        }
    }

    /** 3x3 mean (edges are replicated). out has size W*H. */
    fun smooth3(raw: IntArray, out: IntArray) {
        for (y in 0 until H) {
            val ya = maxOf(0, y - 1) * W
            val yb = y * W
            val yc = minOf(H - 1, y + 1) * W
            for (x in 0 until W) {
                val xa = maxOf(0, x - 1)
                val xc = minOf(W - 1, x + 1)
                out[yb + x] = (raw[ya + xa] + raw[ya + x] + raw[ya + xc] +
                    raw[yb + xa] + raw[yb + x] + raw[yb + xc] +
                    raw[yc + xa] + raw[yc + x] + raw[yc + xc]) / 9
            }
        }
    }

    /** Minimum and maximum (ignoring a 2-pixel border where artifacts are common) of an already smoothed image. */
    fun extremes(smoothed: IntArray): Extremes {
        var minV = Int.MAX_VALUE
        var maxV = Int.MIN_VALUE
        var minI = 0
        var maxI = 0
        for (y in BORDER until H - BORDER) {
            for (x in BORDER until W - BORDER) {
                val v = smoothed[y * W + x]
                if (v < minV) { minV = v; minI = y * W + x }
                if (v > maxV) { maxV = v; maxI = y * W + x }
            }
        }
        return Extremes(minI % W, minI / W, minV, maxI % W, maxI / W, maxV)
    }

    /** 3x3 mean around a pixel (u,v are normalized thermal-frame coordinates). */
    fun sample3(raw: IntArray, u: Float, v: Float): Double {
        val cx = (u * W).toInt().coerceIn(0, W - 1)
        val cy = (v * H).toInt().coerceIn(0, H - 1)
        var sum = 0
        var n = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                val x = (cx + dx).coerceIn(0, W - 1)
                val y = (cy + dy).coerceIn(0, H - 1)
                sum += raw[y * W + x]
                n++
            }
        }
        return sum.toDouble() / n
    }

    /**
     * The 240 Lepton telemetry words from the last 3 packets (240..242) of the thermal block, or null if the block is
     * shorter. Words 20..31 hold the frame counter, the frame mean, the sensor and housing temperatures, etc.
     */
    fun telemetry(frame: FlirFrame): IntArray? {
        val last = 243 * PACKET
        if (frame.thermalSize < last || frame.thermalOffset + last > frame.data.size) return null
        val out = IntArray(240)
        for (p in 0 until 3) {
            val base = frame.thermalOffset + (240 + p) * PACKET + 4
            for (i in 0 until 80) {
                val o = base + 2 * i
                out[p * 80 + i] = (frame.data[o].toInt() and 0xFF) or ((frame.data[o + 1].toInt() and 0xFF) shl 8)
            }
        }
        return out
    }

    private const val PACKET = 164
    private const val W_STRIDE_A = 164
    private const val BORDER = 2
}
