package com.exciteddev.flironepro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlirMessageParserTest {

    private fun msg(json: String): ByteArray {
        val p = FlirCommand.payload(json)
        return FlirCommand.header(p.size) + p
    }

    private fun run(chunk: Int, stream: ByteArray): List<String> {
        val out = ArrayList<String>()
        val parser = FlirMessageParser { _, payload -> out.add(String(payload, Charsets.UTF_8).trimEnd('\u0000')) }
        var i = 0
        while (i < stream.size) {
            val n = minOf(chunk, stream.size - i)
            parser.feed(stream.copyOfRange(i, i + n), n)
            i += n
        }
        return out
    }

    @Test
    fun parsesMessagesRegardlessOfChunking() {
        val a = """{"type":"openFileStatus","data":{"path":"CameraFiles.zip","streamIdentifier":13,"mode":"r"}}"""
        val b = """{"type":"batteryVoltageUpdate","data":{"voltage":3.7,"percentage":39}}"""
        val stream = msg(a) + msg(b) + msg(a)
        for (chunk in intArrayOf(1, 5, 16, 17, 100, 100000)) {
            assertEquals("chunk=$chunk", listOf(a, b, a), run(chunk, stream))
        }
    }

    @Test
    fun headerAndPayloadMayArriveSeparately() {
        val j = """{"x":1}"""
        val p = FlirCommand.payload(j)
        val out = ArrayList<String>()
        val parser = FlirMessageParser { _, payload -> out.add(String(payload, Charsets.UTF_8).trimEnd('\u0000')) }
        val h = FlirCommand.header(p.size)
        parser.feed(h, h.size)
        assertTrue(out.isEmpty())
        parser.feed(p, p.size)
        assertEquals(listOf(j), out)
    }

    @Test
    fun skipsGarbageAndBadCrc() {
        val j = """{"ok":true}"""
        val bad = msg("""{"bad":1}""").also { it[12] = (it[12] + 1).toByte() } // зіпсований CRC
        val garbage = ByteArray(30) { 0x55 }
        assertEquals(listOf(j), run(7, garbage + bad + garbage + msg(j)))
    }

    @Test
    fun readFileCommandMatchesConstant() {
        assertEquals(FlirCommand.READ_STREAM_10, FlirCommand.readFile(10))
    }
}
