package com.exciteddev.flironepro

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import java.io.File
import java.io.FileOutputStream

/**
 * Diagnostic self-test, started from adb (debug builds): replays saved frames in demo mode, renders the whole UI into PNG
 * snapshots (portrait and landscape, every mode, settings panel, tool cards, fill mode), exercises calibration, video
 * recording and touch mapping, and writes the results to files/snapshots and the log (lines starting with "SELFTEST").
 */
internal class SelfTest(private val a: StreamActivity, private val lang: String?) {

    /** Prefix of the snapshot file names; a run in a specific language only takes the UI snapshots. */
    private val prefix = if (lang != null) "${lang}_" else ""
    private val quick = lang != null

    fun start() {
        Thread({ run() }, "flir-selftest").start()
    }

    private val dir: File get() = File(a.filesDir, "snapshots")

    private fun run() {
        try {
            DiagLog.log("SELFTEST: start")
            Thread.sleep(3500)
            dir.mkdirs()
            if (!quick) dir.listFiles()?.forEach { it.delete() }
            val s = a.settings

            // Portrait: every display mode, with the automatic orientation (screen rotation 0).
            a.forcedDisplayRotation = 0
            for (m in DisplayMode.entries) {
                a.onUiAndWait { s.mode = m; a.updateRotation() }
                Thread.sleep(900)
                snapshot(1080, 2160, "${prefix}portrait_${m.name}.png")
            }

            // The battery icon at several charge levels (the top bars of the snapshots stacked into one picture), then the
            // video button in its recording look.
            batterySheet()
            a.onUiAndWait { a.refreshRecordingUi(true) }
            Thread.sleep(200)
            snapshot(1080, 2160, "${prefix}portrait_recording_button.png")
            a.onUiAndWait { a.refreshRecordingUi() }

            // Settings panel and tool cards.
            a.onUiAndWait { s.mode = DisplayMode.MSX; a.testViewSize = Pair(1080, 2160); a.openSettingsForTest(true) }
            Thread.sleep(300)
            snapshot(1080, 2160, "${prefix}portrait_settings.png")

            // A tap on the palette's color strip (not on its radio button) must select that palette: real touch dispatch.
            var tapped = false
            a.onUiAndWait {
                val strip = findByTag(a.rootView, Palette.LAVA)
                if (strip != null) {
                    tap(strip)
                    tapped = true
                }
            }
            if (tapped) {
                Thread.sleep(300)
                DiagLog.log("SELFTEST: tap on the Lava color strip -> palette ${s.palette}")
                snapshot(1080, 2160, "${prefix}portrait_settings_strip_tap.png")
                a.onUiAndWait { s.palette = Palette.IRON; a.onSettingsChanged() }
            } else {
                DiagLog.log("SELFTEST: the Lava color strip was not found")
            }
            a.onUiAndWait {
                a.openSettingsForTest(false)
                a.testViewSize = Pair(1080, 3400)
                a.rootView.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(3400, View.MeasureSpec.EXACTLY))
                a.rootView.layout(0, 0, 1080, 3400)
                a.openSettingsForTest(true)
            }
            snapshot(1080, 3400, "${prefix}portrait_settings_full.png")
            a.onUiAndWait { a.openSettingsForTest(false) }
            a.onUiAndWait { s.palette = Palette.LAVA; a.onSettingsChanged() }
            Thread.sleep(900)
            snapshot(1080, 2160, "${prefix}portrait_settings_lava.png")
            a.onUiAndWait { s.palette = Palette.IRON; a.onSettingsChanged(); a.openSettingsForTest(false); a.openCalibrationCard() }
            Thread.sleep(500)
            snapshot(1080, 2160, "${prefix}portrait_calibration.png")
            a.onUiAndWait { a.openAlignmentCard() }
            Thread.sleep(300)
            snapshot(1080, 2160, "${prefix}portrait_alignment.png")
            a.onUiAndWait { a.hideCardsForTest() }

            // Fill mode in portrait.
            a.onUiAndWait { s.fillScreen = true }
            Thread.sleep(500)
            snapshot(1080, 2160, "${prefix}portrait_fill.png")
            a.onUiAndWait { s.fillScreen = false }

            // Landscape: the screen is rotated 90 degrees (counter-clockwise), the picture must stay upright.
            a.forcedDisplayRotation = 90
            a.onUiAndWait { a.updateRotation() }
            Thread.sleep(900)
            snapshot(2160, 1080, "${prefix}landscape_MSX.png")
            a.onUiAndWait { a.testViewSize = Pair(2160, 1080); a.openSettingsForTest(true) }
            Thread.sleep(300)
            snapshot(2160, 1080, "${prefix}landscape_settings.png")
            a.onUiAndWait { a.openSettingsForTest(false); s.fillScreen = true }
            Thread.sleep(600)
            snapshot(2160, 1080, "${prefix}landscape_fill.png")
            a.onUiAndWait { s.fillScreen = false }

            if (quick) {
                restoreLanguageAndFinish()
                return
            }

            // Performance: 4.5 s per mode, see the "Frame processing" lines in the log.
            a.forcedDisplayRotation = 0
            for (m in DisplayMode.entries) {
                a.onUiAndWait { s.mode = m; a.updateRotation() }
                Thread.sleep(4500)
            }
            a.onUiAndWait { s.mode = DisplayMode.MSX }
            Thread.sleep(600)

            // Calibration: add a reference under the crosshair, then reset.
            val sample = a.processor?.calibrationSample()
            if (sample != null) {
                var out = ""
                a.onUiAndWait { out = a.addCalibrationPoint(sample.tempC ?: 25.0, sample) }
                DiagLog.log("SELFTEST: calibration: $out")
                a.onUiAndWait { a.calibrationReset() }
            } else {
                DiagLog.log("SELFTEST: no calibration sample")
            }

            // Photo composite (to a private file, not the gallery) and video (to a private file).
            if (a.thermalView.currentFrame() != null) {
                val bm = a.processor?.renderSnapshot(960, 720)
                if (bm != null) FileOutputStream(File(dir, "photo.png")).use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            val rec = VideoRecorder(a.applicationContext, 640, 480, File(dir, "test.mp4"))
            if (rec.start()) {
                a.processor?.recorder = rec
                Thread.sleep(2500)
                a.processor?.recorder = null
                val name = rec.stop()
                DiagLog.log("SELFTEST: video: $name, frames=${rec.framesEncoded}, size=${File(dir, "test.mp4").length()}")
            } else {
                DiagLog.log("SELFTEST: video did not start")
            }

            // Touch mapping through a 90 degree rotation, on the device.
            a.onUiAndWait {
                s.rotation = 90
                val tv = a.thermalView
                tv.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.EXACTLY))
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

            // Restore the defaults so the test leaves nothing in the app settings.
            a.forcedDisplayRotation = null
            a.testViewSize = null
            a.onUiAndWait {
                s.mode = DisplayMode.MSX
                s.palette = Palette.IRON
                s.fillScreen = false
                s.crossU = 0.5f
                s.crossV = 0.5f
                s.calibrationPoints = emptyList()
                a.onSettingsChanged()
                s.save()
            }
            DiagLog.log("SELFTEST: finished")
            a.runOnUiThread { a.finish() }
        } catch (e: Exception) {
            DiagLog.log("SELFTEST: error $e")
        }
    }

    private fun restoreLanguageAndFinish() {
        a.forcedDisplayRotation = null
        a.testViewSize = null
        a.onUiAndWait {
            val s = a.settings
            s.mode = DisplayMode.MSX
            s.palette = Palette.IRON
            s.fillScreen = false
            s.crossU = 0.5f
            s.crossV = 0.5f
            a.onSettingsChanged()
            s.save()
        }
        // The language is deliberately not restored from here: changing it re-creates a finishing activity as a brand new one.
        DiagLog.log("SELFTEST: finished (language run '$lang')")
        a.runOnUiThread { a.finish() }
    }

    /** Feeds battery reports into the activity (as the camera does) and stacks the top bar of each state into one PNG. */
    private fun batterySheet() {
        val levels = listOf(100 to false, 75 to false, 50 to false, 25 to false, 15 to false, 5 to false, 60 to true)
        val rowH = 170
        val sheet = Bitmap.createBitmap(1080, rowH * levels.size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        for ((i, level) in levels.withIndex()) {
            val (pct, charging) = level
            a.onServiceMessage("{\"type\":\"batteryVoltageUpdate\",\"data\":{\"voltage\":3.7,\"percentage\":$pct}}")
            val state = if (charging) "Charging" else "NoCharging"
            a.onServiceMessage("{\"type\":\"batteryChargingStateUpdate\",\"data\":{\"chargingState\":\"$state\"}}")
            val bm = render(1080, 2160)
            canvas.drawBitmap(bm, Rect(0, 0, 1080, rowH), Rect(0, i * rowH, 1080, (i + 1) * rowH), null)
        }
        FileOutputStream(File(dir, "${prefix}battery_levels.png")).use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun findByTag(v: View, tag: Any): View? {
        if (v.tag == tag) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) findByTag(v.getChildAt(i), tag)?.let { return it }
        }
        return null
    }

    /** Taps the center of a view by dispatching a touch (down + up) through the whole view tree, like a finger would. */
    private fun tap(target: View) {
        val r = Rect(0, 0, target.width, target.height)
        a.rootView.offsetDescendantRectToMyCoords(target, r)
        val t = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(t, t, action, r.exactCenterX(), r.exactCenterY(), 0)
            a.rootView.dispatchTouchEvent(e)
            e.recycle()
        }
    }

    /** Renders the whole screen layout (picture, bars, panels) at the given size. */
    private fun render(w: Int, h: Int): Bitmap {
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        a.onUiAndWait {
            a.testViewSize = Pair(w, h)
            a.refreshForTest()
            a.rootView.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            a.rootView.layout(0, 0, w, h)
            a.rootView.draw(Canvas(bm))
        }
        return bm
    }

    /** Renders the whole screen layout (picture, bars, panels) at the given size into a PNG. */
    private fun snapshot(w: Int, h: Int, name: String) {
        val bm = render(w, h)
        FileOutputStream(File(dir, name)).use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
