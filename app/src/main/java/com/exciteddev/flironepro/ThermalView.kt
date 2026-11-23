package com.exciteddev.flironepro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * The picture: draws the composed image with rotation / mirroring / fit-or-fill, the overlays (crosshair, markers,
 * scale, notes) and lets the user drag the crosshair with a finger.
 */
class ThermalView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    interface Listener {
        fun onCrosshairMoved(u: Float, v: Float)
        fun onTap()
    }

    var listener: Listener? = null
    var settings: AppSettings? = null

    /** Hides the overlays (crosshair, markers, scale, notes) while the settings screen covers the picture. */
    @Volatile var overlaysSuppressed = false

    /** Localized overlay texts; must be set before the first frame is drawn. */
    var texts: UiTexts? = null
        set(value) {
            field = value
            painter = value?.let { OverlayPainter(resources.displayMetrics.density, it) }
        }

    var hud: Hud? = null
        set(value) {
            field = value
            postInvalidateOnAnimation()
        }

    private var painter: OverlayPainter? = null
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val emptyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB0B0B0.toInt() }
    private val matrix = Matrix()
    private val srcPts = FloatArray(ViewTransform.CORNER_VALUES)
    private val dstPts = FloatArray(ViewTransform.CORNER_VALUES)

    private val pending = AtomicReference<Pair<Bitmap, OverlayState>?>(null)
    private var bitmap: Bitmap? = null
    private var overlay: OverlayState? = null

    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var dragging = false
    private var startU = 0f
    private var startV = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    /** Can be called from any thread; frames that are not shown in time are dropped. */
    fun submit(bm: Bitmap, o: OverlayState) {
        pending.set(Pair(bm, o))
        postInvalidateOnAnimation()
    }

    /** Current picture state (used for snapshots). */
    fun currentFrame(): Pair<Bitmap, OverlayState>? {
        val b = bitmap
        val o = overlay
        return if (b != null && o != null) Pair(b, o) else null
    }

    /** Forget the last picture (the camera went away) so the empty state with its message is shown again. */
    fun clear() {
        pending.set(null)
        bitmap = null
        overlay = null
        postInvalidateOnAnimation()
    }

    private fun transform(s: AppSettings, bm: Bitmap) =
        ViewTransform(width.toFloat(), height.toFloat(), bm.width.toFloat() / bm.height, s.rotation, s.mirror, s.fillScreen)

    override fun onDraw(canvas: Canvas) {
        pending.getAndSet(null)?.let {
            bitmap = it.first
            overlay = it.second
        }
        canvas.drawColor(Color.BLACK)
        val s = settings
        val bm = bitmap
        val p = painter
        if (s == null || bm == null || p == null) {
            // The empty-state message must not ghost through the translucent settings screen.
            if (!overlaysSuppressed) drawEmpty(canvas)
            return
        }
        val t = transform(s, bm)
        fillMatrix(t, bm)
        canvas.drawBitmap(bm, matrix, bitmapPaint)
        if (!overlaysSuppressed) {
            overlay?.let { p.draw(canvas, t, it, s.crossU, s.crossV, s.alignment, s.showMarkers, s.showScale, hud) }
        }
    }

    private fun fillMatrix(t: ViewTransform, bm: Bitmap) {
        ViewTransform.sourceCorners(bm.width.toFloat(), bm.height.toFloat(), srcPts)
        t.destinationCorners(dstPts)
        matrix.setPolyToPoly(srcPts, 0, dstPts, 0, ViewTransform.CORNER_COUNT)
    }

    /** Empty state: a centered, wrapped message (e.g. "Camera not found"). */
    private fun drawEmpty(canvas: Canvas) {
        val msg = hud?.message ?: return
        emptyPaint.textSize = 16f * resources.displayMetrics.density
        val w = (width * 0.8f).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(msg, 0, msg.length, emptyPaint, w)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        canvas.save()
        canvas.translate((width - w) / 2f, (height - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    /**
     * True if a touch at (x, y) may start dragging the crosshair: it has to be on the picture itself (not on the empty bars
     * around it) and outside the thin strips along the screen edges, where the system swipes (notification shade, navigation
     * gestures) start.
     */
    private fun canStartDrag(x: Float, y: Float, s: AppSettings): Boolean {
        val bm = bitmap ?: return false
        return transform(s, bm).canStartDragAt(x, y, EDGE_GUARD_DP * resources.displayMetrics.density)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val s = settings ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                moved = false
                dragging = canStartDrag(e.x, e.y, s)
                if (dragging) {
                    startU = s.crossU
                    startV = s.crossV
                    moveCrosshair(e.x, e.y, s)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) moved = true
                if (dragging) moveCrosshair(e.x, e.y, s)
            }

            MotionEvent.ACTION_UP -> {
                dragging = false
                if (!moved) {
                    performClick()
                    listener?.onTap()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                // The system took the gesture over (e.g. the notification shade): put the crosshair back where it was.
                if (dragging) {
                    s.crossU = startU
                    s.crossV = startV
                    postInvalidateOnAnimation()
                }
                dragging = false
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
        val t = transform(s, bm)
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

    private companion object {
        const val EDGE_GUARD_DP = 20f
    }
}
