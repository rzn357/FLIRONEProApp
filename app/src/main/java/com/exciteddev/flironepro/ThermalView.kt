package com.exciteddev.flironepro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Екран із зображенням: малює складену картинку з урахуванням повороту/дзеркала, накладення (хрестик, маркери, шкала, HUD)
 * і дозволяє пальцем рухати хрестик.
 */
class ThermalView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Listener {
        fun onCrosshairMoved(u: Float, v: Float)
        fun onTap()
    }

    var listener: Listener? = null
    var settings: AppSettings? = null
    var hud: Hud? = null
        set(value) {
            field = value
            postInvalidateOnAnimation()
        }

    private val painter = OverlayPainter(resources.displayMetrics.density)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY }
    private val matrix = Matrix()
    private val srcPts = FloatArray(8)
    private val dstPts = FloatArray(8)

    private val pending = AtomicReference<Pair<Bitmap, OverlayState>?>(null)
    private var bitmap: Bitmap? = null
    private var overlay: OverlayState? = null

    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    /** Можна викликати з будь-якого потоку: кадри, що не встигли показатись, відкидаються. */
    fun submit(bm: Bitmap, o: OverlayState) {
        pending.set(Pair(bm, o))
        postInvalidateOnAnimation()
    }

    /** Поточний стан зображення для знімків. */
    fun currentFrame(): Pair<Bitmap, OverlayState>? {
        val b = bitmap ?: return null
        val o = overlay ?: return null
        return Pair(b, o)
    }

    override fun onDraw(canvas: Canvas) {
        pending.getAndSet(null)?.let {
            bitmap = it.first
            overlay = it.second
        }
        canvas.drawColor(Color.BLACK)
        val s = settings
        val bm = bitmap
        if (s == null || bm == null) {
            drawEmpty(canvas)
            return
        }
        val t = ViewTransform(width.toFloat(), height.toFloat(), bm.width.toFloat() / bm.height, s.rotation, s.mirror)
        fillMatrix(t, bm)
        canvas.drawBitmap(bm, matrix, bitmapPaint)
        overlay?.let { painter.draw(canvas, t, it, s.crossU, s.crossV, s.alignment, s.showMarkers, s.showScale, hud) }
    }

    private fun fillMatrix(t: ViewTransform, bm: Bitmap) {
        srcPts[0] = 0f; srcPts[1] = 0f
        srcPts[2] = bm.width.toFloat(); srcPts[3] = 0f
        srcPts[4] = bm.width.toFloat(); srcPts[5] = bm.height.toFloat()
        srcPts[6] = 0f; srcPts[7] = bm.height.toFloat()
        val corners = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f), floatArrayOf(1f, 1f), floatArrayOf(0f, 1f))
        for (i in 0 until 4) {
            dstPts[2 * i] = t.toViewX(corners[i][0], corners[i][1])
            dstPts[2 * i + 1] = t.toViewY(corners[i][0], corners[i][1])
        }
        matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)
    }

    private fun drawEmpty(canvas: Canvas) {
        emptyPaint.textSize = 16f * resources.displayMetrics.density
        emptyPaint.textAlign = Paint.Align.CENTER
        val msg = hud?.message ?: "Очікую зображення…"
        canvas.drawText(msg, width / 2f, height / 2f, emptyPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val s = settings ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; moved = false
                moveCrosshair(e.x, e.y, s)
            }

            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) moved = true
                moveCrosshair(e.x, e.y, s)
            }

            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    performClick()
                    listener?.onTap()
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun moveCrosshair(x: Float, y: Float, s: AppSettings) {
        val bm = bitmap ?: return
        val t = ViewTransform(width.toFloat(), height.toFloat(), bm.width.toFloat() / bm.height, s.rotation, s.mirror)
        val (cu, cv) = t.toContent(x, y)
        val mode = overlay?.mode ?: s.mode
        val a = s.alignment
        val u = if (mode == DisplayMode.VISIBLE) a.visibleToThermalU(cu) else cu
        val v = if (mode == DisplayMode.VISIBLE) a.visibleToThermalV(cv) else cv
        s.crossU = u.coerceIn(0.01f, 0.99f)
        s.crossV = v.coerceIn(0.01f, 0.99f)
        listener?.onCrosshairMoved(s.crossU, s.crossV)
        postInvalidateOnAnimation()
    }
}
