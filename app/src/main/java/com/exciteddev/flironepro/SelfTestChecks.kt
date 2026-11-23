package com.exciteddev.flironepro

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.io.File
import java.io.FileOutputStream

/** The self-test steps that are not screenshots: performance, calibration, photo and video, touch mapping. */
internal class SelfTestChecks(private val a: StreamActivity, private val kit: SelfTestKit) {

    fun runAll() {
        performance()
        calibration()
        photoAndVideo()
        touchMapping()
        restoreDefaults()
    }

    /** 4.5 s per mode, see the "Frame processing" lines in the log. */
    private fun performance() {
        a.geometry.forcedDisplayRotation = 0
        for (m in DisplayMode.entries) {
            kit.onUiAndWait {
                a.settings.mode = m
                a.geometry.updateRotation()
            }
            Thread.sleep(4500)
        }
        kit.onUiAndWait { a.settings.mode = DisplayMode.MSX }
        Thread.sleep(600)
    }

    /** Adds a reference under the crosshair, then resets. */
    private fun calibration() {
        val sample = a.pipeline.processor?.calibration?.average()
        if (sample != null) {
            var out = ""
            kit.onUiAndWait { out = a.cards.addCalibrationPoint(sample.tempC ?: 25.0, sample) }
            DiagLog.log("SELFTEST: calibration: $out")
            kit.onUiAndWait { a.cards.reset() }
        } else {
            DiagLog.log("SELFTEST: no calibration sample")
        }
    }

    /** Photo composite (to a private file, not the gallery) and video (to a private file). */
    private fun photoAndVideo() {
        val processor = a.pipeline.processor
        if (a.views.thermalView.currentFrame() != null) {
            val bm = processor?.renderSnapshot(960, 720)
            if (bm != null) FileOutputStream(File(kit.dir, "photo.png")).use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val rec = VideoRecorder(a.applicationContext, 640, 480, File(kit.dir, "test.mp4"))
        if (rec.start()) {
            processor?.recorder = rec
            Thread.sleep(2500)
            processor?.recorder = null
            val name = rec.stop()
            DiagLog.log("SELFTEST: video: $name, frames=${rec.framesEncoded}, size=${File(kit.dir, "test.mp4").length()}")
        } else {
            DiagLog.log("SELFTEST: video did not start")
        }
    }

    /** Touch mapping through a 90 degree rotation, on the device. */
    private fun touchMapping() {
        kit.onUiAndWait {
            val s = a.settings
            s.rotation = 90
            val tv = a.views.thermalView
            tv.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.EXACTLY)
            )
            tv.layout(0, 0, 1080, 1500)
            val tr = ViewTransform(1080f, 1500f, 4f / 3f, 90, false)
            val x = tr.toViewX(0.25f, 0.75f)
            val y = tr.toViewY(0.25f, 0.75f)
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
            tv.onTouchEvent(down)
            down.recycle()
            DiagLog.log("SELFTEST: touch (0.25,0.75) through a 90 degree rotation -> crosshair (${s.crossU}, ${s.crossV})")
        }
    }

    /** Restores the defaults so the test leaves nothing in the app settings. */
    private fun restoreDefaults() {
        a.geometry.forcedDisplayRotation = null
        a.geometry.testViewSize = null
        kit.onUiAndWait {
            val s = a.settings
            s.mode = DisplayMode.MSX
            s.palette = Palette.IRON
            s.fillScreen = false
            s.crossU = 0.5f
            s.crossV = 0.5f
            s.calibrationPoints = emptyList()
            a.onSettingsChanged()
            s.save()
        }
    }
}
