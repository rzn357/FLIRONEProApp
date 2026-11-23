package com.exciteddev.flironepro.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.exciteddev.flironepro.R
import com.exciteddev.flironepro.debug.DemoSource
import com.exciteddev.flironepro.debug.DiagLog
import com.exciteddev.flironepro.pipeline.FrameStore
import com.exciteddev.flironepro.pipeline.StreamPipeline
import com.exciteddev.flironepro.radiometry.Radiometry

/** Starts the demo: instead of the camera, the pipeline replays the frames saved earlier, so the whole screen works without one. */
internal class DemoPlayer(
    private val context: Context,
    private val pipeline: StreamPipeline,
    private val runMode: RunMode,
    private val status: StreamStatus
) {
    private val ui = Handler(Looper.getMainLooper())

    fun start() {
        // The self-test feeds frames faster (20/s) to check the performance headroom.
        val frameMs = if (runMode.selfTest) SELF_TEST_FRAME_MS else DemoSource.FRAME_MS
        val src = pipeline.startReplay(DEMO_FPA_K, DEMO_ANCHOR) { onFrame ->
            DiagLog.log("=== Starting the demo ===")
            DemoSource(FrameStore.dir(context), frameMs, onFrame)
        } ?: return
        status.say(R.string.msg_demo_running)
        ui.postDelayed({
            if (pipeline.replay === src && src.frameCount == 0 && pipeline.frameCount == 0) status.say(R.string.msg_demo_no_frames)
        }, NO_DEMO_FRAMES_AFTER_MS)
    }

    private companion object {
        /** The demo's anchor and sensor temperature, taken from verified measurements (room / freezer). */
        val DEMO_ANCHOR = Radiometry.Anchor(3845.0, 304.92, 305.21)
        const val DEMO_FPA_K = 305.4
        const val SELF_TEST_FRAME_MS = 50L
        const val NO_DEMO_FRAMES_AFTER_MS = 1500L
    }
}
