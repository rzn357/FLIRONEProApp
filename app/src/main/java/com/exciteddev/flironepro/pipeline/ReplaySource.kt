package com.exciteddev.flironepro.pipeline

/**
 * A source of frames other than the camera. The pipeline starts it, feeds the frames it delivers through the same path as the
 * camera's, and stops it together with the stream. Only the debug build has one (the demo player, see `src/debug`).
 */
interface ReplaySource {
    fun start()

    fun shutdown()
}
