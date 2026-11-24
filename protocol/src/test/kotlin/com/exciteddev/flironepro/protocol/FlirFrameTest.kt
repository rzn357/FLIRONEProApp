package com.exciteddev.flironepro.protocol

import com.exciteddev.flironepro.protocol.TestFrames.buildFrame
import com.exciteddev.flironepro.protocol.TestFrames.collect
import com.exciteddev.flironepro.protocol.TestFrames.layoutAThermal
import com.exciteddev.flironepro.protocol.TestFrames.status
import com.exciteddev.flironepro.protocol.TestFrames.telemetryThermal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlirFrameTest {

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
    fun jpegAndStatusAccessors() {
        val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val (frames, _) = collect(1000, buildFrame(ByteArray(8), jpg, status(true)))
        assertArrayEquals(jpg, frames[0].jpegBytes())
        assertEquals("FFC", frames[0].statusText().substring(17, 20))
    }

    @Test
    fun telemetryIsReadFromLastThreePackets() {
        val (frames, _) = collect(4096, buildFrame(telemetryThermal(), ByteArray(10), status(false)))
        val tele = ThermalTelemetry.read(frames[0])!!
        assertEquals(240, tele.size)
        assertEquals(14, tele[0])
        assertEquals(3138, tele[83])
        assertEquals(0x1234, tele[239])
    }

    @Test
    fun telemetryMissingForShortThermalBlock() {
        val (frames, _) = collect(4096, buildFrame(layoutAThermal(), ByteArray(10), status(false)))
        assertNull(ThermalTelemetry.read(frames[0]))
    }
}
