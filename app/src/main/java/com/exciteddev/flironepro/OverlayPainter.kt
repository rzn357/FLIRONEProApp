package com.exciteddev.flironepro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Locale

/** A mark in normalized thermal-frame coordinates with a temperature (if known). */
class Reading(val u: Float, val v: Float, val tempC: Double?)

/** Everything drawn over the picture that changes from frame to frame. */
class OverlayState(
    val mode: DisplayMode,
    val lut: IntArray,
    val cross: Reading?,
    val hot: Reading?,
    val cold: Reading?,
    val scaleLoC: Double?,
    val scaleHiC: Double?,
    val waitingForAnchor: Boolean,
    val ffcActive: Boolean,
    val emissivity: Double
)

/** Localized texts used by the overlay painter. */
class UiTexts(
    val locale: Locale,
    val waitingForCalibration: String,
    val shutterCalibration: String,
    val demoNote: String,
    /** Format with one string argument, e.g. "ε %1$s". */
    val emissivityFormat: String
)

/** Extra on-screen notes (not included in photos and videos). */
class Hud(
    val message: String?,
    val demo: Boolean,
    /** Notes are hidden while a panel or card is open over the picture. */
    val showNotes: Boolean = true
)

/**
 * Draws the overlays over the picture. The screen and photos/videos use the same code, so the marks
 * match the picture at any rotation.
 */
class OverlayPainter(private val density: Float, private val texts: UiTexts) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        color = Color.WHITE
    }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xB0000000.toInt()
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val path = Path()

    private var scaleBitmap: Bitmap? = null
    private var scaleLut: IntArray? = null

    private fun dp(v: Float) = v * density

    /** Thermal-frame normalized coordinates -> content coordinates (in visible-camera mode through the alignment). */
    fun thermalToContent(mode: DisplayMode, a: Alignment, u: Float, v: Float): Pair<Float, Float> =
        if (mode == DisplayMode.VISIBLE) Pair(a.thermalToVisibleU(u), a.thermalToVisibleV(v)) else Pair(u, v)

    fun draw(
        canvas: Canvas,
        t: ViewTransform,
        o: OverlayState,
        crossU: Float,
        crossV: Float,
        alignment: Alignment,
        showMarkers: Boolean,
        showScale: Boolean,
        hud: Hud?
    ) {
        val visible = o.mode == DisplayMode.VISIBLE
        fun px(u: Float, v: Float): Pair<Float, Float> {
            val (cu, cv) = thermalToContent(o.mode, alignment, u, v)
            return Pair(t.toViewX(cu, cv), t.toViewY(cu, cv))
        }

        if (showScale && !visible) drawScale(canvas, t, o)

        if (showMarkers) {
            o.hot?.let { h ->
                val (x, y) = px(h.u, h.v)
                if (t.isOnScreen(x, y)) drawMarker(canvas, t, x, y, 0xFFFF3B30.toInt(), up = true, label = h.tempC?.let { fmt(it) })
            }
            o.cold?.let { c ->
                val (x, y) = px(c.u, c.v)
                if (t.isOnScreen(x, y)) drawMarker(canvas, t, x, y, 0xFF2F9BFF.toInt(), up = false, label = c.tempC?.let { fmt(it) })
            }
        }

        val (cx, cy) = px(crossU, crossV)
        drawCrosshair(canvas, t, cx, cy, o.cross?.tempC?.let { fmt(it) })

        if (hud != null && hud.showNotes) drawNotes(canvas, t, o, hud)
    }

    private fun drawCrosshair(canvas: Canvas, t: ViewTransform, x: Float, y: Float, label: String?) {
        val r = dp(11f)
        val tick = dp(8f)
        for (pass in 0..1) {
            stroke.color = if (pass == 0) Color.BLACK else Color.WHITE
            stroke.strokeWidth = if (pass == 0) dp(4f) else dp(1.8f)
            canvas.drawCircle(x, y, r, stroke)
            canvas.drawLine(x - r - tick, y, x - r, y, stroke)
            canvas.drawLine(x + r, y, x + r + tick, y, stroke)
            canvas.drawLine(x, y - r - tick, x, y - r, stroke)
            canvas.drawLine(x, y + r, x, y + r + tick, stroke)
        }
        fill.color = Color.WHITE
        canvas.drawCircle(x, y, dp(1.6f), fill)
        if (label != null) drawLabel(canvas, t, label, x + r + tick + dp(4f), y - r - tick - dp(4f), Color.WHITE)
    }

    private fun drawMarker(canvas: Canvas, t: ViewTransform, x: Float, y: Float, color: Int, up: Boolean, label: String?) {
        val s = dp(7f)
        path.reset()
        if (up) {
            path.moveTo(x, y - s)
            path.lineTo(x - s, y + s)
            path.lineTo(x + s, y + s)
        } else {
            path.moveTo(x, y + s)
            path.lineTo(x - s, y - s)
            path.lineTo(x + s, y - s)
        }
        path.close()
        fill.color = color
        canvas.drawPath(path, fill)
        stroke.color = Color.WHITE
        stroke.strokeWidth = dp(1.5f)
        canvas.drawPath(path, stroke)
        if (label != null) {
            val ly = if (up) y - s - dp(22f) else y + s + dp(4f)
            drawLabel(canvas, t, label, x - dp(18f), ly, color)
        }
    }

    /** A label on a dark backing; it never leaves the frame. */
    private fun drawLabel(canvas: Canvas, t: ViewTransform, s: String, left: Float, top: Float, color: Int) {
        text.textSize = dp(13f)
        text.color = color
        val w = text.measureText(s)
        val pad = dp(4f)
        val h = text.textSize + pad
        val l = left.coerceIn(0f, maxOf(0f, t.viewW - w - 2 * pad))
        val tp = top.coerceIn(0f, maxOf(0f, t.viewH - h - pad))
        rect.set(l, tp, l + w + 2 * pad, tp + h + pad)
        canvas.drawRoundRect(rect, dp(4f), dp(4f), bg)
        canvas.drawText(s, l + pad, tp + text.textSize, text)
    }

    private fun drawScale(canvas: Canvas, t: ViewTransform, o: OverlayState) {
        val bm = scaleBitmapFor(o.lut)
        val barW = dp(14f)
        val margin = dp(10f)
        val h = (t.visBottom - t.visTop) * 0.6f
        val right = t.visRight - margin
        val left = right - barW
        val top = t.visTop + ((t.visBottom - t.visTop) - h) / 2f
        rect.set(left, top, right, top + h)
        canvas.drawBitmap(bm, null, rect, bitmapPaint)
        stroke.color = Color.WHITE
        stroke.strokeWidth = dp(1f)
        canvas.drawRect(rect, stroke)
        val hi = o.scaleHiC?.let { fmt(it) }
        val lo = o.scaleLoC?.let { fmt(it) }
        text.textSize = dp(12f)
        if (hi != null) drawLabel(canvas, t, hi, left - text.measureText(hi) - dp(14f), top - dp(2f), Color.WHITE)
        if (lo != null) drawLabel(canvas, t, lo, left - text.measureText(lo) - dp(14f), top + h - dp(16f), Color.WHITE)
    }

    private fun scaleBitmapFor(lut: IntArray): Bitmap {
        val cached = scaleBitmap
        if (cached != null && scaleLut === lut) return cached
        val px = IntArray(256) { lut[255 - it] } // hot at the top
        val bm = Bitmap.createBitmap(px, 1, 256, Bitmap.Config.ARGB_8888)
        scaleBitmap = bm
        scaleLut = lut
        return bm
    }

    /** Status notes centered below the top bar: demo, waiting for the shutter calibration, emissivity. */
    private fun drawNotes(canvas: Canvas, t: ViewTransform, o: OverlayState, hud: Hud) {
        text.textSize = dp(12f)
        val notes = ArrayList<String>()
        if (hud.demo) notes.add(texts.demoNote)
        if (o.waitingForAnchor) {
            notes.add(texts.waitingForCalibration)
        } else if (o.ffcActive) {
            notes.add(texts.shutterCalibration)
        }
        if (o.emissivity <
            0.999
        ) {
            notes.add(String.format(texts.locale, texts.emissivityFormat, String.format(texts.locale, "%.2f", o.emissivity)))
        }
        var y = dp(64f)
        for (n in notes) {
            drawLabel(canvas, t, n, (t.viewW - text.measureText(n)) / 2f - dp(4f), y, 0xFFFFD60A.toInt())
            y += text.textSize + dp(14f)
        }
    }

    private fun fmt(c: Double) = String.format(texts.locale, "%.1f°C", c)
}
