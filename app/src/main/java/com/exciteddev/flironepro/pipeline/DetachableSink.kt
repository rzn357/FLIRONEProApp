package com.exciteddev.flironepro.pipeline

import android.graphics.Bitmap
import com.exciteddev.flironepro.render.OverlayState

/**
 * The [PictureSink] that the render thread always talks to. The real sink (the screen's view) comes and goes with the screen,
 * while the pipeline stays; while there is none, the pictures are dropped. The newest picture is kept, so that a screen that
 * is created again shows it at once instead of an empty view until the next frame arrives.
 */
class DetachableSink : PictureSink {
    @Volatile private var target: PictureSink? = null

    @Volatile private var last: Pair<Bitmap, OverlayState>? = null

    fun attach(sink: PictureSink) {
        target = sink
        last?.let { (bm, overlay) -> sink.submit(bm, overlay) }
    }

    /** Lets go of [sink]; does nothing if another sink has taken over in the meantime. */
    fun detach(sink: PictureSink) {
        if (target === sink) target = null
    }

    override fun submit(bm: Bitmap, o: OverlayState) {
        last = Pair(bm, o)
        target?.submit(bm, o)
    }

    override fun currentFrame(): Pair<Bitmap, OverlayState>? = target?.currentFrame()

    override fun clear() {
        last = null
        target?.clear()
    }
}
