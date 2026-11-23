package com.exciteddev.flironepro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint

/**
 * Draws the composed picture with its overlays on a canvas of any size: for photos, videos and snapshots, which always show the
 * whole frame. It uses the same [ViewTransform] as the screen, so the marks sit exactly where they are on the screen.
 */
class PictureRenderer(private val settings: AppSettings, private val texts: UiTexts) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val painters = HashMap<Int, OverlayPainter>()

    /** Draws on [canvas] of size w x h. Can be called from several threads. */
    fun draw(canvas: Canvas, w: Int, h: Int, content: Bitmap, overlay: OverlayState) {
        canvas.drawColor(Color.BLACK)
        val t = ViewTransform(w.toFloat(), h.toFloat(), content.width.toFloat() / content.height, settings.rotation, settings.mirror)
        val src = FloatArray(ViewTransform.CORNER_VALUES)
        val dst = FloatArray(ViewTransform.CORNER_VALUES)
        ViewTransform.sourceCorners(content.width.toFloat(), content.height.toFloat(), src)
        t.destinationCorners(dst)
        val m = Matrix()
        m.setPolyToPoly(src, 0, dst, 0, ViewTransform.CORNER_COUNT)
        canvas.drawBitmap(content, m, paint)
        val painter = synchronized(painters) { painters.getOrPut(w) { OverlayPainter(w / REFERENCE_WIDTH, texts) } }
        painter.draw(
            canvas, t, overlay, settings.crossU, settings.crossV, settings.alignment,
            settings.showMarkers, settings.showScale, null
        )
    }

    /** The picture as a new bitmap of size w x h. */
    fun render(w: Int, h: Int, content: Bitmap, overlay: OverlayState): Bitmap {
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(bm), w, h, content, overlay)
        return bm
    }

    private companion object {
        /** The overlays are designed for a picture 360 pixels wide and scaled for other sizes. */
        const val REFERENCE_WIDTH = 360f
    }
}
