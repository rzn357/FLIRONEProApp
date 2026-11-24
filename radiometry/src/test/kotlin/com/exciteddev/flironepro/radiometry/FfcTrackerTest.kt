package com.exciteddev.flironepro.radiometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FfcTrackerTest {

    private fun status(state: String) = """{"ffcState":"$state","shutterTemperature":305.1}"""

    @Test
    fun observeReadsTheStateAndReportsChanges() {
        val t = FfcTracker()
        val first = t.observe(status("FFC_VALID_RAD"), 100)
        assertEquals("FFC_VALID_RAD", first.state)
        assertNull(first.previous)
        assertTrue(first.changed)

        val same = t.observe(status("FFC_VALID_RAD"), 200)
        assertFalse(same.changed)

        val next = t.observe(status("FFC_PROGRESS"), 300)
        assertEquals("FFC_VALID_RAD", next.previous)
        assertTrue(next.changed)
        assertEquals("FFC_PROGRESS", t.lastState)
    }

    @Test
    fun statusWithoutAStateGivesNull() {
        val t = FfcTracker()
        t.observe(status("FFC_DESIRED"), 0)
        val seen = t.observe("no json here", 10)
        assertNull(seen.state)
        assertTrue(seen.changed)
        assertNull(t.lastState)
    }

    @Test
    fun theFrameAfterTheShutterReopensIsAlsoDropped() {
        val t = FfcTracker()
        assertTrue(t.contentAllowed(isFfc = false, now = 1))
        assertFalse(t.contentAllowed(isFfc = true, now = 2))
        assertFalse(t.contentAllowed(isFfc = true, now = 3))
        assertTrue(t.shutterClosed)
        assertFalse(t.contentAllowed(isFfc = false, now = 4)) // the first frame with the shutter open
        assertFalse(t.shutterClosed)
        assertFalse(t.contentAllowed(isFfc = false, now = 5)) // and the one after it
        assertTrue(t.contentAllowed(isFfc = false, now = 6))
    }

    @Test
    fun aClosedShutterCountsAsActivity() {
        val t = FfcTracker()
        t.contentAllowed(isFfc = true, now = 5000)
        assertEquals(5000L, t.lastActivityAt)
    }

    @Test
    fun progressStateCountsAsActivity() {
        val t = FfcTracker()
        t.observe(status("FFC_PROGRESS"), 7000)
        assertEquals(7000L, t.lastActivityAt)
        assertTrue(t.inProgress(now = 1_000_000)) // the state itself keeps it "in progress"
    }

    @Test
    fun inProgressWhileTheShutterIsClosedOrJustAfter() {
        val t = FfcTracker()
        assertFalse(t.inProgress(now = 10_000))
        t.contentAllowed(isFfc = true, now = 10_000)
        assertTrue(t.inProgress(now = 10_100))
        t.contentAllowed(isFfc = false, now = 10_200)
        assertTrue(t.inProgress(now = 10_300)) // within the grace period after the last closed frame
        assertFalse(t.inProgress(now = 11_000))
    }

    @Test
    fun wantedWhileTheCameraAsksForACalibration() {
        val t = FfcTracker()
        assertFalse(t.wanted())
        t.observe(status("FFC_VALID_IMG"), 0)
        assertTrue(t.wanted())
        t.observe(status("FFC_DESIRED"), 1)
        assertTrue(t.wanted())
        t.observe(status("FFC_VALID_RAD"), 2)
        assertFalse(t.wanted())
    }

    @Test
    fun fpsIsMeasuredOverAboutOneSecond() {
        val m = FpsMeter()
        // 10 frames per second: a frame every 100 ms.
        for (i in 0..10) m.onFrame(1000L + i * 100L)
        assertEquals(11f, m.fps, 0.001f)
    }

    @Test
    fun fpsStaysZeroUntilTheFirstWindowIsFull() {
        val m = FpsMeter()
        m.onFrame(5000)
        m.onFrame(5100)
        assertEquals(0f, m.fps, 0f)
    }
}
