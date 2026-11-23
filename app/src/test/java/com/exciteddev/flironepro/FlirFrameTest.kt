package com.exciteddev.flironepro

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlirFrameTest {

    private fun u32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    /** Builds a frame in the camera's format from the given blocks. */
    private fun buildFrame(thermal: ByteArray, jpg: ByteArray, status: ByteArray): ByteArray {
        val frameSize = thermal.size + jpg.size + status.size
        val header = ByteArray(28)
        header[0] = 0xEF.toByte()
        header[1] = 0xBE.toByte()
        u32(frameSize).copyInto(header, 8)
        u32(thermal.size).copyInto(header, 12)
        u32(jpg.size).copyInto(header, 16)
        u32(status.size).copyInto(header, 20)
        return header + thermal + jpg + status
    }

    /** Thermal data of layout A: a row is 164 pixels, 4 service bytes at the start and 4 more in the middle. Pixel (x,y) = y*160+x. */
    private fun layoutAThermal(): ByteArray {
        val t = ByteArray(4 + 120 * 164 * 2)
        for (y in 0 until 120) {
            for (x in 0 until 160) {
                val v = y * 160 + x
                val o = 4 + 2 * (y * 164 + x) + (if (x < 80) 0 else 4)
                t[o] = v.toByte()
                t[o + 1] = (v shr 8).toByte()
            }
        }
        return t
    }

    private fun status(ffc: Boolean): ByteArray {
        val s = ByteArray(40) { '.'.code.toByte() }
        if (ffc) "FFC".toByteArray().copyInto(s, 17)
        return s
    }

    private fun collect(chunkSize: Int, stream: ByteArray): Pair<List<FlirFrame>, FlirFrameParser> {
        val frames = ArrayList<FlirFrame>()
        val parser = FlirFrameParser({ frames.add(it) })
        var i = 0
        while (i < stream.size) {
            val n = minOf(chunkSize, stream.size - i)
            parser.feed(stream.copyOfRange(i, i + n), n)
            i += n
        }
        return frames to parser
    }

    @Test
    fun parsesFramesRegardlessOfChunking() {
        val f1 = buildFrame(layoutAThermal(), ByteArray(500) { 7 }, status(false))
        val f2 = buildFrame(layoutAThermal(), ByteArray(300) { 9 }, status(true))
        for (chunk in intArrayOf(1, 7, 512, 16384, 1_000_000)) {
            val (frames, parser) = collect(chunk, f1 + f2)
            assertEquals("chunk=$chunk", 2, frames.size)
            assertEquals(0L, parser.resyncs)
            assertEquals(500, frames[0].jpgSize)
            assertFalse(frames[0].isFfc)
            assertTrue(frames[1].isFfc)
        }
    }

    @Test
    fun resyncsAfterGarbage() {
        val f = buildFrame(layoutAThermal(), ByteArray(100), status(false))
        val garbage = ByteArray(50) { 0x11 }
        val (frames, parser) = collect(13, garbage + f + garbage + f)
        assertEquals(2, frames.size)
        assertTrue(parser.resyncs >= 1)
    }

    @Test
    fun bogusHeaderIsSkipped() {
        val bogus =
            byteArrayOf(0xEF.toByte(), 0xBE.toByte(), 0, 0, 0, 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F) + ByteArray(40)
        val f = buildFrame(layoutAThermal(), ByteArray(10), status(false))
        val (frames, _) = collect(64, bogus + f)
        assertEquals(1, frames.size)
    }

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
    fun jpegAndStatusAccessors() {
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val (frames, _) = collect(1000, buildFrame(ByteArray(8), jpg, status(true)))
        assertArrayEquals(jpg, frames[0].jpegBytes())
        assertEquals("FFC", frames[0].statusText().substring(17, 20))
    }

    @Test
    fun renderStretchesToFullRange() {
        val raw = IntArray(160 * 120) { 1000 + it % 100 }
        raw[5] = 1000 // minimum
        raw[77] = 1099 // maximum
        val lut = Palettes.lut(Palette.GRAY)
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
        ThermalImage.render(raw, Palettes.lut(Palette.IRON), out) // must not divide by zero
        assertEquals(out[0], out[100])
    }

    @Test
    fun telemetryIsReadFromLastThreePackets() {
        val t = ByteArray(243 * 164)
        fun put(packet: Int, word: Int, v: Int) {
            val o = packet * 164 + 4 + 2 * word
            t[o] = v.toByte()
            t[o + 1] = (v shr 8).toByte()
        }
        put(240, 0, 14)
        put(241, 3, 3138)
        put(242, 79, 0x1234)
        val (frames, _) = collect(4096, buildFrame(t, ByteArray(10), status(false)))
        val tele = ThermalTelemetry.read(frames[0])!!
        assertEquals(240, tele.size)
        assertEquals(14, tele[0])
        assertEquals(3138, tele[83])
        assertEquals(0x1234, tele[239])
        // the image takes only the first 240 packets and does not touch the telemetry
        assertNotNull(ThermalImage.decode(frames[0]))
    }

    @Test
    fun telemetryMissingForShortThermalBlock() {
        val (frames, _) = collect(4096, buildFrame(layoutAThermal(), ByteArray(10), status(false)))
        assertNull(ThermalTelemetry.read(frames[0]))
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

    @Test
    fun palettesHaveDistinctEnds() {
        for (p in Palette.entries) {
            val l = Palettes.lut(p)
            assertEquals(256, l.size)
            assertTrue("$p", l[0] != l[255])
        }
    }
}
