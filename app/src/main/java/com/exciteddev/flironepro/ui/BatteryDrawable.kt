package com.exciteddev.flironepro.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * A battery icon whose fill level follows the charge: the fill drains from the top as the percentage drops, turns red when
 * the battery is low and green with a bolt while charging.
 */
class BatteryDrawable : Drawable() {
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = WHITE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bolt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = WHITE
    }
    private val boltEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xCC000000.toInt()
    }
    private val rect = RectF()
    private val path = Path()

    var percent: Int = 100
        private set
    var charging: Boolean = false
        private set

    fun update(percent: Int, charging: Boolean) {
        val p = clamp(percent)
        if (p == this.percent && charging == this.charging) return
        this.percent = p
        this.charging = charging
        invalidateSelf()
    }

    fun fillColor(): Int = colorFor(percent, charging)

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val stroke = maxOf(1.5f, w * 0.085f)
        outline.strokeWidth = stroke
        outline.color = if (charging) WHITE else fillColor()

        // Body and terminal cap (a vertical battery).
        val bodyL = b.left + w * 0.27f
        val bodyR = b.left + w * 0.73f
        val bodyT = b.top + h * 0.17f
        val bodyB = b.top + h * 0.97f
        val cap = fill.color
        fill.color = outline.color
        rect.set(b.left + w * 0.40f, b.top + h * 0.03f, b.left + w * 0.60f, bodyT + stroke * 0.5f)
        canvas.drawRoundRect(rect, stroke * 0.4f, stroke * 0.4f, fill)
        fill.color = cap

        rect.set(bodyL + stroke / 2, bodyT + stroke / 2, bodyR - stroke / 2, bodyB - stroke / 2)
        canvas.drawRoundRect(rect, w * 0.09f, w * 0.09f, outline)

        // Fill: from the bottom up, proportional to the charge.
        val gap = stroke * 0.9f
        val innerL = bodyL + stroke + gap * 0.4f
        val innerR = bodyR - stroke - gap * 0.4f
        val innerT = bodyT + stroke + gap * 0.4f
        val innerB = bodyB - stroke - gap * 0.4f
        val level = percent / 100f
        var top = innerB - (innerB - innerT) * level
        if (percent > 0 && innerB - top < 1.5f) top = innerB - 1.5f // never invisible while there is some charge
        if (percent > 0) {
            fill.color = fillColor()
            rect.set(innerL, top, innerR, innerB)
            canvas.drawRoundRect(rect, w * 0.04f, w * 0.04f, fill)
        }

        if (charging) {
            val cx = (bodyL + bodyR) / 2
            val cy = (bodyT + bodyB) / 2
            val s = w * 0.19f
            path.reset()
            path.moveTo(cx + s * 0.25f, cy - s * 1.5f)
            path.lineTo(cx - s * 0.75f, cy + s * 0.15f)
            path.lineTo(cx - s * 0.05f, cy + s * 0.15f)
            path.lineTo(cx - s * 0.25f, cy + s * 1.5f)
            path.lineTo(cx + s * 0.75f, cy - s * 0.2f)
            path.lineTo(cx + s * 0.05f, cy - s * 0.2f)
            path.close()
            boltEdge.strokeWidth = maxOf(1f, w * 0.03f)
            canvas.drawPath(path, boltEdge)
            canvas.drawPath(path, bolt)
        }
    }

    override fun setAlpha(alpha: Int) {
        outline.alpha = alpha
        fill.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        outline.colorFilter = colorFilter
        fill.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        const val LOW_PERCENT = 20

        fun clamp(percent: Int): Int = percent.coerceIn(0, 100)

        /** Fill color for a charge level: red when low, green while charging, white otherwise. */
        fun colorFor(percent: Int, charging: Boolean): Int = when {
            charging -> GREEN
            percent <= LOW_PERCENT -> RED
            else -> WHITE
        }

        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val RED = 0xFFFF5252.toInt()
        private const val GREEN = 0xFF66BB6A.toInt()
    }
}
