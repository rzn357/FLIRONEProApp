package com.exciteddev.flironepro.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class FlirCommandTest {

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Reference: the headers sent by the original app (captured by the community). */
    @Test
    fun openFileHeaderMatchesKnownBytes() {
        val payload = FlirCommand.payload(FlirCommand.OPEN_CAMERA_FILES)
        assertEquals(65, payload.size)
        assertEquals("cc0100000100000041000000f8b3f700", hex(FlirCommand.header(payload.size)))
    }

    @Test
    fun readFileHeaderMatchesKnownBytes() {
        val payload = FlirCommand.payload(FlirCommand.READ_STREAM_10)
        assertEquals(51, payload.size)
        assertEquals("cc0100000100000033000000efdbc1c1", hex(FlirCommand.header(payload.size)))
    }

    @Test
    fun payloadIsNulTerminated() {
        val p = FlirCommand.payload("{}")
        assertEquals(0.toByte(), p.last())
        assertEquals(3, p.size)
    }
}
