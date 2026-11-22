package com.exciteddev.flironepro

import kotlin.math.exp
import kotlin.math.ln

/**
 * Переведення сирих значень сенсора в температуру.
 *
 * Модель (виведена на реальній камері, див. тести):
 *   Φ(T)    = R / (exp(B/T) - F)                      - функція Планка з констант заводського калібрування (R, B, F)
 *   S'      = S - S_ffc - K_FPA*(T_fpa - T_fpa_ffc)   - сире значення відносно опорної точки з поправкою на дрейф сенсора
 *   Φ_meas  = Φ(T_затвора) + (S' - зсув) / b          - «позірне» випромінювання
 *   Φ_obj   = (Φ_meas - (1-ε)*Φ(T_відбита)) / ε       - поправка на випромінювальну здатність
 *   T_obj   = B / ln(R/Φ_obj + F)
 * Опорну точку (S_ffc, T_затвора, T_fpa_ffc) дає кожне калібрування затвором (FFC): коли затвор закритий,
 * увесь кадр однаковий, а його температуру камера повідомляє сама.
 *
 * b і зсув за замовчуванням - заводські (підібрані за термопарою), користувач може їх уточнити калібруванням у застосунку.
 */
class Radiometry {
    class Anchor(val raw: Double, val shutterK: Double, val fpaK: Double)

    @Volatile
    var anchor: Anchor? = null
        private set

    /** Випромінювальна здатність об'єкта (1.0 = без поправки). */
    @Volatile var emissivity: Double = 1.0

    /** Температура навколишніх предметів, °C (для поправки на відбиття при ε < 1). */
    @Volatile var reflectedC: Double = 20.0

    /** Скільки сирих лічильників припадає на одиницю Φ. */
    @Volatile var countsPerPhi: Double = DEFAULT_COUNTS_PER_PHI

    /** Додатковий зсув сирих лічильників (результат калібрування в застосунку). */
    @Volatile var offsetCounts: Double = 0.0

    private var n = 0
    private var sumRaw = 0.0
    private var sumShutter = 0.0
    private var sumFpa = 0.0

    /** Задати опорну точку напряму (демо-режим і тести). */
    fun setAnchor(a: Anchor) {
        anchor = a
    }

    /**
     * Викликати для кожного кадру. Під час FFC збирає «плато» (рівномірний кадр закритого затвора);
     * коли FFC закінчується, зберігає опорну точку.
     */
    fun onFrame(ffc: Boolean, stats: ThermalStats, shutterK: Double?, fpaK: Double?) {
        if (ffc) {
            // Перехідні кадри (затвор рухається) мають великий розкид значень - їх пропускаємо.
            if (shutterK != null && fpaK != null && stats.max - stats.min < PLATEAU_RANGE) {
                n++
                sumRaw += stats.mean
                sumShutter += shutterK
                sumFpa += fpaK
            }
        } else {
            if (n >= MIN_PLATEAU_FRAMES) anchor = Anchor(sumRaw / n, sumShutter / n, sumFpa / n)
            n = 0
            sumRaw = 0.0
            sumShutter = 0.0
            sumFpa = 0.0
        }
    }

    /** S' (без користувацького зсуву): сире значення відносно опорної точки з поправкою на дрейф сенсора. */
    fun relative(raw: Double, fpaK: Double): Double? {
        val a = anchor ?: return null
        return raw - a.raw - FPA_DRIFT * (fpaK - a.fpaK)
    }

    /** Φ затвора в опорній точці. */
    fun shutterPhi(): Double? = anchor?.let { planck(it.shutterK) }

    fun celsius(raw: Int, fpaK: Double): Double? = celsius(raw.toDouble(), fpaK)

    /** Температура в градусах Цельсія для сирого значення, або null, якщо опорної точки ще немає чи значення поза моделлю. */
    fun celsius(raw: Double, fpaK: Double): Double? {
        val rel = relative(raw, fpaK) ?: return null
        val phiMeas = shutterPhi()!! + (rel - offsetCounts) / countsPerPhi
        val e = emissivity.coerceIn(MIN_EMISSIVITY, 1.0)
        val phiRefl = planck(reflectedC + 273.15)
        val phiObj = (phiMeas - (1.0 - e) * phiRefl) / e
        if (phiObj <= 0.0) return null
        val t = B / ln(R / phiObj + F) - 273.15
        return if (t.isNaN() || t.isInfinite()) null else t
    }

    /**
     * Точка калібрування: що показував сенсор (y = S') і що було насправді (x = Φ_meas - Φ(T_затвора)),
     * з урахуванням випромінювальної здатності та відбитої температури на момент вимірювання.
     */
    fun calibrationPoint(relative: Double, phiShutter: Double, trueC: Double, emissivity: Double, reflectedC: Double): TempCalibration.Point {
        val e = emissivity.coerceIn(MIN_EMISSIVITY, 1.0)
        val phiMeas = e * planck(trueC + 273.15) + (1.0 - e) * planck(reflectedC + 273.15)
        return TempCalibration.Point(y = relative, x = phiMeas - phiShutter, trueC = trueC)
    }

    companion object {
        /** Константи з calib.rsc (розділ extRBF, фіксована лінза leExFOL2). */
        const val R = 14331.136
        const val B = 1384.7
        const val F = 1.0

        /** Заводське значення: лічильників на одиницю Φ (підібрано за термопарою: гаряча вода 74-75 °C, мороз -12 °C, кімната 19 °C). */
        const val DEFAULT_COUNTS_PER_PHI = 22.4
        const val COUNTS_PER_PHI = DEFAULT_COUNTS_PER_PHI

        /** Дрейф сирих значень за температурою сенсора, лічильників на кельвін (оцінено з трьох FFC). */
        const val FPA_DRIFT = 100.0

        const val MIN_EMISSIVITY = 0.05

        private const val PLATEAU_RANGE = 150
        private const val MIN_PLATEAU_FRAMES = 3

        fun planck(tK: Double): Double = R / (exp(B / tK) - F)
    }
}
