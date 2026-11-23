package com.exciteddev.flironepro

import java.util.zip.CRC32

/**
 * Parses the stream of the camera's service messages (bulk IN 0x81 / 0x83).
 * Each message: 16-byte header CC 01 00 00 | type (u32 LE) | length (u32 LE) | CRC32 of the first 12 bytes (u32 LE),
 * followed by a payload of the given length. Independent of how USB split the data into chunks.
 */
class FlirMessageParser(private val onMessage: (type: Int, payload: ByteArray) -> Unit) {
    private var buf = ByteArray(64 * 1024)
    private var len = 0

    var messages = 0L
        private set
    var bytesSkipped = 0L
        private set

    fun feed(chunk: ByteArray, n: Int) {
        if (n <= 0) return
        if (len + n > buf.size) {
            var size = buf.size
            while (size < len + n) size *= 2
            buf = buf.copyOf(size)
        }
        System.arraycopy(chunk, 0, buf, len, n)
        len += n
        process()
    }

    private fun process() {
        var pos = 0
        while (true) {
            val m = indexOfMagic(pos)
            if (m < 0) {
                val keep = maxOf(pos, len - 3)
                bytesSkipped += keep - pos
                pos = keep
                break
            }
            if (m > pos) {
                bytesSkipped += m - pos
                pos = m
            }
            if (len - pos < HEADER) break

            val type = u32(pos + 4)
            val plen = u32(pos + 8)
            val crc = u32(pos + 12).toLong() and 0xFFFFFFFFL
            val calc = CRC32().apply { update(buf, pos, 12) }.value
            if (crc != calc || plen < 0 || plen > MAX_PAYLOAD) {
                // not a header - keep searching
                bytesSkipped++
                pos++
                continue
            }
            if (len - pos - HEADER < plen) break // the payload has not arrived yet

            val payload = buf.copyOfRange(pos + HEADER, pos + HEADER + plen)
            pos += HEADER + plen
            messages++
            onMessage(type, payload)
        }
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, len - pos)
            len -= pos
        }
    }

    private fun indexOfMagic(from: Int): Int {
        var i = from
        val end = len - 4
        while (i <= end) {
            if (buf[i] == 0xCC.toByte() && buf[i + 1] == 0x01.toByte() && buf[i + 2] == 0.toByte() && buf[i + 3] == 0.toByte()) return i
            i++
        }
        return -1
    }

    private fun u32(o: Int): Int =
        (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0xFF) shl 8) or
            ((buf[o + 2].toInt() and 0xFF) shl 16) or ((buf[o + 3].toInt() and 0xFF) shl 24)

    private companion object {
        const val HEADER = 16
        const val MAX_PAYLOAD = 16 * 1024 * 1024
    }
}
