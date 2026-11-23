package com.exciteddev.flironepro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import java.util.concurrent.Executors

/** One camera frame, already parsed on the USB thread (cheap work), handed over for the heavy processing. */
class FrameSample(
    val frame: FlirFrame,
    val raw: IntArray?,
    /** true if the thermal content may be shown (the shutter is not closed for calibration). */
    val valid: Boolean,
    val fpaK: Double?
)

/** Averaged reading under the crosshair for calibration: S' (without the user offset) and the shutter's Φ of the anchor. */
class CalSample(val relative: Double, val phiShutter: Double, val tempC: Double?)

/** The last few readings under the crosshair, so a reference point can be added from their average. */
class CalibrationRing {
    private val ring = ArrayDeque<CalSample>()

    fun add(sample: CalSample) {
        synchronized(ring) {
            ring.addLast(sample)
            while (ring.size > CAPACITY) ring.removeFirst()
        }
    }

    /** The average of the stored readings, or null if there are none. */
    fun average(): CalSample? = synchronized(ring) {
        if (ring.isEmpty()) {
            null
        } else {
            CalSample(
                relative = ring.map { it.relative }.average(),
                phiShutter = ring.map { it.phiShutter }.average(),
                tempC = ring.mapNotNull { it.tempC }.takeIf { it.isNotEmpty() }?.average()
            )
        }
    }

    private companion object {
        const val CAPACITY = 10
    }
}

/** The span of raw values that is mapped onto the palette. It follows the scene smoothly, so the colors do not flicker. */
class ColorRange {
    private var lo = 0.0
    private var hi = 0.0
    private var initialized = false

    fun update(newLo: Double, newHi: Double) {
        if (initialized) {
            lo += SMOOTHING * (newLo - lo)
            hi += SMOOTHING * (newHi - hi)
        } else {
            lo = newLo
            hi = newHi
            initialized = true
        }
    }

    /** The range as (low, high), widened to a minimum span so that noise is not amplified on a flat scene. */
    fun widened(): Pair<Double, Double> {
        if (hi - lo >= MIN_SPAN) return Pair(lo, hi)
        val mid = (hi + lo) / 2
        return Pair(mid - MIN_SPAN / 2, mid + MIN_SPAN / 2)
    }

    private companion object {
        const val SMOOTHING = 0.3
        const val MIN_SPAN = 80.0
    }
}

/** The palette's 256 colors, rebuilt only when the palette changes. */
private class LutCache {
    private var palette: Palette? = null
    private var lut = IntArray(0)

    fun get(p: Palette): IntArray {
        if (palette != p) {
            lut = Palettes.lut(p)
            palette = p
        }
        return lut
    }
}

/** Position of the center of pixel [i] of a row (or column) of [size] pixels, as a fraction of the whole row. */
private fun pixelCenter(i: Int, size: Int): Float = (i + PIXEL_CENTER) / size

private const val PIXEL_CENTER = 0.5f

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
    texts: UiTexts
) : Thread("flir-render") {

    @Volatile var recorder: VideoRecorder? = null

    @Volatile var onPhotoSaved: ((String?) -> Unit)? = null

    /** The readings under the crosshair of the last frames (for the temperature calibration). */
    val calibration = CalibrationRing()

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
    private val range = ColorRange()

    private val argb = IntArray(ImageComposer.OUT_W * ImageComposer.OUT_H)
    private val scratch = ImageComposer.Scratch()
    private var visBitmap: Bitmap? = null
    private var visLuma = IntArray(0)
    private var visPixels = IntArray(0)
    private val decodeOpts = BitmapFactory.Options().apply { inSampleSize = 2 }
    private val decodeOptsFull = BitmapFactory.Options().apply { inSampleSize = 1 }

    private val renderer = PictureRenderer(settings, texts)
    private val luts = LutCache()
    private val saver = Executors.newSingleThreadExecutor()

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
        runCatching { join(JOIN_MS) }
        saver.shutdown()
    }

    @Suppress("TooGenericExceptionCaught") // whatever goes wrong with one frame must not stop the render thread
    override fun run() {
        while (running) {
            val s = synchronized(lock) {
                while (running && latest == null) lock.wait(WAIT_MS)
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
            if (++timeCount == REPORT_EVERY) {
                DiagLog.log("Frame processing (mode ${settings.mode}): average ${timeSum / timeCount} ms, max $timeMax ms")
                timeSum = 0
                timeMax = 0
                timeCount = 0
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
            range.update(ex.minValue.toDouble(), ex.maxValue.toDouble())
            haveThermal = true
        }

        if (mode != DisplayMode.THERMAL) decodeVisible(s.frame)

        val (lo, hi) = range.widened()
        val content: Bitmap? = when (mode) {
            DisplayMode.VISIBLE -> visBitmap
            else -> if (haveThermal) buildThermalBitmap(mode, lo, hi) else null
        }

        val overlay = buildOverlay(mode, lo, hi, s)
        if (content != null) {
            view.submit(content, overlay)
            recorder?.let { rec -> rec.encode { canvas -> renderer.draw(canvas, rec.width, rec.height, content, overlay) } }
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
        ImageComposer.thermalToArgb(thermalRaw, lo, hi, luts.get(settings.palette), argb)
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
                fun at(x: Int, y: Int, value: Int) =
                    Reading(pixelCenter(x, ThermalImage.W), pixelCenter(y, ThermalImage.H), radiometry.celsius(value.toDouble(), fpaK))
                hot = at(ex.maxX, ex.maxY, ex.maxValue)
                cold = at(ex.minX, ex.minY, ex.minValue)
            }
            loC = radiometry.celsius(lo, fpaK)
            hiC = radiometry.celsius(hi, fpaK)
        }
        return OverlayState(
            mode = mode,
            lut = luts.get(settings.palette),
            cross = cross,
            hot = hot,
            cold = cold,
            scaleLoC = loC,
            scaleHiC = hiC,
            waitingForAnchor = radiometry.effectiveAnchor(fpaK) == null,
            ffcActive = !s.valid,
            emissivity = radiometry.emissivity
        )
    }

    private fun recordCalSample(avgRaw: Double, tempC: Double?) {
        val rel = radiometry.relative(avgRaw, fpaK)
        val phi = radiometry.shutterPhi(fpaK)
        if (rel != null && phi != null) calibration.add(CalSample(rel, phi, tempC))
    }

    private fun savePhoto(content: Bitmap, overlay: OverlayState) {
        val sideways = Orientation.isSideways(settings.rotation)
        val bm = renderer.render(if (sideways) PHOTO_H else PHOTO_W, if (sideways) PHOTO_W else PHOTO_H, content, overlay)
        saver.execute {
            val name = MediaSaver.savePhoto(context, bm)
            DiagLog.log("Photo saved: $name")
            onPhotoSaved?.invoke(name)
        }
    }

    /** Renders the current picture into a bitmap (diagnostic snapshots). */
    fun renderSnapshot(w: Int, h: Int): Bitmap? = view.currentFrame()?.let { (bitmap, overlay) -> renderer.render(w, h, bitmap, overlay) }

    private companion object {
        const val PHOTO_W = 960
        const val PHOTO_H = 720
        const val JOIN_MS = 2000L
        const val WAIT_MS = 200L
        const val REPORT_EVERY = 60
    }
}
