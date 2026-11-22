package com.exciteddev.flironepro

/** Режими показу. */
enum class DisplayMode(val label: String) {
    MSX("MSX (контури)"),
    THERMAL("Тепловізор"),
    VISIBLE("Звичайна камера"),
    ;

    fun next(): DisplayMode = entries[(ordinal + 1) % entries.size]
}

/**
 * Вирівнювання видимої камери відносно теплової. Нормалізовані координати теплового кадру (u,v ∈ [0,1])
 * відображаються у нормалізовані координати видимого кадру: u' = scale*(u-0.5) + 0.5 + dx.
 * scale < 1 означає, що тепловий кадр охоплює лише центральну частину видимого.
 */
class Alignment(val scale: Float = DEFAULT_SCALE, val dx: Float = DEFAULT_DX, val dy: Float = DEFAULT_DY) {
    fun thermalToVisibleU(u: Float) = scale * (u - 0.5f) + 0.5f + dx
    fun thermalToVisibleV(v: Float) = scale * (v - 0.5f) + 0.5f + dy
    fun visibleToThermalU(u: Float) = (u - 0.5f - dx) / scale + 0.5f
    fun visibleToThermalV(v: Float) = (v - 0.5f - dy) / scale + 0.5f

    fun copy(scale: Float = this.scale, dx: Float = this.dx, dy: Float = this.dy) = Alignment(scale, dx, dy)

    companion object {
        // Оцінено за збереженими кадрами (кореляція країв теплового й видимого зображень): тепловий кадр охоплює ~83 %
        // ширини видимого і зсунутий трохи вправо. Залежить від відстані (паралакс), тож у застосунку є ручне підлаштування.
        const val DEFAULT_SCALE = 0.834f
        const val DEFAULT_DX = 0.063f
        const val DEFAULT_DY = 0f
    }
}

/**
 * Геометрія показу: вміст (нормалізовані координати u,v ∈ [0,1], початок зліва-вгорі) -> дзеркало -> поворот за годинниковою
 * стрілкою -> вписування (по центру) у прямокутник [0,viewW]x[0,viewH] із відступами.
 * Той самий клас використовують і екран, і знімки/відео, тож накладені мітки завжди збігаються з картинкою.
 */
class ViewTransform(
    val viewW: Float,
    val viewH: Float,
    contentAspect: Float,
    val rotation: Int,
    val mirror: Boolean,
    margin: Float = 0f,
) {
    val destLeft: Float
    val destTop: Float
    val destW: Float
    val destH: Float

    init {
        val rotated = rotation % 180 != 0
        val aspect = if (rotated) 1f / contentAspect else contentAspect
        val availW = maxOf(1f, viewW - 2 * margin)
        val availH = maxOf(1f, viewH - 2 * margin)
        if (availW / availH > aspect) {
            destH = availH
            destW = availH * aspect
        } else {
            destW = availW
            destH = availW / aspect
        }
        destLeft = (viewW - destW) / 2f
        destTop = (viewH - destH) / 2f
    }

    /** Нормалізовані координати вмісту -> нормалізовані координати після дзеркала й повороту. */
    private fun rotate(u: Float, v: Float): Pair<Float, Float> {
        val mu = if (mirror) 1f - u else u
        return when (((rotation % 360) + 360) % 360) {
            90 -> Pair(1f - v, mu)
            180 -> Pair(1f - mu, 1f - v)
            270 -> Pair(v, 1f - mu)
            else -> Pair(mu, v)
        }
    }

    private fun unrotate(p: Float, q: Float): Pair<Float, Float> {
        val (mu, v) = when (((rotation % 360) + 360) % 360) {
            90 -> Pair(q, 1f - p)
            180 -> Pair(1f - p, 1f - q)
            270 -> Pair(1f - q, p)
            else -> Pair(p, q)
        }
        return Pair(if (mirror) 1f - mu else mu, v)
    }

    fun toViewX(u: Float, v: Float): Float = destLeft + rotate(u, v).first * destW
    fun toViewY(u: Float, v: Float): Float = destTop + rotate(u, v).second * destH

    /** Координати дотику на екрані -> нормалізовані координати вмісту (можуть виходити за [0,1]). */
    fun toContent(x: Float, y: Float): Pair<Float, Float> = unrotate((x - destLeft) / destW, (y - destTop) / destH)
}
