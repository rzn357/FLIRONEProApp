package com.exciteddev.flironepro.usb

import com.exciteddev.flironepro.protocol.FlirFrame
import com.exciteddev.flironepro.protocol.FlirFrameParser
import com.exciteddev.flironepro.protocol.TestFrames
import com.exciteddev.flironepro.testing.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reader loop of the video endpoint, run on the test's own thread with a clock that the fake device moves. */
class FrameReaderTest {
    private val clock = FakeClock()
    private val frames = ArrayList<FlirFrame>()
    private var lost = 0
    private val states = ArrayList<String>()

    /** A device whose timed-out reads take their full time on the fake clock, and whose failures take none. */
    private val link = FakeUsbLink(idle = { clock.advance(it.toLong()) })

    private fun reader(isRunning: () -> Boolean) =
        FrameReader(link, FlirFrameParser({ frames.add(it) }), isRunning, { lost++ }, { states.add(it) }, clock.now)

    private fun frame(ffc: Boolean = false) = TestFrames.buildFrame(TestFrames.layoutAThermal(), ByteArray(10), TestFrames.status(ffc))

    @Test
    fun parsesTheFramesThatArriveInChunks() {
        val stream = frame() + frame(ffc = true) + frame()
        link.feedChunked(FlirUsb.EP_FRAMES, stream, chunkSize = 1000)
        val chunks = (stream.size + 999) / 1000
        val reader = reader { link.hasPending(FlirUsb.EP_FRAMES) }
        reader.run()
        assertEquals(listOf(false, true, false), frames.map { it.isFfc })
        assertEquals(chunks.toLong(), reader.chunks)
        assertEquals(stream.size.toLong(), reader.bytes)
        assertEquals(0, lost)
    }

    @Test
    fun aReadLargerThanTheBufferArrivesInTwoReads() {
        link.feed(FlirUsb.EP_FRAMES, frame().let { it + frame() + ByteArray(20_000) })
        reader { link.hasPending(FlirUsb.EP_FRAMES) }.run()
        assertEquals(2, frames.size)
    }

    @Test
    fun givesUpAfterTwentyFiveImmediateFailuresInARow() {
        link.breakReads(FlirUsb.EP_FRAMES)
        val reader = reader { true }
        reader.run() // returns by itself, or the test hangs
        assertEquals(1, lost)
        assertEquals(25, link.readsOf(FlirUsb.EP_FRAMES))
        assertEquals(25L, reader.timeouts)
    }

    @Test
    fun aChunkOfDataStartsTheCountOfFailuresOver() {
        link.feedError(FlirUsb.EP_FRAMES, 24)
        link.feed(FlirUsb.EP_FRAMES, frame())
        link.feedError(FlirUsb.EP_FRAMES, 24)
        reader { link.hasPending(FlirUsb.EP_FRAMES) }.run()
        assertEquals(0, lost)
        assertEquals(1, frames.size)
    }

    @Test
    fun timeoutsAreNotALostConnectionButAreReportedAfterAboutThreeSeconds() {
        var reads = 0
        val reader = reader { reads++ < 100 }
        reader.run()
        assertEquals(0, lost)
        assertEquals(100L, reader.timeouts)
        assertEquals(listOf("No data from the camera for ~3 s"), states)
    }

    @Test
    fun noWarningWhenDataKeepsComing() {
        var reads = 0
        val reader = reader { reads++ < 100 }
        repeat(100) { link.feed(FlirUsb.EP_FRAMES, ByteArray(8)) }
        reader.run()
        assertTrue(states.isEmpty())
        assertEquals(0L, reader.timeouts)
    }
}
