package com.exciteddev.flironepro.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbDescriptorParserTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun parsesDeviceConfigInterfaceEndpoint() {
        val raw = bytes(
            // DEVICE: USB 2.00, class 0xFF, VID 09CB PID 1996, 1 config
            18, 1, 0x00, 0x02, 0xFF, 0xFF, 0xFF, 64, 0xCB, 0x09, 0x96, 0x19, 0x00, 0x01, 1, 2, 3, 1,
            // CONFIG #3, 1 interface, 500 mA
            9, 2, 32, 0, 1, 3, 0, 0x80, 250,
            // INTERFACE 0 alt 0, 1 endpoint, vendor class
            9, 4, 0, 0, 1, 0xFF, 0x00, 0x00, 0,
            // ENDPOINT 0x85 IN BULK, 512
            7, 5, 0x85, 0x02, 0x00, 0x02, 0
        )

        val text = UsbDescriptorParser.parse(raw)

        assertTrue(text, "VID:PID=09CB:1996" in text)
        assertTrue(text, "USB 2.00" in text)
        assertTrue(text, "CONFIG #3" in text && "maxPower=500mA" in text)
        assertTrue(text, "INTERFACE 0 alt=0" in text && "class=0xFF(Vendor)" in text)
        assertTrue(text, "ENDPOINT 0x85 IN  BULK, maxPacket=512" in text)
    }

    @Test
    fun unknownDescriptorIsDumpedAsHex() {
        val text = UsbDescriptorParser.parse(bytes(5, 0x24, 0xAB, 0xCD, 0xEF))
        assertTrue(text, "0x24" in text && "ab cd ef" in text)
    }

    @Test
    fun brokenLengthDoesNotCrash() {
        val text = UsbDescriptorParser.parse(bytes(9, 4, 0))
        assertTrue(text, text.startsWith("!!"))
    }

    @Test
    fun hex16IsUppercaseAndPadded() {
        assertEquals("09CB", UsbDescriptorParser.hex16(0x09CB))
    }
}
