package com.exciteddev.flironepro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.SystemClock
import java.util.concurrent.Executors

/** One camera frame, already parsed on the USB thread (cheap work), handed over for the heavy processing. */
class FrameSample(
    val frame: FlirFrame,
    val raw: IntArray?,
    /** true if the thermal content may be shown (the shutter is not closed for calibration). */
    val valid: Boolean,
    val fpaK: Double?,
)

/** Averaged reading under the crosshair for calibration: S' (without the user offset) and the shutter's Φ of the anchor. */
class CalSample(val relative: Double, val phiShutter: Double, val tempC: Double?)

/**
 * Render thread: takes the newest frame, composes the picture for the current mode (thermal / visible / MSX), computes the
 * temperatures under the crosshair and at the markers, hands everything to the screen and, if requested, to the video
 * encoder or a photo. If it cannot keep up it skips stale frames, so USB reading is not affected.
 */
class FrameProcessor(
    private val context: Context,
    private val settings: AppSettings,
    private val radiometry: Radiometry,
    private val view: ThermalView,
    private val texts: UiTexts,
) : Thread("flir-render") {

    @Volatile var recorder: VideoRecorder? = null
    @Volatile var onPhotoSaved: ((String?) -> Unit)? = null
    @Volatile private var photoRequested = false

    private val lock = Object()
    private var latest: FrameSample? = null
    @Volatile private var running = true

    // Processing state (this thread only).
    private val thermalRaw = IntArray(ThermalImage.W * ThermalImage.H)
    private val smoothed = IntArray(ThermalImage.W * ThermalImage.H)
    private var haveThermal = false
    private var fpaK = 0.0
    private var extremes: Extremes? = null
    private var rangeLo = 0.0
    private var rangeHi = 0.0
    private var rangeInit = false

    private val argb = IntArray(ImageComposer.OUT_W * ImageComposer.OUT_H)
    private val scratch = ImageComposer.Scratch()
    private var visBitmap: Bitmap? = null
    private var visLuma = IntArray(0)
    private var visPixels = IntArray(0)
    private val decodeOpts = BitmapFactory.Options().apply { inSampleSize = 2 }
    private val decodeOptsFull = BitmapFactory.Options().apply { inSampleSize = 1 }

    private val calRing = ArrayDeque<CalSample>()
    private val capturePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val saver = Executors.newSingleThreadExecutor()
    private val painters = HashMap<Int, OverlayPainter>()

    private var timeSum = 0L
    private var timeMax = 0L
    private var timeCount = 0

    fun submit(s: FrameSample) {
        synchronized(lock) {
            latest = s
            lock.notifyAll()
        }
    }

    fun requestPhoto() {
        photoRequested = true
    }

    fun shutdown() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        runCatching { join(2000) }
        saver.shutdown()
    }

    override fun run() {
        while (running) {
            val s = synchronized(lock) {
                while (running && latest == null) lock.wait(200)
                val x = latest
                latest = null
                x
            } ?: continue
            val t0 = SystemClock.elapsedRealtime()
            try {
                process(s)
            } catch (e: Exception) {
                DiagLog.log("Frame processing error: $e")
            }
            val dt = SystemClock.elapsedRealtime() - t0
            timeSum += dt
            if (dt > timeMax) timeMax = dt
            if (++timeCount == 60) {
                DiagLog.log("Frame processing (mode ${settings.mode}): average ${timeSum / timeCount} ms, max $timeMax ms")
                timeSum = 0; timeMax = 0; timeCount = 0
            }
        }
    }

    private fun process(s: FrameSample) {
        val mode = settings.mode

        if (s.valid && s.raw != null) {
            System.arraycopy(s.raw, 0, thermalRaw, 0, thermalRaw.size)
            s.fpaK?.let { fpaK = it }
            ThermalImage.smooth3(thermalRaw, smoothed)
            val ex = ThermalImage.extremes(smoothed)
            extremes = ex
            val lo = ex.minValue.toDouble()
            val hi = ex.maxValue.toDouble()
            if (!rangeInit) {
                rangeLo = lo; rangeHi = hi; rangeInit = true
            } else {
                rangeLo += 0.3 * (lo - rangeLo)
                rangeHi += 0.3 * (hi - rangeHi)
            }
            haveThermal = true
        }

        if (mode != DisplayMode.THERMAL) decodeVisible(s.frame)

        // Minimum span of the range, so noise is not amplified on a flat scene.
        var lo = rangeLo
        var hi = rangeHi
        if (hi - lo < MIN_SPAN) {
            val mid = (hi + lo) / 2
            lo = mid - MIN_SPAN / 2
            hi = mid + MIN_SPAN / 2
        }

        val content: Bitmap? = when (mode) {
            DisplayMode.VISIBLE -> visBitmap
            else -> if (haveThermal) buildThermalBitmap(mode, lo, hi) else null
        }

        val overlay = buildOverlay(mode, lo, hi, s)
        if (content != null) {
            view.submit(content, overlay)
            recorder?.let { rec -> rec.encode { canvas -> drawComposite(canvas, rec.width, rec.height, content, overlay) } }
            if (photoRequested) {
                photoRequested = false
                savePhoto(content, overlay)
            }
        }
    }

    /**
     * MSX needs the visible frame at half resolution (speed); in the visible-only mode with "fill the screen" the picture is
     * enlarged the most, so it is decoded at full resolution to keep it as sharp as possible.
     */
    private fun decodeVisible(frame: FlirFrame) {
        val jpg = frame.jpegBytes() ?: return
        val full = settings.mode == DisplayMode.VISIBLE && settings.fillScreen
        val bm = BitmapFactory.decodeByteArray(jpg, 0, jpg.size, if (full) decodeOptsFull else decodeOpts) ?: return
        visBitmap = bm
    }

    private fun buildThermalBitmap(mode: DisplayMode, lo: Double, hi: Double): Bitmap {
        ImageComposer.thermalToArgb(thermalRaw, lo, hi, cachedLut(settings.palette), argb)
        val vis = visBitmap
        if (mode == DisplayMode.MSX && vis != null) {
            val n = vis.width * vis.height
            if (visPixels.size != n) {
                visPixels = IntArray(n)
                visLuma = IntArray(n)
            }
            vis.getPixels(visPixels, 0, vis.width, 0, 0, vis.width, vis.height)
            ImageComposer.lumaFromArgb(visPixels, n, visLuma)
            ImageComposer.msx(argb, visLuma, vis.width, vis.height, settings.alignment, settings.msxGain, scratch)
        }
        return Bitmap.createBitmap(argb, ImageComposer.OUT_W, ImageComposer.OUT_H, Bitmap.Config.ARGB_8888)
    }

    private var lutPalette: Palette? = null
    private var lutCache: IntArray = IntArray(256)
    private fun cachedLut(p: Palette): IntArray {
        if (lutPalette != p) {
            lutCache = Palettes.lut(p)
            lutPalette = p
        }
        return lutCache
    }

    private fun buildOverlay(mode: DisplayMode, lo: Double, hi: Double, s: FrameSample): OverlayState {
        val crossU = settings.crossU
        val crossV = settings.crossV
        var cross: Reading? = null
        var hot: Reading? = null
        var cold: Reading? = null
        var loC: Double? = null
        var hiC: Double? = null
        if (haveThermal) {
            val avg = ThermalImage.sample3(thermalRaw, crossU, crossV)
            val crossT = radiometry.celsius(avg, fpaK)
            cross = Reading(crossU, crossV, crossT)
            recordCalSample(avg, crossT)
            extremes?.let { ex ->
                hot = Reading((ex.maxX + 0.5f) / ThermalImage.W, (ex.maxY + 0.5f) / ThermalImage.H, radiometry.celsius(ex.maxValue.toDouble(), fpaK))
                cold = Reading((ex.minX + 0.5f) / ThermalImage.W, (ex.minY + 0.5f) / ThermalImage.H, radiometry.celsius(ex.minValue.toDouble(), fpaK))
            }
            loC = radiometry.celsius(lo, fpaK)
            hiC = radiometry.celsius(hi, fpaK)
        }
        return OverlayState(
            mode = mode,
            lut = cachedLut(settings.palette),
            cross = cross,
            hot = hot,
            cold = cold,
            scaleLoC = loC,
            scaleHiC = hiC,
            waitingForAnchor = radiometry.effectiveAnchor(fpaK) == null,
            ffcActive = !s.valid,
            emissivity = radiometry.emissivity,
        )
    }

    private fun recordCalSample(avgRaw: Double, tempC: Double?) {
        val rel = radiometry.relative(avgRaw, fpaK) ?: return
        val phi = radiometry.shutterPhi(fpaK) ?: return
        synchronized(calRing) {
            calRing.addLast(CalSample(rel, phi, tempC))
            while (calRing.size > RING) calRing.removeFirst()
        }
    }

    /** Reading under the crosshair averaged over the last frames (for adding a reference point). */
    fun calibrationSample(): CalSample? = synchronized(calRing) {
        if (calRing.isEmpty()) return null
        CalSample(
            relative = calRing.map { it.relative }.average(),
            phiShutter = calRing.map { it.phiShutter }.average(),
            tempC = calRing.mapNotNull { it.tempC }.takeIf { it.isNotEmpty() }?.average(),
        )
    }

    /** Draws the picture and the overlays on a canvas of the given size (for photos and videos; always the whole frame). */
    private fun drawComposite(canvas: Canvas, w: Int, h: Int, content: Bitmap, overlay: OverlayState) {
        canvas.drawColor(Color.BLACK)
        val t = ViewTransform(w.toFloat(), h.toFloat(), content.width.toFloat() / content.height, settings.rotation, settings.mirror)
        val src = floatArrayOf(0f, 0f, content.width.toFloat(), 0f, content.width.toFloat(), content.height.toFloat(), 0f, content.height.toFloat())
        val corners = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f), floatArrayOf(1f, 1f), floatArrayOf(0f, 1f))
        val dst = FloatArray(8)
        for (i in 0 until 4) {
            dst[2 * i] = t.toViewX(corners[i][0], corners[i][1])
            dst[2 * i + 1] = t.toViewY(corners[i][0], corners[i][1])
        }
        val m = Matrix()
        m.setPolyToPoly(src, 0, dst, 0, 4)
        canvas.drawBitmap(content, m, capturePaint)
        val painter = synchronized(painters) { painters.getOrPut(w) { OverlayPainter(w / 360f, texts) } }
        painter.draw(canvas, t, overlay, settings.crossU, settings.crossV, settings.alignment, settings.showMarkers, settings.showScale, null)
    }

    private fun savePhoto(content: Bitmap, overlay: OverlayState) {
        val rotated = settings.rotation % 180 != 0
        val w = if (rotated) PHOTO_H else PHOTO_W
        val h = if (rotated) PHOTO_W else PHOTO_H
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawComposite(Canvas(bm), w, h, content, overlay)
        saver.execute {
            val name = MediaSaver.savePhoto(context, bm)
            DiagLog.log("Photo saved: $name")
            onPhotoSaved?.invoke(name)
        }
    }

    /** Renders the current picture into a bitmap (diagnostic snapshots). */
    fun renderSnapshot(w: Int, h: Int): Bitmap? {
        val vf = view.currentFrame() ?: return null
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawComposite(Canvas(bm), w, h, vf.first, vf.second)
        return bm
    }

    private companion object {
        const val MIN_SPAN = 80.0
        const val RING = 10
        const val PHOTO_W = 960
        const val PHOTO_H = 720
    }
}
