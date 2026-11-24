package com.exciteddev.flironepro.protocol

/** Builds frames in the camera's format for tests, here and in the modules that depend on :protocol. */
object TestFrames {
    private fun u32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    /** Builds a frame in the camera's format from the given blocks. */
    fun buildFrame(thermal: ByteArray, jpg: ByteArray, status: ByteArray): ByteArray {
        val frameSize = thermal.size + jpg.size + status.size
        val header = ByteArray(28)
        header[0] = 0xEF.toByte()
        header[1] = 0xBE.toByte()
        u32(frameSize).copyInto(header, 8)
        u32(thermal.size).copyInto(header, 12)
        u32(jpg.size).copyInto(header, 16)
        u32(status.size).copyInto(header, 20)
        return header + thermal + jpg + status
    }

    /** Thermal data of layout A: a row is 164 pixels, 4 service bytes at the start and 4 more in the middle. Pixel (x,y) = y*160+x. */
    fun layoutAThermal(): ByteArray {
        val t = ByteArray(4 + 120 * 164 * 2)
        for (y in 0 until 120) {
            for (x in 0 until 160) {
                val v = y * 160 + x
                val o = 4 + 2 * (y * 164 + x) + (if (x < 80) 0 else 4)
                t[o] = v.toByte()
                t[o + 1] = (v shr 8).toByte()
            }
        }
        return t
    }

    /** A thermal block of 243 Lepton packets (164 bytes each) whose telemetry words 0 of packet 240, 3 of 241 and 79 of 242 are set. */
    fun telemetryThermal(): ByteArray {
        val t = ByteArray(243 * 164)
        fun put(packet: Int, word: Int, v: Int) {
            val o = packet * 164 + 4 + 2 * word
            t[o] = v.toByte()
            t[o + 1] = (v shr 8).toByte()
        }
        put(240, 0, 14)
        put(241, 3, 3138)
        put(242, 79, 0x1234)
        return t
    }

    /** A 40-byte status block; with [ffc] it carries the "FFC" mark. */
    fun status(ffc: Boolean): ByteArray {
        val s = ByteArray(40) { '.'.code.toByte() }
        if (ffc) "FFC".toByteArray().copyInto(s, 17)
        return s
    }

    /** Feeds [stream] to a [FlirFrameParser] in chunks of [chunkSize] bytes and returns the frames it found, and the parser. */
    fun collect(chunkSize: Int, stream: ByteArray): Pair<List<FlirFrame>, FlirFrameParser> {
        val frames = ArrayList<FlirFrame>()
        val parser = FlirFrameParser({ frames.add(it) })
        var i = 0
        while (i < stream.size) {
            val n = minOf(chunkSize, stream.size - i)
            parser.feed(stream.copyOfRange(i, i + n), n)
            i += n
        }
        return frames to parser
    }
}
