package com.exciteddev.flironepro

/**
 * Parses raw USB descriptors (device + all configurations) into readable text.
 * Knows nothing about Android, so it can be tested with an ordinary unit test.
 */
object UsbDescriptorParser {

    fun parse(raw: ByteArray): String {
        val out = StringBuilder()
        var i = 0
        while (i + 2 <= raw.size) {
            val len = raw.u8(i)
            if (len < 2 || i + len > raw.size) {
                out.appendLine("!! corrupted descriptor at offset $i (bLength=$len), the rest: ${hex(raw, i, raw.size - i)}")
                break
            }
            out.appendLine(describe(raw, i, len))
            i += len
        }
        return out.toString().trimEnd()
    }

    private fun describe(raw: ByteArray, off: Int, len: Int): String {
        val type = raw.u8(off + 1)
        return when {
            type == 0x01 && len >= 18 ->
                "DEVICE: USB ${bcd(raw.u16(off + 2))}, class=${className(raw.u8(off + 4))} " +
                    "sub=${raw.u8(off + 5)} proto=${raw.u8(off + 6)}, maxPacket0=${raw.u8(off + 7)}, " +
                    "VID:PID=${hex16(raw.u16(off + 8))}:${hex16(raw.u16(off + 10))}, " +
                    "bcdDevice=${bcd(raw.u16(off + 12))}, configs=${raw.u8(off + 17)}"

            type == 0x02 && len >= 9 ->
                "CONFIG #${raw.u8(off + 5)}: totalLength=${raw.u16(off + 2)}, interfaces=${raw.u8(off + 4)}, " +
                    "attrs=0x${raw.u8(off + 7).toString(16)}, maxPower=${raw.u8(off + 8) * 2}mA"

            type == 0x04 && len >= 9 ->
                "  INTERFACE ${raw.u8(off + 2)} alt=${raw.u8(off + 3)}: endpoints=${raw.u8(off + 4)}, " +
                    "class=${className(raw.u8(off + 5))} sub=${raw.u8(off + 6)} proto=${raw.u8(off + 7)}"

            type == 0x05 && len >= 7 -> {
                val addr = raw.u8(off + 2)
                val dir = if (addr and 0x80 != 0) "IN " else "OUT"
                "    ENDPOINT 0x${addr.toString(16).padStart(2, '0')} $dir ${transferType(raw.u8(off + 3))}, " +
                    "maxPacket=${raw.u16(off + 4)}, interval=${raw.u8(off + 6)}"
            }

            type == 0x0B && len >= 8 ->
                "  IAD: firstInterface=${raw.u8(off + 2)} count=${raw.u8(off + 3)} " +
                    "class=${className(raw.u8(off + 4))} sub=${raw.u8(off + 5)} proto=${raw.u8(off + 6)}"

            else -> "    ? type 0x${type.toString(16).padStart(2, '0')} (len=$len): ${hex(raw, off, len)}"
        }
    }

    fun className(c: Int): String = when (c) {
        0x00 -> "0x00(per-interface)"
        0x01 -> "0x01(Audio)"
        0x02 -> "0x02(CDC)"
        0x03 -> "0x03(HID)"
        0x06 -> "0x06(Image)"
        0x08 -> "0x08(MassStorage)"
        0x09 -> "0x09(Hub)"
        0x0A -> "0x0A(CDC-Data)"
        0x0E -> "0x0E(Video)"
        0xEF -> "0xEF(Misc)"
        0xFE -> "0xFE(AppSpecific)"
        0xFF -> "0xFF(Vendor)"
        else -> "0x${c.toString(16).padStart(2, '0')}"
    }

    private fun transferType(attrs: Int) = when (attrs and 3) {
        0 -> "CONTROL"
        1 -> "ISO"
        2 -> "BULK"
        else -> "INTERRUPT"
    }

    fun hex16(v: Int) = v.toString(16).padStart(4, '0').uppercase()

    private fun bcd(v: Int) = "${v shr 8 and 0xFF}.${(v and 0xFF).toString(16).padStart(2, '0')}"

    fun hex(raw: ByteArray, off: Int, len: Int): String =
        (off until (off + len).coerceAtMost(raw.size)).joinToString(" ") {
            raw.u8(it).toString(16).padStart(2, '0')
        }

    private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
    private fun ByteArray.u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
}
