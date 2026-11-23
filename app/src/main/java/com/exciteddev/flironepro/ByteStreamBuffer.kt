package com.exciteddev.flironepro

import java.util.zip.CRC32

/**
 * Growing byte buffer that a stream parser reads from: chunks are appended at the end, the parser moves [pos] forward as it
 * consumes data, and [discardConsumed] drops what has been dealt with from the front.
 */
internal class ByteStreamBuffer(initialCapacity: Int) {
    private var data = ByteArray(initialCapacity)

    var length = 0
        private set

    /** Read position: everything before it has been dealt with. */
    var pos = 0

    val available: Int get() = length - pos

    fun append(chunk: ByteArray, n: Int) {
        ensureCapacity(length + n)
        System.arraycopy(chunk, 0, data, length, n)
        length += n
    }

    fun clear() {
        length = 0
        pos = 0
    }

    fun u32(offset: Int): Int = Bytes.u32(data, offset)

    fun copyOf(from: Int, count: Int): ByteArray = data.copyOfRange(from, from + count)

    fun crc32(from: Int, count: Int): Long = CRC32().apply { update(data, from, count) }.value

    /** Index of the first place at or after [from] where [magic] starts, or -1. */
    fun indexOf(magic: ByteArray, from: Int): Int {
        for (i in from..length - magic.size) if (matches(magic, i)) return i
        return -1
    }

    /** Drops the consumed bytes before [pos] from the front of the buffer. */
    fun discardConsumed() {
        if (pos > 0) {
            System.arraycopy(data, pos, data, 0, length - pos)
            length -= pos
            pos = 0
        }
    }

    private fun matches(magic: ByteArray, at: Int): Boolean {
        for (k in magic.indices) if (data[at + k] != magic[k]) return false
        return true
    }

    private fun ensureCapacity(need: Int) {
        if (need <= data.size) return
        var size = data.size
        while (size < need) size *= 2
        data = data.copyOf(size)
    }
}
