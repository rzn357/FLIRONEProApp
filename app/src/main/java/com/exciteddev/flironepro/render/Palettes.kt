package com.exciteddev.flironepro.render

import kotlin.math.abs

enum class Palette {
    IRON,
    RAINBOW,
    GRAY,
    BLACK_HOT,
    LAVA,
    ARCTIC
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
        floatArrayOf(1.00f, 255f, 255f, 220f)
    )

    private val lavaStops = arrayOf(
        floatArrayOf(0.00f, 0f, 0f, 0f),
        floatArrayOf(0.25f, 120f, 0f, 0f),
        floatArrayOf(0.50f, 255f, 120f, 0f),
        floatArrayOf(0.75f, 255f, 230f, 0f),
        floatArrayOf(1.00f, 255f, 255f, 255f)
    )

    private val arcticStops = arrayOf(
        floatArrayOf(0.00f, 0f, 0f, 80f),
        floatArrayOf(0.20f, 0f, 80f, 255f),
        floatArrayOf(0.40f, 0f, 220f, 255f),
        floatArrayOf(0.55f, 255f, 255f, 255f),
        floatArrayOf(0.72f, 255f, 230f, 0f),
        floatArrayOf(0.90f, 255f, 40f, 0f),
        floatArrayOf(1.00f, 140f, 0f, 0f)
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
                    (a[3] + (b[3] - a[3]) * f).toInt()
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
