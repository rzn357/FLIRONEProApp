package com.exciteddev.flironepro

/**
 * Assembles frames from the byte stream of endpoint 0x85. Independent of how USB split the data into chunks:
 * it looks for the magic bytes, reads the size and waits until the frame is complete.
 */
class FlirFrameParser(private val onFrame: (FlirFrame) -> Unit, private val onEvent: (String) -> Unit = {}) {
    private val stream = ByteStreamBuffer(INITIAL_CAPACITY)

    var framesParsed = 0L
        private set
    var resyncs = 0L
        private set
    var bytesSkipped = 0L
        private set

    fun feed(chunk: ByteArray, n: Int) {
        if (n <= 0) return
        stream.append(chunk, n)
        process()
    }

    fun reset() {
        stream.clear()
    }

    private fun process() {
        stream.pos = 0
        var more = true
        while (more) more = step()
        stream.discardConsumed()
    }

    /** Deals with the data at the read position; false when more bytes have to arrive before anything else can be done. */
    private fun step(): Boolean {
        val magicAt = stream.indexOf(MAGIC, stream.pos)
        if (magicAt < 0) {
            skipWithoutMagic()
            return false
        }
        if (magicAt > stream.pos) resync(magicAt)
        return readFrame()
    }

    private fun skipWithoutMagic() {
        // The magic bytes may begin at a chunk boundary - keep the last bytes of the buffer.
        val keep = maxOf(stream.pos, stream.length - (MAGIC.size - 1))
        val skipped = keep - stream.pos
        if (skipped > 0) {
            bytesSkipped += skipped
            if (stream.available > MAGIC.size - 1) onEvent("No magic bytes, skipped $skipped bytes")
        }
        stream.pos = keep
    }

    private fun resync(magicAt: Int) {
        val skipped = magicAt - stream.pos
        resyncs++
        bytesSkipped += skipped
        onEvent("Lost synchronization: skipped $skipped bytes before the magic bytes")
        stream.pos = magicAt
    }

    private fun readFrame(): Boolean {
        if (stream.available < HEADER_READ) return false
        val frameSize = stream.u32(stream.pos + FRAME_SIZE_AT)
        val total = frameSize.toLong() + FlirFrame.HEADER_SIZE
        return when {
            frameSize < 0 || total > MAX_FRAME -> {
                resyncs++
                onEvent("Invalid header: FrameSize=$frameSize, skipping a byte")
                bytesSkipped++
                stream.pos++
                true
            }

            // The frame has not fully arrived yet.
            stream.available < total -> false

            else -> {
                emitFrame(total.toInt())
                true
            }
        }
    }

    private fun emitFrame(total: Int) {
        val start = stream.pos
        val frame = FlirFrame(
            stream.copyOf(start, total),
            stream.u32(start + THERMAL_SIZE_AT),
            stream.u32(start + JPG_SIZE_AT),
            stream.u32(start + STATUS_SIZE_AT)
        )
        stream.pos = start + total
        framesParsed++
        onFrame(frame)
    }

    private companion object {
        const val INITIAL_CAPACITY = 512 * 1024
        const val FRAME_SIZE_AT = 8
        const val THERMAL_SIZE_AT = 12
        const val JPG_SIZE_AT = 16
        const val STATUS_SIZE_AT = 20
        const val HEADER_READ = 24
        const val MAX_FRAME = 4L * 1024 * 1024
        val MAGIC = byteArrayOf(0xEF.toByte(), 0xBE.toByte(), 0, 0)
    }
}
