package com.exciteddev.flironepro

/**
 * Збирає кадри з потоку байтів ендпоінта 0x85. Не залежить від того, на які шматки USB порізав дані:
 * шукає магічні байти, читає розмір і чекає, поки кадр набереться повністю.
 */
class FlirFrameParser(
    private val onFrame: (FlirFrame) -> Unit,
    private val onEvent: (String) -> Unit = {},
) {
    private var buf = ByteArray(512 * 1024)
    private var len = 0

    var framesParsed = 0L
        private set
    var resyncs = 0L
        private set
    var bytesSkipped = 0L
        private set

    fun feed(chunk: ByteArray, n: Int) {
        if (n <= 0) return
        ensureCapacity(len + n)
        System.arraycopy(chunk, 0, buf, len, n)
        len += n
        process()
    }

    fun reset() {
        len = 0
    }

    private fun process() {
        var pos = 0
        while (true) {
            val m = indexOfMagic(pos)
            if (m < 0) {
                // Може бути початок магічних байтів на межі шматків - лишаємо останні 3 байти.
                val keep = maxOf(pos, len - 3)
                if (keep > pos) {
                    bytesSkipped += keep - pos
                    if (keep - pos > 0 && len - pos > 3) onEvent("Немає магічних байтів, пропущено ${keep - pos} байт")
                }
                pos = keep
                break
            }
            if (m > pos) {
                resyncs++
                bytesSkipped += m - pos
                onEvent("Втрата синхронізації: пропущено ${m - pos} байт до магічних байтів")
                pos = m
            }
            if (len - pos < HEADER_READ) break

            val frameSize = u32(pos + 8)
            val total = frameSize.toLong() + FlirFrame.HEADER_SIZE
            if (frameSize < 0 || total > MAX_FRAME) {
                resyncs++
                onEvent("Некоректний заголовок: FrameSize=$frameSize, пропускаю байт")
                bytesSkipped++
                pos += 1
                continue
            }
            if (len - pos < total) break // кадр ще не прийшов повністю

            val start = pos
            val data = buf.copyOfRange(start, start + total.toInt())
            val frame = FlirFrame(data, u32(start + 12), u32(start + 16), u32(start + 20))
            pos = start + total.toInt()
            framesParsed++
            onFrame(frame)
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
            if (buf[i] == M0 && buf[i + 1] == M1 && buf[i + 2] == 0.toByte() && buf[i + 3] == 0.toByte()) return i
            i++
        }
        return -1
    }

    private fun u32(o: Int): Int =
        (buf[o].toInt() and 0xFF) or
            ((buf[o + 1].toInt() and 0xFF) shl 8) or
            ((buf[o + 2].toInt() and 0xFF) shl 16) or
            ((buf[o + 3].toInt() and 0xFF) shl 24)

    private fun ensureCapacity(need: Int) {
        if (need <= buf.size) return
        var size = buf.size
        while (size < need) size *= 2
        buf = buf.copyOf(size)
    }

    private companion object {
        const val HEADER_READ = 24
        const val MAX_FRAME = 4L * 1024 * 1024
        const val M0 = 0xEF.toByte()
        const val M1 = 0xBE.toByte()
    }
}
