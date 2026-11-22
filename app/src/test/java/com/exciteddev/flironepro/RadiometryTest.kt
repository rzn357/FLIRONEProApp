package com.exciteddev.flironepro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadiometryTest {

    private fun stats(min: Int, max: Int, mean: Int) = ThermalStats(min, max, mean, 0, 0, 0, 0, mean)

    /** Опорна точка, як у реальній камері: плато 3889 при затворі 305,41 K і сенсорі 305,66 K. */
    private fun radiometryWith(raw: Int, shutterK: Double, fpaK: Double): Radiometry {
        val r = Radiometry()
        repeat(5) { r.onFrame(true, stats(raw - 10, raw + 10, raw), shutterK, fpaK) }
        r.onFrame(false, stats(3000, 3500, 3200), shutterK, fpaK)
        return r
    }

    @Test
    fun noAnchorMeansNoTemperature() {
        assertNull(Radiometry().celsius(3300, 305.0))
    }

    @Test
    fun anchorIsCommittedAfterFfcEnds() {
        val r = Radiometry()
        repeat(4) { r.onFrame(true, stats(3880, 3900, 3890), 305.41, 305.66) }
        assertNull(r.anchor) // FFC ще триває
        r.onFrame(false, stats(3000, 3500, 3200), 305.41, 305.66)
        val a = r.anchor
        assertNotNull(a)
        assertEquals(3890.0, a!!.raw, 0.01)
        assertEquals(305.41, a.shutterK, 0.001)
    }

    @Test
    fun transitionalShutterFramesAreIgnored() {
        val r = Radiometry()
        // затвор рухається: великий розкид значень
        repeat(10) { r.onFrame(true, stats(1787, 4995, 3325), 305.41, 305.66) }
        r.onFrame(false, stats(3000, 3500, 3200), 305.41, 305.66)
        assertNull(r.anchor)
    }

    @Test
    fun tooFewPlateauFramesDoNotCreateAnchor() {
        val r = Radiometry()
        repeat(2) { r.onFrame(true, stats(3880, 3900, 3890), 305.41, 305.66) }
        r.onFrame(false, stats(3000, 3500, 3200), 305.41, 305.66)
        assertNull(r.anchor)
    }

    @Test
    fun shutterLevelMapsToShutterTemperature() {
        val r = radiometryWith(3889, 305.41, 305.66)
        assertEquals(305.41 - 273.15, r.celsius(3889, 305.66)!!, 0.05)
    }

    @Test
    fun temperatureIncreasesWithRawValue() {
        val r = radiometryWith(3889, 305.41, 305.66)
        var prev = -1000.0
        for (raw in 2500..7000 step 100) {
            val t = r.celsius(raw, 305.66)!!
            assertTrue("raw=$raw t=$t prev=$prev", t > prev)
            prev = t
        }
    }

    /** Регресія на реальних вимірах: гаряча вода (термопара 74-75 °C) і холодна банка з холодильника (~2-4 °C). */
    @Test
    fun reproducesHotWaterMeasurement() {
        val e2 = radiometryWith(3889, 305.41, 305.66)
        val e3 = radiometryWith(3966, 305.94, 306.18)
        val hotFromE2 = e2.celsius(6522, 306.06)!!
        val hotFromE3 = e3.celsius(6522, 306.06)!!
        assertEquals(74.5, hotFromE2, 1.5)
        assertEquals(74.5, hotFromE3, 1.5)
        // обидві опорні точки FFC мають давати майже однаковий результат (перевірка поправки на дрейф сенсора)
        assertEquals(hotFromE2, hotFromE3, 0.5)
    }

    @Test
    fun reproducesColdJarMeasurement() {
        val e2 = radiometryWith(3889, 305.41, 305.66)
        val e3 = radiometryWith(3966, 305.94, 306.18)
        val coldFromE3 = e3.celsius(2587, 306.35)!!
        val coldFromE2 = e2.celsius(2587, 306.35)!!
        assertTrue("cold=$coldFromE3", coldFromE3 in 0.0..6.0)
        assertEquals(coldFromE2, coldFromE3, 0.5)
    }

    /**
     * Перевірка 2026-11-22 (сесія 20:51-20:55): вода кімнатної температури (термопара 19-20 °C) і заморожені креветки
     * (термопара -12...-13 °C), опора FFC: плато 3845 при затворі 304,92 K і сенсорі 305,21 K.
     */
    @Test
    fun reproducesRoomWaterAndFrozenMeasurements() {
        val r = radiometryWith(3845, 304.92, 305.21)
        assertEquals(19.5, r.celsius(3268, 305.58)!!, 1.0)
        assertEquals(-12.5, r.celsius(2007, 305.51)!!, 1.0)
    }

    /**
     * Холодні точки (велике «плече» від опори затвора) жорстко задають масштаб. Масштаб, за якого гаряча вода дала б
     * об'ємні 79,5 °C, зламав би мороз більш ніж на 5 K, тож розбіжність на гарячому - фізика поверхні води, а не помилка моделі.
     */
    @Test
    fun frozenPointRejectsHigherSensitivityScale() {
        val phiShutter = Radiometry.planck(304.92)
        val dS = 2007 - 3845 - Radiometry.FPA_DRIFT * (305.51 - 305.21)
        fun coldWith(countsPerPhi: Double): Double {
            val phi = phiShutter + dS / countsPerPhi
            return Radiometry.B / kotlin.math.ln(Radiometry.R / phi + Radiometry.F) - 273.15
        }
        assertTrue(coldWith(19.7) < -18.0) // масштаб, потрібний для 79,5 °C на гарячому, дає ~-20 °C на морозі
    }

    @Test
    fun sensorDriftIsCompensated() {
        val r = radiometryWith(3889, 305.41, 305.66)
        // сенсор нагрівся на 0,5 K: те саме сире значення означає холоднішу сцену
        val same = r.celsius(3500, 305.66)!!
        val warmer = r.celsius(3500, 306.16)!!
        assertTrue(warmer < same)
    }
}
