package com.exciteddev.flironepro.pipeline

import android.graphics.Bitmap
import com.exciteddev.flironepro.render.OverlayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetachableSinkTest {
    private class Counting : PictureSink {
        var cleared = 0

        override fun submit(bm: Bitmap, o: OverlayState) = Unit

        override fun currentFrame(): Pair<Bitmap, OverlayState>? = null

        override fun clear() {
            cleared++
        }
    }

    @Test
    fun withoutATargetNothingHappens() {
        val sink = DetachableSink()
        sink.clear()
        assertNull(sink.currentFrame())
    }

    @Test
    fun forwardsToTheAttachedSinkOnly() {
        val sink = DetachableSink()
        val first = Counting()
        val second = Counting()
        sink.attach(first)
        sink.clear()
        sink.attach(second)
        sink.clear()
        assertEquals(1, first.cleared)
        assertEquals(1, second.cleared)
    }

    @Test
    fun anOldSinkCannotDetachTheNewOne() {
        val sink = DetachableSink()
        val old = Counting()
        val fresh = Counting()
        sink.attach(old)
        sink.attach(fresh)
        sink.detach(old)
        sink.clear()
        assertEquals(1, fresh.cleared)
        sink.detach(fresh)
        sink.clear()
        assertEquals(1, fresh.cleared)
    }
}
