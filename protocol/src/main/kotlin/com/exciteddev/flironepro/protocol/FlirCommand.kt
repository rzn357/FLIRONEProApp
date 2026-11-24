package com.exciteddev.flironepro.protocol

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

    /** The message type that marks a header as a command. */
    private const val COMMAND_TYPE = 1

    fun readFile(streamIdentifier: Int): String = """{"type":"readFile","data":{"streamIdentifier":$streamIdentifier}}"""

    fun payload(json: String): ByteArray = json.toByteArray(Charsets.UTF_8) + 0.toByte()

    fun header(payloadLength: Int): ByteArray {
        val h = ByteArray(ServiceFraming.HEADER_SIZE)
        ServiceFraming.MAGIC.copyInto(h)
        Bytes.putU32(h, ServiceFraming.TYPE_AT, COMMAND_TYPE.toLong())
        Bytes.putU32(h, ServiceFraming.LENGTH_AT, payloadLength.toLong())
        val crc = java.util.zip.CRC32().apply { update(h, 0, ServiceFraming.CRC_COVERS) }.value
        Bytes.putU32(h, ServiceFraming.CRC_AT, crc)
        return h
    }
}
