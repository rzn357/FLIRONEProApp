package com.exciteddev.flironepro.pipeline

import android.graphics.Bitmap
import com.exciteddev.flironepro.render.OverlayState

/** Where the pipeline puts the finished picture. The screen's `ThermalView` is one; the pipeline does not know about the UI. */
interface PictureSink {
    /** Can be called from any thread; frames that are not shown in time are dropped. */
    fun submit(bm: Bitmap, o: OverlayState)

    /** The picture shown now with its overlay state (used for snapshots), or null when there is none. */
    fun currentFrame(): Pair<Bitmap, OverlayState>?

    /** Forgets the last picture (the camera went away). */
    fun clear()
}
