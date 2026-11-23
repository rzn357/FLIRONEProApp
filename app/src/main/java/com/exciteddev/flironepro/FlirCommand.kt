package com.exciteddev.flironepro

import java.util.zip.CRC32

/**
 * Framing of the camera's service JSON commands (bulk OUT channel 0x02).
 *
 * A command is sent as two bulk transfers:
 *   1) 16-byte header: CC 01 00 00 | 01 00 00 00 | payload length (u32 LE) | CRC32 of the first 12 bytes (u32 LE)
 *   2) payload: JSON in UTF-8 with a terminating zero byte (the length includes this byte)
 * The format was derived from command samples captured by the community and verified by matching the CRC32.
 */
object FlirCommand {
    const val OPEN_CAMERA_FILES = """{"type":"openFile","data":{"mode":"r","path":"CameraFiles.zip"}}"""
    const val READ_STREAM_10 = """{"type":"readFile","data":{"streamIdentifier":10}}"""

    fun readFile(streamIdentifier: Int): String = """{"type":"readFile","data":{"streamIdentifier":$streamIdentifier}}"""

    fun payload(json: String): ByteArray = json.toByteArray(Charsets.UTF_8) + 0.toByte()

    fun header(payloadLength: Int): ByteArray {
        val h = ByteArray(16)
        h[0] = 0xCC.toByte()
        h[1] = 0x01
        h[4] = 0x01
        putU32(h, 8, payloadLength.toLong())
        val crc = CRC32().apply { update(h, 0, 12) }.value
        putU32(h, 12, crc)
        return h
    }

    private fun putU32(b: ByteArray, o: Int, v: Long) {
        b[o] = v.toByte()
        b[o + 1] = (v shr 8).toByte()
        b[o + 2] = (v shr 16).toByte()
        b[o + 3] = (v shr 24).toByte()
    }
}
