package com.exciteddev.flironepro

/**
 * One frame from bulk endpoint 0x85.
 *
 * Layout (from the community protocol description, verified on a real camera):
 *   0..3   magic bytes EF BE 00 00
 *   8..11  FrameSize (LE); total frame size = FrameSize + 28
 *   12..15 ThermalSize, 16..19 JpgSize, 20..23 StatusSize (LE)
 *   28..   thermal data, then the visible-camera JPEG, then the text status
 */
class FlirFrame(
    val data: ByteArray,
    val thermalSize: Int,
    val jpgSize: Int,
    val statusSize: Int,
) {
    val thermalOffset: Int get() = HEADER_SIZE
    val jpgOffset: Long get() = HEADER_SIZE.toLong() + thermalSize
    val statusOffset: Long get() = jpgOffset + jpgSize

    /** true if the JPEG lies completely inside the frame. */
    val hasJpeg: Boolean
        get() = jpgSize > 0 && thermalSize >= 0 && jpgOffset + jpgSize <= data.size

    private val hasStatus: Boolean
        get() = statusSize > 0 && thermalSize >= 0 && jpgSize >= 0 && statusOffset + statusSize <= data.size

    /** During the shutter calibration (FFC) the shutter is closed and the thermal image is temporarily invalid. */
    val isFfc: Boolean
        get() {
            if (!hasStatus || statusSize < 20) return false
            val o = statusOffset.toInt() + 17
            return data[o] == 'F'.code.toByte() && data[o + 1] == 'F'.code.toByte() && data[o + 2] == 'C'.code.toByte()
        }

    /** The status as readable ASCII (non-printable characters replaced with '.'). */
    fun statusText(max: Int = 200): String {
        if (!hasStatus) return ""
        val start = statusOffset.toInt()
        val n = minOf(statusSize, max)
        val sb = StringBuilder(n)
        for (i in 0 until n) {
            val c = data[start + i].toInt() and 0xFF
            sb.append(if (c in 32..126) c.toChar() else '.')
        }
        return sb.toString()
    }

    fun jpegBytes(): ByteArray? =
        if (hasJpeg) data.copyOfRange(jpgOffset.toInt(), jpgOffset.toInt() + jpgSize) else null

    companion object {
        const val HEADER_SIZE = 28
    }
}
