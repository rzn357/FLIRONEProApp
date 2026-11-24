package com.exciteddev.flironepro.radiometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadiometryTest {

    private fun stats(min: Int, max: Int, mean: Int) = ThermalStats(min, max, mean, 0, 0, 0, 0, mean)

    /** An anchor as on the real camera: plateau 3889 with the shutter at 305.41 K and the sensor at 305.66 K. */
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
        assertNull(r.anchor) // the FFC is still in progress
        r.onFrame(false, stats(3000, 3500, 3200), 305.41, 305.66)
        val a = r.anchor
        assertNotNull(a)
        assertEquals(3890.0, a!!.raw, 0.01)
        assertEquals(305.41, a.shutterK, 0.001)
    }

    @Test
    fun transitionalShutterFramesAreIgnored() {
        val r = Radiometry()
        // the shutter is moving: a large spread of values
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

    /** Regression on real measurements: hot water (thermocouple 74-75 °C) and a cold jar from the fridge (~2-4 °C). */
    @Test
    fun reproducesHotWaterMeasurement() {
        val e2 = radiometryWith(3889, 305.41, 305.66)
        val e3 = radiometryWith(3966, 305.94, 306.18)
        val hotFromE2 = e2.celsius(6522, 306.06)!!
        val hotFromE3 = e3.celsius(6522, 306.06)!!
        assertEquals(74.5, hotFromE2, 1.5)
        assertEquals(74.5, hotFromE3, 1.5)
        // both FFC anchors must give almost the same result (checks the sensor drift correction)
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
     * Check of 2026-11-22 (session 20:51-20:55): room-temperature water (thermocouple 19-20 °C) and frozen shrimp
     * (thermocouple -12...-13 °C), FFC anchor: plateau 3845 with the shutter at 304.92 K and the sensor at 305.21 K.
     */
    @Test
    fun reproducesRoomWaterAndFrozenMeasurements() {
        val r = radiometryWith(3845, 304.92, 305.21)
        assertEquals(19.5, r.celsius(3268, 305.58)!!, 1.0)
        assertEquals(-12.5, r.celsius(2007, 305.51)!!, 1.0)
    }

    /**
     * The cold points (a long lever arm from the shutter anchor) pin the scale down firmly. A scale for which hot water would read
     * the bulk 79.5 °C would break the freezer point by more than 5 K, so the hot-water mismatch is the physics of the water surface, not a model error.
     */
    @Test
    fun frozenPointRejectsHigherSensitivityScale() {
        val phiShutter = Radiometry.planck(304.92)
        val dS = 2007 - 3845 - Radiometry.FPA_DRIFT * (305.51 - 305.21)
        fun coldWith(countsPerPhi: Double): Double {
            val phi = phiShutter + dS / countsPerPhi
            return Radiometry.B / kotlin.math.ln(Radiometry.R / phi + Radiometry.F) - 273.15
        }
        assertTrue(coldWith(19.7) < -18.0) // the scale needed for 79.5 °C when hot gives ~-20 °C in the freezer
    }

    @Test
    fun sensorDriftIsCompensated() {
        val r = radiometryWith(3889, 305.41, 305.66)
        // the sensor warmed up by 0.5 K: the same raw value means a colder scene
        val same = r.celsius(3500, 305.66)!!
        val warmer = r.celsius(3500, 306.16)!!
        assertTrue(warmer < same)
    }

    @Test
    fun fallbackAnchorIsUsedWhileTheSensorTemperatureIsClose() {
        val earlier = radiometryWith(3889, 305.41, 305.66).anchor!!
        val r = Radiometry()
        assertNull(r.celsius(3889, 305.9))
        r.fallbackAnchor = earlier
        assertEquals(305.41 - 273.15, r.celsius(3889, 305.66)!!, 0.05)
        assertNotNull(r.celsius(3500, 306.5)) // 0.84 K away: the drift correction covers it
        assertNotNull(r.shutterPhi(306.5))
        assertNull(r.shutterPhi()) // without a sensor temperature only the real anchor counts
    }

    @Test
    fun fallbackAnchorIsNotUsedWhenTheSensorTemperatureIsFarAway() {
        val r = Radiometry()
        r.fallbackAnchor = radiometryWith(3889, 305.41, 305.66).anchor
        assertNull(r.celsius(3889, 305.66 + Radiometry.MAX_FALLBACK_FPA_GAP_K + 0.5))
        assertNull(r.effectiveAnchor(305.66 - Radiometry.MAX_FALLBACK_FPA_GAP_K - 0.5))
    }

    @Test
    fun theSessionsOwnAnchorBeatsTheFallback() {
        val own = radiometryWith(3889, 305.41, 305.66)
        own.fallbackAnchor = Radiometry.Anchor(1000.0, 290.0, 300.0)
        assertEquals(own.anchor, own.effectiveAnchor(305.66))
        assertEquals(305.41 - 273.15, own.celsius(3889, 305.66)!!, 0.05)
    }

    @Test
    fun theNewestAnchorIsRememberedForAWhile() {
        Radiometry.forgetRemembered()
        assertNull(Radiometry.recentAnchor(1_000L))
        val a = Radiometry.Anchor(3889.0, 305.41, 305.66)
        Radiometry.remember(a, 1_000L)
        assertEquals(a, Radiometry.recentAnchor(1_000L + 60_000L))
        assertNull(Radiometry.recentAnchor(1_000L + Radiometry.MAX_FALLBACK_AGE_MS + 1))
        assertNull(Radiometry.recentAnchor(500L)) // a clock that went backwards is not trusted
        Radiometry.forgetRemembered()
    }
}
