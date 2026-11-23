package com.exciteddev.flironepro

class ThermalStats(
    val min: Int,
    val max: Int,
    val center: Int,
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
    val mean: Int = 0
)

/** Minimum and maximum of the smoothed thermal image (for the hottest / coldest point markers). */
class Extremes(val minX: Int, val minY: Int, val minValue: Int, val maxX: Int, val maxY: Int, val maxValue: Int)

object ThermalImage {
    const val W = 160
    const val H = 120

    private const val BYTES_PER_PIXEL = LeptonPacket.BYTES_PER_PIXEL

    /** Service bytes in front of each half of a row in layout A (the Lepton packet header). */
    private const val PACKET_HEADER = LeptonPacket.HEADER

    /** Pixels per row in layout A: 160 pixels and the service words of the two halves. */
    private const val W_STRIDE_A = 164
    private const val LAYOUT_A_BYTES = W_STRIDE_A * H * BYTES_PER_PIXEL + PACKET_HEADER
    private const val LAYOUT_B_BYTES = W * H * BYTES_PER_PIXEL

    /** Pixels around the edge that are ignored when looking for the extremes (artifacts are common there). */
    private const val BORDER = 2
    private const val LUT_LAST = 255
    private const val SMOOTH_AREA = 9
    private const val CENTER_PIXELS = 4

    /** The crosshair arms start [CROSSHAIR_NEAR] and end [CROSSHAIR_FAR] pixels from the center. */
    private const val CROSSHAIR_NEAR = 3
    private const val CROSSHAIR_FAR = 7

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
        val fitsA = frame.thermalSize >= LAYOUT_A_BYTES && base + LAYOUT_A_BYTES <= d.size
        val fitsB = frame.thermalSize >= LAYOUT_B_BYTES && base + LAYOUT_B_BYTES <= d.size
        when {
            fitsA -> decodeLayoutA(d, base, out)
            fitsB -> decodeLayoutB(d, base, out)
            else -> return null
        }
        return out
    }

    private fun decodeLayoutA(d: ByteArray, base: Int, out: IntArray) {
        for (y in 0 until H) {
            for (x in 0 until W) {
                val skip = if (x < W / 2) 0 else PACKET_HEADER
                val o = base + PACKET_HEADER + BYTES_PER_PIXEL * (y * W_STRIDE_A + x) + skip
                out[y * W + x] = Bytes.u16(d, o)
            }
        }
    }

    private fun decodeLayoutB(d: ByteArray, base: Int, out: IntArray) {
        for (i in 0 until W * H) out[i] = Bytes.u16(d, base + BYTES_PER_PIXEL * i)
    }

    fun layoutName(thermalSize: Int): String = when {
        thermalSize >= LAYOUT_A_BYTES -> "A($W_STRIDE_A)"
        thermalSize >= LAYOUT_B_BYTES -> "B($W)"
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
            if (v < min) {
                min = v
                minI = i
            }
            if (v > max) {
                max = v
                maxI = i
            }
        }
        return ThermalStats(min, max, centerValue(raw), minI % W, minI / W, maxI % W, maxI / W, (sum / raw.size).toInt())
    }

    /** Mean of the 2x2 pixels around the middle of the frame. */
    private fun centerValue(raw: IntArray): Int {
        val top = (H / 2 - 1) * W + W / 2 - 1
        return (raw[top] + raw[top + 1] + raw[top + W] + raw[top + W + 1]) / CENTER_PIXELS
    }

    /** Linear stretch of min..max over 256 colors. Fills out (ARGB) and returns the statistics. */
    fun render(raw: IntArray, lut: IntArray, out: IntArray, stats: ThermalStats = stats(raw)): ThermalStats {
        val delta = maxOf(1, stats.max - stats.min)
        for (i in raw.indices) {
            out[i] = lut[(raw[i] - stats.min) * LUT_LAST / delta]
        }
        return stats
    }

    /** White crosshair in the center of the frame (with a gap), showing where the center measurement looks. */
    fun drawCrosshair(pixels: IntArray, color: Int = 0xFFFFFFFF.toInt()) {
        val cx = W / 2
        val cy = H / 2
        for (d in CROSSHAIR_NEAR..CROSSHAIR_FAR) {
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
                out[yb + x] = (
                    raw[ya + xa] + raw[ya + x] + raw[ya + xc] +
                        raw[yb + xa] + raw[yb + x] + raw[yb + xc] +
                        raw[yc + xa] + raw[yc + x] + raw[yc + xc]
                    ) / SMOOTH_AREA
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
                if (v < minV) {
                    minV = v
                    minI = y * W + x
                }
                if (v > maxV) {
                    maxV = v
                    maxI = y * W + x
                }
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
}
