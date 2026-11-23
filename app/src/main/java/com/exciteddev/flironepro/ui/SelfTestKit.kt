package com.exciteddev.flironepro.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch

/** The tools of the self-test: running on the UI thread, rendering the screen into PNG files, and touching views. */
internal class SelfTestKit(private val a: StreamActivity, private val prefix: String) {
    val dir: File get() = File(a.filesDir, "snapshots")

    /** Runs [block] on the UI thread and waits until it is done. */
    fun onUiAndWait(block: () -> Unit) {
        val latch = CountDownLatch(1)
        a.runOnUiThread {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }

    /** Measures and lays out the whole screen at the given size (the way a real window of that size would). */
    fun layoutRoot(w: Int, h: Int) {
        a.views.rootView.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        a.views.rootView.layout(0, 0, w, h)
    }

    /** Renders the whole screen layout (picture, bars, panels) at the given size. */
    private fun render(w: Int, h: Int): Bitmap {
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        onUiAndWait {
            a.geometry.testViewSize = Pair(w, h)
            a.hud.updateTopBar()
            a.hud.updateHud()
            layoutRoot(w, h)
            a.views.rootView.draw(Canvas(bm))
        }
        return bm
    }

    /** Renders the whole screen layout (picture, bars, panels) at the given size into the PNG [name] (with the language prefix). */
    fun snapshot(w: Int, h: Int, name: String) {
        val bm = render(w, h)
        FileOutputStream(File(dir, prefix + name)).use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Feeds battery reports into the screen (as the camera does) and stacks the top bar of each state into one PNG. */
    fun batterySheet() {
        val levels = listOf(100 to false, 75 to false, 50 to false, 25 to false, 15 to false, 5 to false, 60 to true)
        val rowH = 170
        val sheet = Bitmap.createBitmap(1080, rowH * levels.size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        for ((i, level) in levels.withIndex()) {
            val (pct, charging) = level
            a.pipeline.onServiceMessage("{\"type\":\"batteryVoltageUpdate\",\"data\":{\"voltage\":3.7,\"percentage\":$pct}}")
            val state = if (charging) "Charging" else "NoCharging"
            a.pipeline.onServiceMessage("{\"type\":\"batteryChargingStateUpdate\",\"data\":{\"chargingState\":\"$state\"}}")
            val bm = render(1080, 2160)
            canvas.drawBitmap(bm, Rect(0, 0, 1080, rowH), Rect(0, i * rowH, 1080, (i + 1) * rowH), null)
        }
        FileOutputStream(File(dir, "${prefix}battery_levels.png")).use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The first view in the tree below [v] (or [v] itself) whose tag is [tag]. */
    fun findByTag(v: View, tag: Any): View? {
        val children = (v as? ViewGroup)?.let { group -> (0 until group.childCount).map { group.getChildAt(it) } }.orEmpty()
        return v.takeIf { it.tag == tag } ?: children.firstNotNullOfOrNull { findByTag(it, tag) }
    }

    /** Taps the center of a view by dispatching a touch (down + up) through the whole view tree, like a finger would. */
    fun tap(target: View) {
        val r = Rect(0, 0, target.width, target.height)
        a.views.rootView.offsetDescendantRectToMyCoords(target, r)
        val t = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(t, t, action, r.exactCenterX(), r.exactCenterY(), 0)
            a.views.rootView.dispatchTouchEvent(e)
            e.recycle()
        }
    }
}
