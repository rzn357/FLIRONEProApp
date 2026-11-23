package com.exciteddev.flironepro.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewTransformTest {
    private val eps = 1e-3f

    @Test
    fun roundTripForAllRotationsAndMirror() {
        for (rot in intArrayOf(0, 90, 180, 270)) {
            for (mirror in booleanArrayOf(false, true)) {
                val t = ViewTransform(1080f, 1500f, 4f / 3f, rot, mirror)
                for (u in floatArrayOf(0f, 0.2f, 0.5f, 0.9f, 1f)) {
                    for (v in floatArrayOf(0f, 0.3f, 0.5f, 0.8f, 1f)) {
                        val (cu, cv) = t.toContent(t.toViewX(u, v), t.toViewY(u, v))
                        assertEquals("rot=$rot mirror=$mirror", u, cu, eps)
                        assertEquals("rot=$rot mirror=$mirror", v, cv, eps)
                    }
                }
            }
        }
    }

    @Test
    fun noRotationFitsWidthInPortraitView() {
        val t = ViewTransform(1080f, 1500f, 4f / 3f, 0, false)
        assertEquals(1080f, t.destW, 0.01f)
        assertEquals(810f, t.destH, 0.01f)
        assertEquals(345f, t.destTop, 0.01f)
        assertEquals(0f, t.destLeft, 0.01f)
    }

    @Test
    fun rotationBy90UsesPortraitAspect() {
        val t = ViewTransform(1080f, 1500f, 4f / 3f, 90, false)
        assertEquals(0.75f, t.destW / t.destH, 0.001f)
        assertTrue(t.destH <= 1500.01f && t.destW <= 1080.01f)
    }

    @Test
    fun rotationBy90Clockwise() {
        val t = ViewTransform(400f, 400f, 1f, 90, false)
        // top-left corner of the content -> top-right corner of the screen
        assertEquals(400f, t.toViewX(0f, 0f), eps)
        assertEquals(0f, t.toViewY(0f, 0f), eps)
        // top-right corner of the content -> bottom-right corner of the screen
        assertEquals(400f, t.toViewX(1f, 0f), eps)
        assertEquals(400f, t.toViewY(1f, 0f), eps)
    }

    @Test
    fun rotationBy180() {
        val t = ViewTransform(400f, 300f, 4f / 3f, 180, false)
        assertEquals(400f, t.toViewX(0f, 0f), eps)
        assertEquals(300f, t.toViewY(0f, 0f), eps)
    }

    @Test
    fun mirrorFlipsHorizontally() {
        val t = ViewTransform(400f, 300f, 4f / 3f, 0, true)
        assertEquals(400f, t.toViewX(0f, 0f), eps)
        assertEquals(0f, t.toViewX(1f, 0f), eps)
        assertEquals(0f, t.toViewY(1f, 0f), eps)
    }

    @Test
    fun alignmentRoundTrip() {
        val a = Alignment(0.834f, 0.063f, -0.02f)
        for (u in floatArrayOf(0f, 0.3f, 1f)) {
            assertEquals(u, a.visibleToThermalU(a.thermalToVisibleU(u)), eps)
            assertEquals(u, a.visibleToThermalV(a.thermalToVisibleV(u)), eps)
        }
    }

    @Test
    fun defaultAlignmentCoversCentralPartOfVisibleFrame() {
        val a = Alignment()
        assertTrue(a.thermalToVisibleU(0f) > 0f && a.thermalToVisibleU(1f) < 1f)
    }

    @Test
    fun threeDisplayModesExist() {
        assertEquals(setOf(DisplayMode.MSX, DisplayMode.THERMAL, DisplayMode.VISIBLE), DisplayMode.entries.toSet())
    }

    @Test
    fun autoOrientationKeepsPictureUprightInAnyScreenRotation() {
        // natural portrait: the sensor image is rotated 90 degrees clockwise
        assertEquals(90, Orientation.effective(true, 0, 0))
        // landscape (screen rotated 90 degrees counter-clockwise): no rotation is needed
        assertEquals(0, Orientation.effective(true, 0, 90))
        assertEquals(270, Orientation.effective(true, 0, 180))
        assertEquals(180, Orientation.effective(true, 0, 270))
        // the manual correction is added
        assertEquals(180, Orientation.effective(true, 90, 0))
        // manual mode ignores the screen rotation
        assertEquals(90, Orientation.effective(false, 90, 270))
        assertEquals(0, Orientation.effective(false, 0, 90))
    }

    @Test
    fun coverFillsTheViewAndCropsTheEdges() {
        // portrait phone, image rotated by 90 degrees -> 3:4 picture
        val fit = ViewTransform(1080f, 2160f, 4f / 3f, 90, false, cover = false)
        assertEquals(1080f, fit.destW, 0.01f)
        assertEquals(1440f, fit.destH, 0.01f)
        val cover = ViewTransform(1080f, 2160f, 4f / 3f, 90, false, cover = true)
        assertEquals(2160f, cover.destH, 0.01f)
        assertEquals(1620f, cover.destW, 0.01f)
        assertEquals(-270f, cover.destLeft, 0.01f)
        // the visible part is limited to the view
        assertEquals(0f, cover.visLeft, 0.01f)
        assertEquals(1080f, cover.visRight, 0.01f)
        assertEquals(2160f, cover.visBottom, 0.01f)
        // a screen point maps back into the content, and the round trip is exact
        val (u, v) = cover.toContent(540f, 1080f)
        assertEquals(540f, cover.toViewX(u, v), 0.01f)
        assertEquals(1080f, cover.toViewY(u, v), 0.01f)
    }

    @Test
    fun fitKeepsTheWholePictureVisible() {
        val t = ViewTransform(2160f, 1080f, 4f / 3f, 0, false, cover = false)
        assertEquals(1080f, t.destH, 0.01f)
        assertEquals(1440f, t.destW, 0.01f)
        assertEquals(360f, t.visLeft, 0.01f)
        assertEquals(1800f, t.visRight, 0.01f)
        assertTrue(t.isOnScreen(10f, 10f))
        assertTrue(!t.isOnScreen(-5f, 10f))
    }

    @Test
    fun pictureContainsOnlyItsOwnPixels() {
        val t = ViewTransform(1080f, 1500f, 4f / 3f, 0, false) // 1080 x 810 picture centered vertically
        assertTrue(t.containsPoint(540f, 750f))
        assertTrue(t.containsPoint(1f, 346f))
        assertTrue(!t.containsPoint(540f, 100f)) // the bar above the picture
        assertTrue(!t.containsPoint(540f, 1400f)) // the bar below it
    }

    @Test
    fun dragsDoNotStartAtTheScreenEdgesOrOutsideThePicture() {
        // fill mode: the picture covers the whole view, so only the edge strips are excluded
        val fill = ViewTransform(1080f, 1500f, 4f / 3f, 0, false, true)
        assertTrue(fill.canStartDragAt(540f, 750f, 60f))
        assertTrue(!fill.canStartDragAt(540f, 10f, 60f)) // pulling the notification shade down from the top
        assertTrue(!fill.canStartDragAt(540f, 1490f, 60f))
        assertTrue(!fill.canStartDragAt(10f, 750f, 60f))
        assertTrue(!fill.canStartDragAt(1075f, 750f, 60f))
        // fit mode: the bars above and below the picture are not ours either
        val fit = ViewTransform(1080f, 1500f, 4f / 3f, 0, false)
        assertTrue(fit.canStartDragAt(540f, 750f, 60f))
        assertTrue(!fit.canStartDragAt(540f, 200f, 60f))
    }
}
