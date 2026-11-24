package com.exciteddev.flironepro.radiometry

/**
 * Follows the camera's shutter calibration (FFC) through the frames: which state the camera reports, whether the shutter is
 * closed, and which frames have to be skipped because their thermal content is not valid. Knows nothing about Android.
 */
class FfcTracker {
    /** The calibration state last reported in a frame's status text (e.g. FFC_PROGRESS); null if none. */
    @Volatile var lastState: String? = null
        private set

    /** Time (elapsedRealtime) of the newest frame that showed a calibration under way (state PROGRESS or shutter closed); 0 = none. */
    @Volatile var lastActivityAt = 0L
        private set

    /** True while frames show the closed shutter. */
    @Volatile var shutterClosed = false
        private set

    private var dropNext = false

    /** What a status text said about the calibration state, and whether that differs from the previous one. */
    class Observation(val state: String?, val previous: String?) {
        val changed: Boolean get() = state != previous
    }

    fun observe(status: String, now: Long): Observation {
        val state = STATE.find(status)?.groupValues?.get(1)
        val observation = Observation(state, lastState)
        lastState = state
        if (state == STATE_PROGRESS) lastActivityAt = now
        return observation
    }

    /**
     * Follows the shutter and tells whether the thermal content of this frame may be used. It is not valid while the shutter is
     * closed, and not on the next frame either, because the sensor has not caught up yet.
     */
    fun contentAllowed(isFfc: Boolean, now: Long): Boolean {
        val allowed = !isFfc && !shutterClosed && !dropNext
        when {
            isFfc -> {
                shutterClosed = true
                lastActivityAt = now
            }

            shutterClosed -> {
                shutterClosed = false
                dropNext = true
            }

            dropNext -> dropNext = false
        }
        return allowed
    }

    /** True while the shutter is closed or a calibration has just started. */
    fun inProgress(now: Long): Boolean = shutterClosed || lastState == STATE_PROGRESS || now - lastActivityAt < PROGRESS_GRACE_MS

    /** True while the camera itself asks for a calibration. */
    fun wanted(): Boolean = lastState == STATE_DESIRED || lastState == STATE_VALID_IMG

    companion object {
        private val STATE = Regex("\"ffcState\"\\s*:\\s*\"([A-Za-z_]+)\"")
        const val STATE_PROGRESS = "FFC_PROGRESS"
        private const val STATE_DESIRED = "FFC_DESIRED"
        private const val STATE_VALID_IMG = "FFC_VALID_IMG"

        /** A calibration that began less than this long ago still counts as under way. */
        private const val PROGRESS_GRACE_MS = 800L
    }
}

/** Frames per second, measured over windows of about a second. */
class FpsMeter {
    @Volatile var fps = 0f
        private set

    private var windowStart = 0L
    private var frames = 0

    fun onFrame(now: Long) {
        if (windowStart == 0L) windowStart = now
        frames++
        if (now - windowStart >= WINDOW_MS) {
            fps = frames * MS_PER_SECOND / (now - windowStart)
            windowStart = now
            frames = 0
        }
    }

    private companion object {
        const val WINDOW_MS = 1000L
        const val MS_PER_SECOND = 1000f
    }
}
