package com.exciteddev.flironepro.radiometry

import com.exciteddev.flironepro.protocol.TestFrames.buildFrame
import com.exciteddev.flironepro.protocol.TestFrames.collect
import com.exciteddev.flironepro.protocol.TestFrames.layoutAThermal
import com.exciteddev.flironepro.protocol.TestFrames.status
import com.exciteddev.flironepro.protocol.TestFrames.telemetryThermal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalImageTest {

    /** A gray ramp: index 0 is the coldest color, 255 the hottest. */
    private val lut = IntArray(256) { 0xFF000000.toInt() or (it * 0x010101) }

    @Test
    fun decodesLayoutA() {
        val frame = buildFrame(layoutAThermal(), ByteArray(10), status(false))
        val (frames, _) = collect(1000, frame)
        val raw = ThermalImage.decode(frames[0])
        assertNotNull(raw)
        for (y in 0 until 120) for (x in 0 until 160) assertEquals("($x,$y)", y * 160 + x, raw!![y * 160 + x])
        assertEquals("A(164)", ThermalImage.layoutName(frames[0].thermalSize))
    }

    @Test
    fun decodesLayoutB() {
        val t = ByteArray(160 * 120 * 2)
        for (i in 0 until 160 * 120) {
            t[2 * i] = i.toByte()
            t[2 * i + 1] = (i shr 8).toByte()
        }
        val (frames, _) = collect(1000, buildFrame(t, ByteArray(10), status(false)))
        val raw = ThermalImage.decode(frames[0])!!
        assertEquals(160 * 120 - 1, raw[160 * 120 - 1])
        assertEquals("B(160)", ThermalImage.layoutName(frames[0].thermalSize))
    }

    @Test
    fun unknownLayoutReturnsNull() {
        val (frames, _) = collect(1000, buildFrame(ByteArray(1000), ByteArray(10), status(false)))
        assertNull(ThermalImage.decode(frames[0]))
    }

    @Test
    fun imageTakesOnlyTheFirstPacketsAndDoesNotTouchTelemetry() {
        val (frames, _) = collect(4096, buildFrame(telemetryThermal(), ByteArray(10), status(false)))
        assertNotNull(ThermalImage.decode(frames[0]))
    }

    @Test
    fun renderStretchesToFullRange() {
        val raw = IntArray(160 * 120) { 1000 + it % 100 }
        raw[5] = 1000 // minimum
        raw[77] = 1099 // maximum
        val out = IntArray(raw.size)
        val s = ThermalImage.render(raw, lut, out)
        assertEquals(1000, s.min)
        assertEquals(1099, s.max)
        assertEquals(lut[0], out[s.minY * 160 + s.minX])
        assertEquals(lut[255], out[s.maxY * 160 + s.maxX])
    }

    @Test
    fun renderHandlesFlatImage() {
        val raw = IntArray(160 * 120) { 5000 }
        val out = IntArray(raw.size)
        ThermalImage.render(raw, lut, out) // must not divide by zero
        assertEquals(out[0], out[100])
    }

    @Test
    fun statsComputeMeanAndCenter() {
        val raw = IntArray(160 * 120) { 100 }
        raw[59 * 160 + 79] = 200
        raw[59 * 160 + 80] = 200
        raw[60 * 160 + 79] = 200
        raw[60 * 160 + 80] = 200
        val s = ThermalImage.stats(raw)
        assertEquals(200, s.center)
        assertEquals(100, s.min)
        assertEquals(200, s.max)
        assertEquals(100, s.mean) // (19196*100 + 4*200) / 19200 = 100.02 -> 100
    }

    @Test
    fun crosshairDrawsAroundCenterWithoutOverflow() {
        val px = IntArray(160 * 120)
        ThermalImage.drawCrosshair(px, 1)
        assertEquals(0, px[60 * 160 + 80]) // the center stays free
        assertTrue(px.count { it == 1 } == 20)
    }
}
