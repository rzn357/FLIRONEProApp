package com.exciteddev.flironepro

/**
 * Parses raw USB descriptors (device + all configurations) into readable text.
 * Knows nothing about Android, so it can be tested with an ordinary unit test.
 */
object UsbDescriptorParser {
    private const val MIN_DESCRIPTOR = 2
    private const val TYPE_AT = 1
    private const val HEX = 16
    private const val ENDPOINT_IN = 0x80
    private const val TRANSFER_TYPE_MASK = 3
    private const val BYTE_MASK = 0xFF

    private val TRANSFER_TYPES = listOf("CONTROL", "ISO", "BULK", "INTERRUPT")

    private val CLASS_NAMES = mapOf(
        0x00 to "0x00(per-interface)",
        0x01 to "0x01(Audio)",
        0x02 to "0x02(CDC)",
        0x03 to "0x03(HID)",
        0x06 to "0x06(Image)",
        0x08 to "0x08(MassStorage)",
        0x09 to "0x09(Hub)",
        0x0A to "0x0A(CDC-Data)",
        0x0E to "0x0E(Video)",
        0xEF to "0xEF(Misc)",
        0xFE to "0xFE(AppSpecific)",
        0xFF to "0xFF(Vendor)"
    )

    /** Descriptor types and the positions of their fields (bytes from the start of the descriptor). */
    private object Device {
        const val TYPE = 0x01
        const val MIN_LENGTH = 18
        const val BCD_USB = 2
        const val CLASS = 4
        const val SUBCLASS = 5
        const val PROTOCOL = 6
        const val MAX_PACKET0 = 7
        const val VENDOR_ID = 8
        const val PRODUCT_ID = 10
        const val BCD_DEVICE = 12
        const val CONFIGS = 17
    }

    private object Config {
        const val TYPE = 0x02
        const val MIN_LENGTH = 9
        const val TOTAL_LENGTH = 2
        const val INTERFACES = 4
        const val NUMBER = 5
        const val ATTRIBUTES = 7
        const val MAX_POWER = 8

        /** bMaxPower counts in units of 2 mA. */
        const val MA_PER_UNIT = 2
    }

    private object Interface {
        const val TYPE = 0x04
        const val MIN_LENGTH = 9
        const val NUMBER = 2
        const val ALTERNATE = 3
        const val ENDPOINTS = 4
        const val CLASS = 5
        const val SUBCLASS = 6
        const val PROTOCOL = 7
    }

    private object Endpoint {
        const val TYPE = 0x05
        const val MIN_LENGTH = 7
        const val ADDRESS = 2
        const val ATTRIBUTES = 3
        const val MAX_PACKET = 4
        const val INTERVAL = 6
    }

    private object Association {
        const val TYPE = 0x0B
        const val MIN_LENGTH = 8
        const val FIRST_INTERFACE = 2
        const val COUNT = 3
        const val CLASS = 4
        const val SUBCLASS = 5
        const val PROTOCOL = 6
    }

    /** One descriptor inside the raw bytes; field positions are relative to its start. */
    private class Descriptor(val raw: ByteArray, val offset: Int, val length: Int) {
        val type: Int get() = u8(TYPE_AT)
        fun u8(at: Int): Int = Bytes.u8(raw, offset + at)
        fun u16(at: Int): Int = Bytes.u16(raw, offset + at)
    }

    fun parse(raw: ByteArray): String {
        val out = StringBuilder()
        var i = 0
        while (i + MIN_DESCRIPTOR <= raw.size) {
            val len = Bytes.u8(raw, i)
            if (len < MIN_DESCRIPTOR || i + len > raw.size) {
                out.appendLine("!! corrupted descriptor at offset $i (bLength=$len), the rest: ${hex(raw, i, raw.size - i)}")
                break
            }
            out.appendLine(Describer.describe(Descriptor(raw, i, len)))
            i += len
        }
        return out.toString().trimEnd()
    }

    /** Turns one descriptor into a line of text. */
    private object Describer {
        fun describe(d: Descriptor): String = when {
            d.type == Device.TYPE && d.length >= Device.MIN_LENGTH -> describeDevice(d)
            d.type == Config.TYPE && d.length >= Config.MIN_LENGTH -> describeConfig(d)
            d.type == Interface.TYPE && d.length >= Interface.MIN_LENGTH -> describeInterface(d)
            d.type == Endpoint.TYPE && d.length >= Endpoint.MIN_LENGTH -> describeEndpoint(d)
            d.type == Association.TYPE && d.length >= Association.MIN_LENGTH -> describeAssociation(d)
            else -> "    ? type 0x${hexByte(d.type)} (len=${d.length}): ${hex(d.raw, d.offset, d.length)}"
        }

        private fun describeDevice(d: Descriptor) = "DEVICE: USB ${bcd(d.u16(Device.BCD_USB))}, class=${className(d.u8(Device.CLASS))} " +
            "sub=${d.u8(Device.SUBCLASS)} proto=${d.u8(Device.PROTOCOL)}, maxPacket0=${d.u8(Device.MAX_PACKET0)}, " +
            "VID:PID=${hex16(d.u16(Device.VENDOR_ID))}:${hex16(d.u16(Device.PRODUCT_ID))}, " +
            "bcdDevice=${bcd(d.u16(Device.BCD_DEVICE))}, configs=${d.u8(Device.CONFIGS)}"

        private fun describeConfig(d: Descriptor) =
            "CONFIG #${d.u8(Config.NUMBER)}: totalLength=${d.u16(Config.TOTAL_LENGTH)}, interfaces=${d.u8(Config.INTERFACES)}, " +
                "attrs=0x${d.u8(Config.ATTRIBUTES).toString(HEX)}, maxPower=${d.u8(Config.MAX_POWER) * Config.MA_PER_UNIT}mA"

        private fun describeInterface(d: Descriptor) =
            "  INTERFACE ${d.u8(Interface.NUMBER)} alt=${d.u8(Interface.ALTERNATE)}: endpoints=${d.u8(Interface.ENDPOINTS)}, " +
                "class=${className(d.u8(Interface.CLASS))} sub=${d.u8(Interface.SUBCLASS)} proto=${d.u8(Interface.PROTOCOL)}"

        private fun describeEndpoint(d: Descriptor): String {
            val addr = d.u8(Endpoint.ADDRESS)
            val dir = if (addr and ENDPOINT_IN != 0) "IN " else "OUT"
            return "    ENDPOINT 0x${hexByte(addr)} $dir ${TRANSFER_TYPES[d.u8(Endpoint.ATTRIBUTES) and TRANSFER_TYPE_MASK]}, " +
                "maxPacket=${d.u16(Endpoint.MAX_PACKET)}, interval=${d.u8(Endpoint.INTERVAL)}"
        }

        private fun describeAssociation(d: Descriptor) =
            "  IAD: firstInterface=${d.u8(Association.FIRST_INTERFACE)} count=${d.u8(Association.COUNT)} " +
                "class=${className(d.u8(Association.CLASS))} sub=${d.u8(Association.SUBCLASS)} proto=${d.u8(Association.PROTOCOL)}"

        private fun bcd(v: Int) = "${v shr Byte.SIZE_BITS and BYTE_MASK}.${hexByte(v and BYTE_MASK)}"
    }

    fun className(c: Int): String = CLASS_NAMES[c] ?: "0x${hexByte(c)}"

    fun hex16(v: Int) = v.toString(HEX).padStart(Short.SIZE_BYTES * 2, '0').uppercase()

    private fun hexByte(v: Int) = v.toString(HEX).padStart(2, '0')

    fun hex(raw: ByteArray, off: Int, len: Int): String =
        (off until (off + len).coerceAtMost(raw.size)).joinToString(" ") { hexByte(Bytes.u8(raw, it)) }
}
