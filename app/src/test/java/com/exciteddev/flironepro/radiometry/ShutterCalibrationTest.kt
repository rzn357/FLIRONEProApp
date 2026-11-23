package com.exciteddev.flironepro.radiometry

import com.exciteddev.flironepro.protocol.FlirCommand
import com.exciteddev.flironepro.radiometry.ShutterCalibration.AUTO_OFF
import com.exciteddev.flironepro.radiometry.ShutterCalibration.AUTO_ON
import com.exciteddev.flironepro.radiometry.ShutterCalibration.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShutterCalibrationTest {

    /**
     * A fake camera with a simulated clock. Like the real one it asks for a calibration at [wantedAt] and waits for it while
     * automatic calibration is off; the [good] command makes the shutter close for 1.5 s, but only while it is asked for.
     * With [ignoresAutoOff] it calibrates by itself instead.
     */
    private class FakeCamera(
        private val good: String?,
        private val wantedAt: Long = 30_000L,
        private val ignoresAutoOff: Boolean = false,
        private val failAutoOff: Boolean = false
    ) {
        var time = 0L
        val sent = ArrayList<String>()
        private var autoOff = false
        private var ffcStart = Long.MIN_VALUE

        fun send(cmd: String): Boolean {
            if (cmd == AUTO_OFF && failAutoOff) return false
            sent.add(cmd)
            when {
                cmd == AUTO_OFF -> autoOff = true
                cmd == AUTO_ON -> autoOff = false
                cmd == good && wanted() -> ffcStart = time + 200
            }
            return true
        }

        private fun ranByItself() = (!autoOff || ignoresAutoOff) && time >= wantedAt + 500
        private fun done() = ffcStart != Long.MIN_VALUE || ranByItself()
        fun wanted() = time >= wantedAt && !done()
        fun started(): Long = if (ffcStart != Long.MIN_VALUE) {
            ffcStart
        } else if (ranByItself()) {
            wantedAt + 500
        } else {
            Long.MIN_VALUE
        }
        fun startedSince(t: Long): Boolean {
            val s = started()
            return s != Long.MIN_VALUE && s >= t && time >= s
        }

        fun inProgress(): Boolean {
            val s = started()
            return s != Long.MIN_VALUE && time >= s && time < s + 1500
        }

        fun now() = time
        fun sleep(ms: Long) {
            time += ms
        }
    }

    private fun run(
        cam: FakeCamera,
        candidates: List<String>,
        cancelled: () -> Boolean = { false },
        log: MutableList<String> = ArrayList()
    ) = ShutterCalibration.probe(
        candidates, cam::send, cam::startedSince, cam::inProgress, cam::wanted, cam::now, cam::sleep, { log.add(it) }, cancelled
    )

    @Test
    fun findsTheCommandThatClosesTheShutter() {
        val cam = FakeCamera("good")
        val r = run(cam, listOf("a", "b", "good", "d"))
        assertEquals(Outcome.FOUND, r.outcome)
        assertEquals("good", r.command)
        assertEquals(3, r.tried)
        // the commands after the good one are never sent, and automatic calibration is restored at the end
        assertEquals(listOf(AUTO_OFF, "a", "b", "good", AUTO_ON), cam.sent)
    }

    @Test
    fun reportsWhenNothingReacts() {
        val cam = FakeCamera(null)
        val r = run(cam, listOf("a", "b", "c"))
        assertEquals(Outcome.NOT_FOUND, r.outcome)
        assertNull(r.command)
        assertEquals(3, r.tried)
        assertEquals(listOf(AUTO_OFF, "a", "b", "c", AUTO_ON), cam.sent)
    }

    @Test
    fun givesUpWhenTheCameraNeverAsksForACalibration() {
        val cam = FakeCamera("good", wantedAt = Long.MAX_VALUE / 2)
        val r = run(cam, listOf("good"))
        assertEquals(Outcome.NEVER_REQUESTED, r.outcome)
        assertEquals(listOf(AUTO_OFF, AUTO_ON), cam.sent)
    }

    @Test
    fun aCameraThatCalibratesByItselfProvesNothing() {
        val cam = FakeCamera("good", ignoresAutoOff = true)
        val r = run(cam, listOf("good", "x"))
        assertEquals(Outcome.AUTO_NOT_STOPPED, r.outcome)
        assertNull(r.command)
        assertEquals(listOf(AUTO_OFF, AUTO_ON), cam.sent) // no candidate was sent
    }

    @Test
    fun cancellationStopsTheSearchAndStillRestoresAutomaticCalibration() {
        val cam = FakeCamera("c")
        var asked = 0
        // let the wait for the camera's request pass, then cancel after the first candidate
        val r = run(cam, listOf("a", "b", "c"), cancelled = { cam.time >= 31_000L && ++asked > 1 })
        assertTrue(r.cancelled)
        assertNull(r.command)
        assertEquals(listOf(AUTO_OFF, "a", AUTO_ON), cam.sent)
    }

    @Test
    fun givesUpWhenTheCameraStaysBusy() {
        val cam = FakeCamera("a")
        val log = ArrayList<String>()
        val r = ShutterCalibration.probe(
            listOf("a", "b"),
            cam::send,
            cam::startedSince,
            { true },
            cam::wanted,
            cam::now,
            cam::sleep,
            { log.add(it) }
        )
        assertEquals(Outcome.CAMERA_BUSY, r.outcome)
        assertTrue(cam.sent.isEmpty())
        assertTrue(log.any { "busy" in it })
    }

    @Test
    fun doesNotStartWhenTheFirstCommandCannotBeSent() {
        val cam = FakeCamera("a", failAutoOff = true)
        val r = run(cam, listOf("a"))
        assertEquals(Outcome.COULD_NOT_SEND, r.outcome)
    }

    @Test
    fun failedCandidateSendsAreSkipped() {
        val cam = FakeCamera("b")
        var candidateCalls = 0
        val r = ShutterCalibration.probe(
            listOf("a", "b"),
            { c -> if (c == "a" && candidateCalls++ == 0) false else cam.send(c) },
            cam::startedSince,
            cam::inProgress,
            cam::wanted,
            cam::now,
            cam::sleep,
            {}
        )
        assertEquals("b", r.command)
    }

    @Test
    fun candidateCommandsAreValidFramedJson() {
        for (c in ShutterCalibration.CANDIDATES + listOf(AUTO_OFF, AUTO_ON)) {
            assertTrue(c, c.startsWith("{\"type\":\"") && c.endsWith("}"))
            // framing must work for every command (header CRC and NUL-terminated payload)
            val p = FlirCommand.payload(c)
            assertEquals(c.toByteArray().size + 1, p.size)
            assertEquals(16, FlirCommand.header(p.size).size)
        }
        assertEquals(ShutterCalibration.CANDIDATES.size, ShutterCalibration.CANDIDATES.toSet().size)
    }

    @Test
    fun theSearchNeverSwitchesAutomaticCalibrationOffAmongTheCandidates() {
        assertTrue(ShutterCalibration.CANDIDATES.none { "autoFFC" in it })
    }
}
