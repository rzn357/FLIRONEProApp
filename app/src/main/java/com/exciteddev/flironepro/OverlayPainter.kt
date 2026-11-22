package com.exciteddev.flironepro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Locale

/** Мітка в нормалізованих координатах теплового кадру з температурою (якщо відома). */
class Reading(val u: Float, val v: Float, val tempC: Double?)

/** Усе, що малюється поверх картинки і змінюється від кадру до кадру. */
class OverlayState(
    val mode: DisplayMode,
    val paletteLabel: String,
    val lut: IntArray,
    val cross: Reading?,
    val hot: Reading?,
    val cold: Reading?,
    val scaleLoC: Double?,
    val scaleHiC: Double?,
    val waitingForAnchor: Boolean,
    val ffcActive: Boolean,
    val emissivity: Double,
    val fps: Float,
)

/** Додаткові написи тільки для екрана (на знімки й відео не потрапляють). */
class Hud(
    val battery: BatteryState,
    val recordingMs: Long?,
    val message: String?,
    val demo: Boolean,
)

/**
 * Малює накладення поверх зображення. Для екрана й для знімків/відео використовується той самий код,
 * тому мітки збігаються з картинкою в будь-якому повороті.
 */
class OverlayPainter(private val density: Float) {
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

    /** Нормалізовані координати теплового кадру -> координати вмісту (у режимі видимої камери - через вирівнювання). */
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
        hud: Hud?,
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
                drawMarker(canvas, t, x, y, 0xFFFF3B30.toInt(), up = true, label = h.tempC?.let { fmt(it) })
            }
            o.cold?.let { c ->
                val (x, y) = px(c.u, c.v)
                drawMarker(canvas, t, x, y, 0xFF2F9BFF.toInt(), up = false, label = c.tempC?.let { fmt(it) })
            }
        }

        val (cx, cy) = px(crossU, crossV)
        drawCrosshair(canvas, t, cx, cy, o.cross?.tempC?.let { fmt(it) })

        if (hud != null) drawHud(canvas, t, o, hud)
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

    /** Підпис із темною підкладкою; не виходить за межі кадру. */
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
        val h = t.destH * 0.6f
        val right = t.destLeft + t.destW - margin
        val left = right - barW
        val top = t.destTop + (t.destH - h) / 2f
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
        val px = IntArray(256) { lut[255 - it] } // згори гаряче
        val bm = Bitmap.createBitmap(px, 1, 256, Bitmap.Config.ARGB_8888)
        scaleBitmap = bm
        scaleLut = lut
        return bm
    }

    private fun drawHud(canvas: Canvas, t: ViewTransform, o: OverlayState, hud: Hud) {
        val m = dp(8f)
        text.textSize = dp(13f)

        val bat = hud.battery.label()
        if (bat.isNotEmpty()) drawLabel(canvas, t, bat, m, m, if ((hud.battery.percentage ?: 100) <= 15) 0xFFFF5252.toInt() else Color.WHITE)

        val info = buildString {
            append(o.mode.label)
            if (o.mode != DisplayMode.VISIBLE) append(" · ").append(o.paletteLabel)
            append(" · ").append(String.format(Locale.US, "%.1f", o.fps)).append(" к/с")
        }
        drawLabel(canvas, t, info, t.viewW - text.measureText(info) - m - dp(12f), m, Color.WHITE)

        hud.recordingMs?.let { ms ->
            val sec = ms / 1000
            val s = String.format(Locale.US, "● REC %d:%02d", sec / 60, sec % 60)
            drawLabel(canvas, t, s, (t.viewW - text.measureText(s)) / 2f - dp(4f), m + dp(30f), 0xFFFF5252.toInt())
        }

        val notes = ArrayList<String>()
        if (hud.demo) notes.add("ДЕМО: збережені кадри, температури не точні")
        if (o.waitingForAnchor) notes.add("чекаю калібрування затвором (FFC)…")
        else if (o.ffcActive) notes.add("калібрування затвором…")
        if (o.emissivity < 0.999) notes.add(String.format(Locale.US, "ε %.2f", o.emissivity))
        val rowH = text.textSize + dp(12f)
        var y = t.viewH - m - rowH
        for (n in notes.asReversed()) {
            drawLabel(canvas, t, n, m, y, 0xFFFFD60A.toInt())
            y -= rowH
        }

        hud.message?.let { msg ->
            val w = text.measureText(msg)
            drawLabel(canvas, t, msg, (t.viewW - w) / 2f - dp(4f), t.viewH * 0.4f, Color.WHITE)
        }
    }

    private fun fmt(c: Double) = String.format(Locale.US, "%.1f°C", c)
}
