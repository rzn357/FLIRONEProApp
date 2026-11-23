package com.exciteddev.flironepro

/**
 * In-app temperature calibration: from reference points (temperatures known from a thermocouple or another thermometer)
 * finds the scale b (counts per unit of Φ) and the offset (counts).
 *
 * Model: y = offset + b*x, where y = S' (raw value relative to the anchor), x = Φ_meas(reference) - Φ(T_shutter).
 *  - 1 point (or points within a very narrow temperature range): only the offset is fitted, b stays at the factory value;
 *  - 2+ points with a sufficient temperature spread: linear regression (b, offset), b is limited to sane bounds.
 */
object TempCalibration {
    class Point(val y: Double, val x: Double, val trueC: Double)

    class Result(val countsPerPhi: Double, val offsetCounts: Double, val fittedSlope: Boolean)

    /** Minimum spread of x (in units of Φ, ≈ 2.2 per kelvin near 300 K) for fitting the scale: ≈ 10 K. */
    const val MIN_SPAN = 20.0
    const val MIN_B = 10.0
    const val MAX_B = 60.0
    const val MAX_POINTS = 8

    fun fit(points: List<Point>, defaultB: Double = Radiometry.DEFAULT_COUNTS_PER_PHI): Result {
        if (points.isEmpty()) return Result(defaultB, 0.0, false)
        val span = points.maxOf { it.x } - points.minOf { it.x }
        if (points.size >= 2 && span >= MIN_SPAN) {
            val mx = points.map { it.x }.average()
            val my = points.map { it.y }.average()
            var sxx = 0.0
            var sxy = 0.0
            for (p in points) {
                sxx += (p.x - mx) * (p.x - mx)
                sxy += (p.x - mx) * (p.y - my)
            }
            val b = (sxy / sxx).coerceIn(MIN_B, MAX_B)
            return Result(b, my - b * mx, true)
        }
        val offset = points.map { it.y - defaultB * it.x }.average()
        return Result(defaultB, offset, false)
    }

    /** Serialization for SharedPreferences: "y,x,t;y,x,t". */
    fun serialize(points: List<Point>): String =
        points.joinToString(";") { "${it.y},${it.x},${it.trueC}" }

    fun parse(s: String?): List<Point> {
        if (s.isNullOrBlank()) return emptyList()
        return s.split(";").mapNotNull { part ->
            val v = part.split(",")
            if (v.size != 3) return@mapNotNull null
            val y = v[0].toDoubleOrNull()
            val x = v[1].toDoubleOrNull()
            val t = v[2].toDoubleOrNull()
            if (y == null || x == null || t == null) null else Point(y, x, t)
        }
    }
}
