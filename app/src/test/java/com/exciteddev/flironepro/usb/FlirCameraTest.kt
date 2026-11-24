package com.exciteddev.flironepro.usb

import com.exciteddev.flironepro.protocol.FlirCommand
import com.exciteddev.flironepro.protocol.FlirFrame
import com.exciteddev.flironepro.protocol.TestFrames
import com.exciteddev.flironepro.testing.awaitThat
import com.exciteddev.flironepro.testing.realClock
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The camera driver with its reader threads, against a fake USB device. */
class FlirCameraTest {
    private class Events : FlirCamera.Listener {
        val frames = CopyOnWriteArrayList<FlirFrame>()
        val states = CopyOnWriteArrayList<String>()
        val messages = CopyOnWriteArrayList<String>()

        @Volatile var lost = 0

        override fun onFrame(frame: FlirFrame) {
            frames.add(frame)
        }

        override fun onState(message: String) {
            states.add(message)
        }

        override fun onLost() {
            lost++
        }

        override fun onServiceMessage(json: String) {
            messages.add(json)
        }
    }

    private val events = Events()

    /** A timed-out read waits a little (more than the reader's threshold for a failure that is no timeout), so the tests are quick. */
    private val link = FakeUsbLink(idle = { Thread.sleep(IDLE_MS) })
    private var camera = FlirCamera({ link }, events, realClock)

    @After
    fun stopCamera() = camera.stop()

    private fun frame(ffc: Boolean = false) = TestFrames.buildFrame(TestFrames.layoutAThermal(), ByteArray(10), TestFrames.status(ffc))

    @Test
    fun startClaimsTheInterfacesStartsTheVideoAndReportsIt() {
        assertTrue(camera.start())
        assertTrue(camera.isRunning)
        assertEquals(listOf(0, 1, 2), link.claimed)
        assertEquals(listOf("set alt=0 if=2 len=0", "set alt=0 if=1 len=0", "set alt=1 if=1 len=0", "set alt=1 if=2 len=2"), link.controls)
        assertEquals(listOf("Stream started"), events.states)
    }

    @Test
    fun startingARunningCameraAgainChangesNothing() {
        assertTrue(camera.start())
        assertTrue(camera.start())
        assertEquals(listOf(0, 1, 2), link.claimed)
    }

    @Test
    fun framesFromTheVideoEndpointReachTheListener() {
        camera.start()
        link.feedChunked(FlirUsb.EP_FRAMES, frame() + frame(ffc = true) + frame(), chunkSize = 4096)
        awaitThat("three frames") { events.frames.size == 3 }
        assertEquals(listOf(false, true, false), events.frames.map { it.isFfc })
    }

    @Test
    fun serviceMessagesFromBothServiceEndpointsReachTheListener() {
        camera.start()
        val battery = """{"type":"batteryVoltageUpdate","data":{"percentage":73,"voltage":3.9}}"""
        val reply = """{"type":"openFileStatus","data":{"streamIdentifier":4}}"""
        link.feed(FlirUsb.EP_SERVICE_A, serviceMessage(battery))
        link.feed(FlirUsb.EP_SERVICE_B, serviceMessage(reply))
        awaitThat("both messages") { events.messages.size == 2 }
        assertEquals(setOf(battery, reply), events.messages.map { it.trimEnd('\u0000') }.toSet())
        // Replies to commands (not the battery reports) are kept for whoever waits for them.
        assertEquals(reply, camera.service.replies.poll()?.trimEnd('\u0000'))
    }

    @Test
    fun aMessageSplitAcrossReadsIsPutTogether() {
        camera.start()
        val json = """{"type":"batteryVoltageUpdate","data":{"percentage":50}}"""
        link.feedChunked(FlirUsb.EP_SERVICE_A, serviceMessage(json), chunkSize = 7)
        awaitThat("the message") { events.messages.isNotEmpty() }
        assertEquals(1, events.messages.size)
    }

    @Test
    fun stopStopsTheStreamReleasesTheInterfacesAndClosesTheDevice() {
        camera.start()
        link.controls.clear()
        camera.stop()
        assertFalse(camera.isRunning)
        assertEquals(listOf("set alt=0 if=2 len=0", "set alt=0 if=1 len=0"), link.controls)
        assertEquals(listOf(0, 1, 2), link.released)
        assertTrue(link.closed)
    }

    @Test
    fun stoppingACameraThatNeverStartedDoesNothing() {
        camera.stop()
        assertTrue(link.controls.isEmpty())
        assertFalse(link.closed)
    }

    @Test
    fun lostConnectionIsReportedOnceAndStopsTheReaders() {
        camera.start()
        link.breakReads(FlirUsb.EP_FRAMES)
        awaitThat("the loss") { events.lost > 0 }
        awaitThat("the driver to notice") { !camera.isRunning }
        camera.stop()
        assertEquals(1, events.lost)
        assertTrue(link.closed)
    }

    @Test
    fun silenceIsNotALostConnection() {
        camera.start()
        Thread.sleep(SILENCE_CHECK_MS)
        assertEquals(0, events.lost)
        assertTrue(camera.isRunning)
    }

    @Test
    fun startFailsWithoutPermission() {
        camera = FlirCamera({ null }, events, realClock)
        assertFalse(camera.start())
        assertFalse(camera.isRunning)
        assertTrue(events.states.single().startsWith("Error:"))
    }

    @Test
    fun startFailsAndLetsGoOfTheDeviceWhenThereIsNoVideoEndpoint() {
        val noVideo = FakeUsbLink(interfaces = FLIR_LAYOUT - FlirUsb.VIDEO_INTERFACE)
        camera = FlirCamera({ noVideo }, events, realClock)
        assertFalse(camera.start())
        assertTrue(noVideo.closed)
        assertTrue(noVideo.claimed.isEmpty())
        assertTrue(events.states.single().contains("0x85"))
    }

    @Test
    fun startFailsAndLetsGoOfTheDeviceWhenTheCameraRefusesToStream() {
        link.setInterfaceResult = { alt, id, _ -> if (alt == 1 && id == 2) -1 else 0 }
        assertFalse(camera.start())
        assertFalse(camera.isRunning)
        assertEquals(listOf(0, 1, 2), link.released)
        assertTrue(link.closed)
        assertTrue(events.states.single().startsWith("Error:"))
    }

    @Test
    fun commandsGoToTheCommandEndpointWhileTheCameraRuns() {
        assertFalse(camera.sendJson(FlirCommand.OPEN_CAMERA_FILES))
        camera.start()
        assertTrue(camera.canSend)
        assertTrue(camera.sendJson(FlirCommand.OPEN_CAMERA_FILES))
        val payload = FlirCommand.payload(FlirCommand.OPEN_CAMERA_FILES)
        assertArrayEquals(payload, link.written.last().second)
        assertEquals(FlirUsb.EP_COMMANDS, link.written.last().first)
        camera.stop()
        assertFalse(camera.sendJson(FlirCommand.OPEN_CAMERA_FILES))
    }

    @Test
    fun withoutACommandEndpointNothingCanBeSent() {
        val noCommands = FakeUsbLink(
            interfaces = FLIR_LAYOUT + (FlirUsb.SERVICE_INTERFACE_A to setOf(FlirUsb.EP_SERVICE_A)),
            idle = { Thread.sleep(IDLE_MS) }
        )
        camera = FlirCamera({ noCommands }, events, realClock)
        assertTrue(camera.start())
        assertFalse(camera.canSend)
        assertFalse(camera.sendJson(FlirCommand.OPEN_CAMERA_FILES))
        assertNotNull(camera.service)
    }

    private companion object {
        const val IDLE_MS = 30L
        const val SILENCE_CHECK_MS = 400L
    }
}
