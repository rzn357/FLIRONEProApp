package com.exciteddev.flironepro

/**
 * Framing shared by the camera's service messages and by our commands: a 16-byte header
 * CC 01 00 00 | type (u32 LE) | payload length (u32 LE) | CRC32 of the first 12 bytes (u32 LE), then the payload.
 */
internal object ServiceFraming {
    const val HEADER_SIZE = 16
    const val TYPE_AT = 4
    const val LENGTH_AT = 8
    const val CRC_AT = 12

    /** The CRC32 covers the header up to (not including) the CRC field itself. */
    const val CRC_COVERS = CRC_AT
    val MAGIC = byteArrayOf(0xCC.toByte(), 0x01, 0x00, 0x00)
}

/**
 * Parses the stream of the camera's service messages (bulk IN 0x81 / 0x83).
 * Each message: 16-byte header (see [ServiceFraming]) followed by a payload of the given length.
 * Independent of how USB split the data into chunks.
 */
class FlirMessageParser(private val onMessage: (type: Int, payload: ByteArray) -> Unit) {
    private val stream = ByteStreamBuffer(INITIAL_CAPACITY)

    var messages = 0L
        private set
    var bytesSkipped = 0L
        private set

    fun feed(chunk: ByteArray, n: Int) {
        if (n <= 0) return
        stream.append(chunk, n)
        process()
    }

    private fun process() {
        stream.pos = 0
        var more = true
        while (more) more = step()
        stream.discardConsumed()
    }

    /** Deals with the data at the read position; false when more bytes have to arrive before anything else can be done. */
    private fun step(): Boolean {
        val magicAt = stream.indexOf(ServiceFraming.MAGIC, stream.pos)
        if (magicAt < 0) {
            val keep = maxOf(stream.pos, stream.length - (ServiceFraming.MAGIC.size - 1))
            bytesSkipped += keep - stream.pos
            stream.pos = keep
            return false
        }
        bytesSkipped += magicAt - stream.pos
        stream.pos = magicAt
        return readMessage()
    }

    private fun readMessage(): Boolean {
        if (stream.available < ServiceFraming.HEADER_SIZE) return false
        val start = stream.pos
        val payloadLength = stream.u32(start + ServiceFraming.LENGTH_AT)
        return when {
            !hasValidHeader(start, payloadLength) -> {
                // not a header - keep searching
                bytesSkipped++
                stream.pos++
                true
            }

            // The payload has not arrived yet.
            stream.available - ServiceFraming.HEADER_SIZE < payloadLength -> false

            else -> {
                val payloadStart = start + ServiceFraming.HEADER_SIZE
                val payload = stream.copyOf(payloadStart, payloadLength)
                stream.pos = payloadStart + payloadLength
                messages++
                onMessage(stream.u32(start + ServiceFraming.TYPE_AT), payload)
                true
            }
        }
    }

    private fun hasValidHeader(start: Int, payloadLength: Int): Boolean {
        val crc = stream.u32(start + ServiceFraming.CRC_AT).toLong() and Bytes.U32_MASK
        return payloadLength in 0..MAX_PAYLOAD && crc == stream.crc32(start, ServiceFraming.CRC_COVERS)
    }

    private companion object {
        const val INITIAL_CAPACITY = 64 * 1024
        const val MAX_PAYLOAD = 16 * 1024 * 1024
    }
}
