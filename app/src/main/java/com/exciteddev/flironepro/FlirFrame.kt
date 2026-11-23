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
class FlirFrame(val data: ByteArray, val thermalSize: Int, val jpgSize: Int, val statusSize: Int) {
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
        get() = hasStatus && statusSize >= FFC_MIN_STATUS && hasFfcTag()

    private fun hasFfcTag(): Boolean {
        val start = statusOffset.toInt() + FFC_TAG_OFFSET
        return FFC_TAG.indices.all { data[start + it] == FFC_TAG[it] }
    }

    /** The status as readable ASCII (non-printable characters replaced with '.'). */
    fun statusText(max: Int = 200): String = if (hasStatus) Bytes.asciiText(data, statusOffset.toInt(), minOf(statusSize, max)) else ""

    fun jpegBytes(): ByteArray? = if (hasJpeg) data.copyOfRange(jpgOffset.toInt(), jpgOffset.toInt() + jpgSize) else null

    companion object {
        const val HEADER_SIZE = 28

        /** The status text is shorter than this when it cannot carry the calibration tag. */
        private const val FFC_MIN_STATUS = 20

        /** Position of the "FFC" tag in the status text while the shutter is closed. */
        private const val FFC_TAG_OFFSET = 17
        private val FFC_TAG = "FFC".toByteArray(Charsets.US_ASCII)
    }
}
