package com.exciteddev.flironepro.radiometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TempCalibrationTest {

    private fun stats(min: Int, max: Int, mean: Int) = ThermalStats(min, max, mean, 0, 0, 0, 0, mean)

    private fun radiometry(): Radiometry {
        val r = Radiometry()
        repeat(5) { r.onFrame(true, stats(3880, 3900, 3890), 305.0, 305.3) }
        r.onFrame(false, stats(3000, 3500, 3200), 305.0, 305.3)
        return r
    }

    /** The raw value a "true" camera with parameters (b, offset) would give for the temperature trueC. */
    private fun simulatedRaw(r: Radiometry, trueC: Double, b: Double, offset: Double): Double {
        val a = r.anchor!!
        val phi = Radiometry.planck(trueC + 273.15)
        return a.raw + offset + b * (phi - Radiometry.planck(a.shutterK))
    }

    @Test
    fun noPointsGivesFactoryValues() {
        val f = TempCalibration.fit(emptyList())
        assertEquals(Radiometry.DEFAULT_COUNTS_PER_PHI, f.countsPerPhi, 1e-9)
        assertEquals(0.0, f.offsetCounts, 1e-9)
        assertFalse(f.fittedSlope)
    }

    @Test
    fun singlePointFixesOffsetAndReproducesReference() {
        val r = radiometry()
        // The "true" camera has an offset of +60 counts relative to the factory model.
        val raw = simulatedRaw(r, 40.0, Radiometry.DEFAULT_COUNTS_PER_PHI, 60.0)
        assertTrue(r.celsius(raw, 305.3)!! != 40.0)
        val pt = r.calibrationPoint(r.relative(raw, 305.3)!!, r.shutterPhi()!!, 40.0, 1.0, 20.0)
        val fit = TempCalibration.fit(listOf(pt))
        r.countsPerPhi = fit.countsPerPhi
        r.offsetCounts = fit.offsetCounts
        assertFalse(fit.fittedSlope)
        assertEquals(40.0, r.celsius(raw, 305.3)!!, 0.05)
    }

    @Test
    fun twoWidelySeparatedPointsFitSlopeAndOffset() {
        val r = radiometry()
        val bTrue = 26.0
        val offTrue = 35.0
        val pts = listOf(0.0, 60.0).map { t ->
            val raw = simulatedRaw(r, t, bTrue, offTrue)
            r.calibrationPoint(r.relative(raw, 305.3)!!, r.shutterPhi()!!, t, 1.0, 20.0)
        }
        val fit = TempCalibration.fit(pts)
        assertTrue(fit.fittedSlope)
        assertEquals(bTrue, fit.countsPerPhi, 0.01)
        assertEquals(offTrue, fit.offsetCounts, 0.5)
        r.countsPerPhi = fit.countsPerPhi
        r.offsetCounts = fit.offsetCounts
        // a third temperature that was not among the references
        val raw = simulatedRaw(r, 25.0, bTrue, offTrue)
        assertEquals(25.0, r.celsius(raw, 305.3)!!, 0.1)
    }

    @Test
    fun closeTemperaturesDoNotFitSlope() {
        val r = radiometry()
        val pts = listOf(20.0, 22.0).map { t ->
            val raw = simulatedRaw(r, t, 22.4, 10.0)
            r.calibrationPoint(r.relative(raw, 305.3)!!, r.shutterPhi()!!, t, 1.0, 20.0)
        }
        val fit = TempCalibration.fit(pts)
        assertFalse(fit.fittedSlope)
        assertEquals(Radiometry.DEFAULT_COUNTS_PER_PHI, fit.countsPerPhi, 1e-9)
    }

    @Test
    fun slopeIsClampedToSaneRange() {
        val pts = listOf(
            TempCalibration.Point(y = 0.0, x = 0.0, trueC = 0.0),
            TempCalibration.Point(y = 100000.0, x = 100.0, trueC = 50.0)
        )
        assertEquals(TempCalibration.MAX_B, TempCalibration.fit(pts).countsPerPhi, 1e-9)
    }

    @Test
    fun serializeRoundTrip() {
        val pts = listOf(TempCalibration.Point(12.5, -3.25, 18.0), TempCalibration.Point(-400.0, 60.5, 74.5))
        val back = TempCalibration.parse(TempCalibration.serialize(pts))
        assertEquals(2, back.size)
        assertEquals(-3.25, back[0].x, 1e-12)
        assertEquals(74.5, back[1].trueC, 1e-12)
        assertTrue(TempCalibration.parse("").isEmpty())
        assertTrue(TempCalibration.parse("garbage;1,2").isEmpty())
    }

    @Test
    fun emissivityCorrectionRoundTrips() {
        val r = radiometry()
        r.emissivity = 0.9
        r.reflectedC = 20.0
        // An object at 60 °C with ε=0.9: the sensor sees a mix of its own radiation and the reflected one.
        val a = r.anchor!!
        val phiMeas = 0.9 * Radiometry.planck(333.15) + 0.1 * Radiometry.planck(293.15)
        val raw = a.raw + Radiometry.DEFAULT_COUNTS_PER_PHI * (phiMeas - Radiometry.planck(a.shutterK))
        assertEquals(60.0, r.celsius(raw, 305.3)!!, 0.05)
        // and without the correction (ε=1) the same raw value would read lower
        r.emissivity = 1.0
        assertTrue(r.celsius(raw, 305.3)!! < 59.0)
    }

    @Test
    fun calibrationPointUsesEmissivity() {
        val r = radiometry()
        val p1 = r.calibrationPoint(100.0, r.shutterPhi()!!, 60.0, 1.0, 20.0)
        val p2 = r.calibrationPoint(100.0, r.shutterPhi()!!, 60.0, 0.9, 20.0)
        assertTrue(p2.x < p1.x) // at ε<1 a real object radiates less than a black body of the same temperature
    }
}
