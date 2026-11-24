package com.exciteddev.flironepro.usb

import com.exciteddev.flironepro.protocol.FlirCommand
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlirUsbTest {
    @Test
    fun startSequenceStopsFirstThenStartsInterface1BeforeInterface2() {
        val link = FakeUsbLink()
        assertTrue(FlirUsb.startVideo(link))
        assertEquals(
            listOf("set alt=0 if=2 len=0", "set alt=0 if=1 len=0", "set alt=1 if=1 len=0", "set alt=1 if=2 len=2"),
            link.controls
        )
    }

    @Test
    fun startFallsBackToNoDataStageWhenTheCameraRefusesTheLongRequest() {
        val link = FakeUsbLink().apply {
            setInterfaceResult = { alt, id, length ->
                if (alt == 1 && id == 2 &&
                    length == 2
                ) {
                    -1
                } else {
                    length
                }
            }
        }
        assertTrue(FlirUsb.startVideo(link))
        assertEquals("set alt=1 if=2 len=2", link.controls[3])
        assertEquals("set alt=1 if=2 len=0", link.controls[4])
    }

    @Test
    fun startFailsWhenBothVariantsAreRefused() {
        val link = FakeUsbLink().apply { setInterfaceResult = { alt, id, _ -> if (alt == 1 && id == 2) -1 else 0 } }
        assertFalse(FlirUsb.startVideo(link))
    }

    @Test
    fun refusedStopRequestsDoNotStopTheStart() {
        val link = FakeUsbLink().apply { setInterfaceResult = { alt, _, _ -> if (alt == 0) -1 else 0 } }
        assertTrue(FlirUsb.startVideo(link))
    }

    @Test
    fun stopSwitchesInterface2AndThen1BackToAlternateSetting0() {
        val link = FakeUsbLink()
        FlirUsb.stopVideo(link)
        assertEquals(listOf("set alt=0 if=2 len=0", "set alt=0 if=1 len=0"), link.controls)
    }

    @Test
    fun claimsTheThreeInterfacesInOrder() {
        val link = FakeUsbLink()
        assertEquals(listOf(0, 1, 2), FlirUsb.claimAll(link))
        assertEquals(listOf(0, 1, 2), link.claimed)
    }

    @Test
    fun claimSkipsInterfacesThatAreMissingOrRefused() {
        val link = FakeUsbLink(interfaces = FLIR_LAYOUT - FlirUsb.SERVICE_INTERFACE_B).apply {
            unclaimable = setOf(FlirUsb.VIDEO_INTERFACE)
        }
        assertEquals(listOf(0), FlirUsb.claimAll(link))
    }

    @Test
    fun findsEndpointsOnlyInTheirOwnInterface() {
        val link = FakeUsbLink()
        assertTrue(FlirUsb.hasEndpoint(link, FlirUsb.VIDEO_INTERFACE, FlirUsb.EP_FRAMES))
        assertTrue(FlirUsb.hasEndpoint(link, FlirUsb.SERVICE_INTERFACE_A, FlirUsb.EP_COMMANDS))
        assertFalse(FlirUsb.hasEndpoint(link, FlirUsb.SERVICE_INTERFACE_B, FlirUsb.EP_FRAMES))
        assertFalse(FlirUsb.hasEndpoint(link, 7, FlirUsb.EP_FRAMES))
    }

    @Test
    fun commandIsTwoTransfersToTheCommandEndpoint() {
        val link = FakeUsbLink()
        val json = FlirCommand.readFile(10)
        assertTrue(FlirUsb.sendCommand(link, json))
        val payload = FlirCommand.payload(json)
        assertEquals(listOf(FlirUsb.EP_COMMANDS, FlirUsb.EP_COMMANDS), link.written.map { it.first })
        assertArrayEquals(FlirCommand.header(payload.size), link.written[0].second)
        assertArrayEquals(payload, link.written[1].second)
    }

    @Test
    fun commandFailsWhenTheWriteFails() {
        val link = FakeUsbLink().apply { writesFail = true }
        assertFalse(FlirUsb.sendCommand(link, FlirCommand.OPEN_CAMERA_FILES))
    }
}
