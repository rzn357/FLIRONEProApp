package com.exciteddev.flironepro

/** The Lepton packet: 4 service bytes and 80 sixteen-bit pixels. The thermal block of a frame is a sequence of them. */
internal object LeptonPacket {
    const val BYTES_PER_PIXEL = 2
    const val HEADER = 4
    const val PIXELS = 80
    const val BYTES = HEADER + PIXELS * BYTES_PER_PIXEL
}

/**
 * The 240 Lepton telemetry words: the last three packets (240..242) of the thermal block. Words 20..31 hold the frame counter,
 * the frame mean, the sensor and housing temperatures, etc.
 */
object ThermalTelemetry {
    private const val FIRST_PACKET = 240
    private const val PACKETS = 3
    const val WORDS = PACKETS * LeptonPacket.PIXELS

    /** The telemetry words of a frame, or null if its thermal block is too short to hold them. */
    fun read(frame: FlirFrame): IntArray? {
        val end = (FIRST_PACKET + PACKETS) * LeptonPacket.BYTES
        if (frame.thermalSize < end || frame.thermalOffset + end > frame.data.size) return null
        val out = IntArray(WORDS)
        for (p in 0 until PACKETS) {
            val base = frame.thermalOffset + (FIRST_PACKET + p) * LeptonPacket.BYTES + LeptonPacket.HEADER
            for (i in 0 until LeptonPacket.PIXELS) {
                out[p * LeptonPacket.PIXELS + i] = Bytes.u16(frame.data, base + LeptonPacket.BYTES_PER_PIXEL * i)
            }
        }
        return out
    }
}
