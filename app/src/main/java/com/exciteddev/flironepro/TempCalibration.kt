package com.exciteddev.flironepro

/**
 * Калібрування температури користувачем: за еталонними точками (температура відома з термопари чи іншого термометра)
 * знаходить масштаб b (лічильників на одиницю Φ) і зсув (лічильників).
 *
 * Модель: y = зсув + b*x, де y = S' (сире значення відносно опорної точки), x = Φ_meas(еталон) - Φ(T_затвора).
 *  - 1 точка (або точки в дуже вузькому діапазоні температур): підганяється лише зсув, b лишається заводським;
 *  - 2+ точок із достатнім розкидом температур: лінійна регресія (b, зсув), b обмежується розумними межами.
 */
object TempCalibration {
    class Point(val y: Double, val x: Double, val trueC: Double)

    class Result(val countsPerPhi: Double, val offsetCounts: Double, val fittedSlope: Boolean)

    /** Мінімальний розкид x (в одиницях Φ, ≈ 2,2 на кельвін біля 300 K) для підгонки масштабу: ≈ 10 K. */
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

    /** Серіалізація для SharedPreferences: "y,x,t;y,x,t". */
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
