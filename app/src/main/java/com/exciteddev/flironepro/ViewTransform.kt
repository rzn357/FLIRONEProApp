package com.exciteddev.flironepro

/** Display modes. */
enum class DisplayMode {
    MSX,
    THERMAL,
    VISIBLE,
    ;
}

/**
 * Alignment of the visible camera relative to the thermal one. Normalized thermal-frame coordinates (u,v in [0,1])
 * map to normalized visible-frame coordinates as u' = scale*(u-0.5) + 0.5 + dx.
 * scale < 1 means the thermal frame covers only the central part of the visible one.
 */
class Alignment(val scale: Float = DEFAULT_SCALE, val dx: Float = DEFAULT_DX, val dy: Float = DEFAULT_DY) {
    fun thermalToVisibleU(u: Float) = scale * (u - 0.5f) + 0.5f + dx
    fun thermalToVisibleV(v: Float) = scale * (v - 0.5f) + 0.5f + dy
    fun visibleToThermalU(u: Float) = (u - 0.5f - dx) / scale + 0.5f
    fun visibleToThermalV(v: Float) = (v - 0.5f - dy) / scale + 0.5f

    fun copy(scale: Float = this.scale, dx: Float = this.dx, dy: Float = this.dy) = Alignment(scale, dx, dy)

    companion object {
        // Estimated from saved frames (edge correlation of the thermal and visible images): the thermal frame covers
        // ~83 % of the visible width and is shifted slightly to the right. It depends on distance (parallax), so the
        // app offers manual fine-tuning.
        const val DEFAULT_SCALE = 0.834f
        const val DEFAULT_DX = 0.063f
        const val DEFAULT_DY = 0f
    }
}

/** Rotation of the camera image relative to the screen. */
object Orientation {
    /**
     * With the dongle plugged into the bottom of a phone held in natural portrait, the sensor image has to be rotated
     * 90 degrees clockwise to look upright (checked on real frames: text on a package reads correctly only this way).
     */
    const val SENSOR_BASE = 90

    /**
     * Effective clockwise rotation of the image.
     * @param auto compensate for the current screen rotation, so the picture stays upright in any phone orientation
     * @param offset manual correction in degrees (0, 90, 180, 270)
     * @param displayRotation current screen rotation in degrees, counter-clockwise from natural (Surface.ROTATION_* * 90)
     */
    fun effective(auto: Boolean, offset: Int, displayRotation: Int): Int {
        val v = if (auto) SENSOR_BASE + offset - displayRotation else offset
        return ((v % 360) + 360) % 360
    }
}

/**
 * Geometry of the picture on the screen: content (normalized u,v in [0,1], origin at top-left) -> mirror -> clockwise
 * rotation -> fit (or cover) centered into the rectangle [0,viewW]x[0,viewH].
 * The same class is used by the screen and by photos/videos, so overlay marks always match the picture.
 *
 * @param cover if true the picture fills the whole view and the edges are cropped; otherwise it is fully visible
 */
class ViewTransform(
    val viewW: Float,
    val viewH: Float,
    contentAspect: Float,
    val rotation: Int,
    val mirror: Boolean,
    val cover: Boolean = false,
) {
    val destLeft: Float
    val destTop: Float
    val destW: Float
    val destH: Float

    init {
        val rotated = rotation % 180 != 0
        val aspect = if (rotated) 1f / contentAspect else contentAspect
        val viewIsWiderThanContent = viewW / viewH > aspect
        // Fit: limited by height when the view is wider; cover: the other way around.
        val limitByHeight = if (cover) !viewIsWiderThanContent else viewIsWiderThanContent
        if (limitByHeight) {
            destH = viewH
            destW = viewH * aspect
        } else {
            destW = viewW
            destH = viewW / aspect
        }
        destLeft = (viewW - destW) / 2f
        destTop = (viewH - destH) / 2f
    }

    /** The part of the picture that is actually visible on the screen. */
    val visLeft: Float get() = maxOf(0f, destLeft)
    val visTop: Float get() = maxOf(0f, destTop)
    val visRight: Float get() = minOf(viewW, destLeft + destW)
    val visBottom: Float get() = minOf(viewH, destTop + destH)

    /** Content normalized coordinates -> normalized coordinates after mirroring and rotation. */
    private fun rotate(u: Float, v: Float): Pair<Float, Float> {
        val mu = if (mirror) 1f - u else u
        return when (((rotation % 360) + 360) % 360) {
            90 -> Pair(1f - v, mu)
            180 -> Pair(1f - mu, 1f - v)
            270 -> Pair(v, 1f - mu)
            else -> Pair(mu, v)
        }
    }

    private fun unrotate(p: Float, q: Float): Pair<Float, Float> {
        val (mu, v) = when (((rotation % 360) + 360) % 360) {
            90 -> Pair(q, 1f - p)
            180 -> Pair(1f - p, 1f - q)
            270 -> Pair(1f - q, p)
            else -> Pair(p, q)
        }
        return Pair(if (mirror) 1f - mu else mu, v)
    }

    fun toViewX(u: Float, v: Float): Float = destLeft + rotate(u, v).first * destW
    fun toViewY(u: Float, v: Float): Float = destTop + rotate(u, v).second * destH

    /** Touch coordinates on the screen -> normalized content coordinates (may fall outside [0,1]). */
    fun toContent(x: Float, y: Float): Pair<Float, Float> = unrotate((x - destLeft) / destW, (y - destTop) / destH)

    /** True if the screen point is inside the visible part of the view. */
    fun isOnScreen(x: Float, y: Float): Boolean = x in 0f..viewW && y in 0f..viewH

    /** True if the view point lies on the visible part of the picture (not on the empty bars around it). */
    fun containsPoint(x: Float, y: Float): Boolean = x in visLeft..visRight && y in visTop..visBottom

    /**
     * True if a touch may start dragging the crosshair: it is on the picture and not within [guard] pixels of a screen edge,
     * where system swipes (notification shade, navigation gestures) begin.
     */
    fun canStartDragAt(x: Float, y: Float, guard: Float): Boolean =
        x >= guard && x <= viewW - guard && y >= guard && y <= viewH - guard && containsPoint(x, y)
}
