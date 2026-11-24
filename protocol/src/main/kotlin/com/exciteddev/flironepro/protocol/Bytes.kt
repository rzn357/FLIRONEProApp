package com.exciteddev.flironepro.protocol

/** Little-endian reading and writing of unsigned integers in byte arrays, plus printable-ASCII rendering of raw bytes. */
object Bytes {
    const val U32_MASK = 0xFFFFFFFFL
    private const val BYTE_MASK = 0xFF
    private val PRINTABLE = ' '.code..'~'.code

    fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and BYTE_MASK

    fun u16(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl Byte.SIZE_BITS)

    fun u32(b: ByteArray, i: Int): Int = u16(b, i) or (u16(b, i + Short.SIZE_BYTES) shl Short.SIZE_BITS)

    /** Writes the low 32 bits of [v] at offset [o], least significant byte first. */
    fun putU32(b: ByteArray, o: Int, v: Long) {
        for (k in 0 until Int.SIZE_BYTES) b[o + k] = (v shr (k * Byte.SIZE_BITS)).toByte()
    }

    /** The bytes `buf[start until start + count]` as ASCII text; everything that is not printable becomes '.'. */
    fun asciiText(buf: ByteArray, start: Int, count: Int): String {
        val sb = StringBuilder(count)
        for (i in start until start + count) {
            val c = u8(buf, i)
            sb.append(if (c in PRINTABLE) c.toChar() else '.')
        }
        return sb.toString()
    }
}
