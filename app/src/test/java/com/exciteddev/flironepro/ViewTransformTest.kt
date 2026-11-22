package com.exciteddev.flironepro

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
        // верхній лівий кут вмісту -> правий верхній кут екрана
        assertEquals(400f, t.toViewX(0f, 0f), eps)
        assertEquals(0f, t.toViewY(0f, 0f), eps)
        // правий верхній кут вмісту -> правий нижній кут екрана
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
    fun modeCycleVisitsAllModes() {
        var m = DisplayMode.MSX
        val seen = HashSet<DisplayMode>()
        repeat(3) { seen.add(m); m = m.next() }
        assertEquals(DisplayMode.entries.toSet(), seen)
        assertEquals(DisplayMode.MSX, m)
    }
}
