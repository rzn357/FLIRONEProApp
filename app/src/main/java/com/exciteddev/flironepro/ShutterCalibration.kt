package com.exciteddev.flironepro

/**
 * Manual shutter calibration (FFC, "tuning" in FLIR's SDK) of the camera.
 *
 * FLIR does not publish the camera's JSON command for it (the SDK only has a `performTuning()` call), so the app has to find
 * the command on the camera itself. Findings from the real camera that shape the search:
 *  - the camera calibrates itself every ~2 minutes (frame status "ffcState": VALID_RAD -> VALID_IMG -> DESIRED -> PROGRESS)
 *    and ignores a calibration command while it considers itself calibrated;
 *  - `setOption` with `autoFFC` is understood (the camera answers `setOptionStatus`) and is the SDK's "automatic tuning" switch.
 *
 * So the search switches automatic calibration off, waits until the camera asks for a calibration (VALID_IMG / DESIRED) and
 * then tries the candidate commands one by one, watching whether the shutter starts. Because the camera cannot calibrate by
 * itself meanwhile, a reaction is caused by the command. Automatic calibration is always switched back on at the end.
 * Everything is injected, so the logic is unit-tested without a camera.
 */
object ShutterCalibration {
    /** How long to wait for the shutter to react to a command. */
    const val RESPONSE_WINDOW_MS = 2500L

    const val AUTO_OFF = """{"type":"setOption","data":{"option":"autoFFC","value":false}}"""
    const val AUTO_ON = """{"type":"setOption","data":{"option":"autoFFC","value":true}}"""

    private val TYPE_NAMES = listOf(
        "performTuning", "tuning", "tune", "ffc", "performFfc", "performFFC", "triggerFfc", "runFfc", "startFfc", "doFfc",
        "ffcRequest", "requestFfc", "calibrate", "calibrateShutter", "nuc", "tuningRequest", "requestTuning", "startTuning",
        "runTuning", "doTuning", "manualFfc", "manualTuning"
    )

    /** Candidate commands, most likely first. Replies to unknown commands are ignored by the camera. */
    val CANDIDATES: List<String> =
        TYPE_NAMES.map { """{"type":"$it","data":{}}""" } +
            listOf("performTuning", "tuning", "ffc").map { """{"type":"$it"}""" } +
            listOf("performTuning", "tuning", "ffc").map { """{"type":"$it","data":{"value":true}}""" } +
            listOf("tuning", "performTuning", "tune", "ffc", "manualFFC", "ffcNow").map {
                """{"type":"setOption","data":{"option":"$it","value":true}}"""
            }

    enum class Outcome {
        /** A command made the shutter start. */
        FOUND,

        /** The camera asked for a calibration but ignored every candidate. */
        NOT_FOUND,

        /** The camera did not ask for a calibration in time. */
        NEVER_REQUESTED,

        /** The camera kept calibrating by itself although automatic calibration was switched off. */
        AUTO_NOT_STOPPED,

        /** The camera was busy with a calibration the whole time. */
        CAMERA_BUSY,

        /** The first command could not be written to the camera. */
        COULD_NOT_SEND,

        CANCELLED
    }

    class Result(val command: String?, val tried: Int, val outcome: Outcome) {
        val cancelled: Boolean get() = outcome == Outcome.CANCELLED
    }

    /**
     * @param send sends a command, true if it was written to the camera
     * @param ffcStartedSince true if a shutter calibration started at or after the given time (ms)
     * @param ffcInProgress true while the shutter is closed or a calibration is just running
     * @param calibrationWanted true while the camera itself asks for a calibration (VALID_IMG / DESIRED)
     */
    fun probe(
        candidates: List<String>,
        send: (String) -> Boolean,
        ffcStartedSince: (Long) -> Boolean,
        ffcInProgress: () -> Boolean,
        calibrationWanted: () -> Boolean,
        now: () -> Long,
        sleep: (Long) -> Unit,
        log: (String) -> Unit,
        isCancelled: () -> Boolean = { false }
    ): Result {
        fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
            val end = now() + timeoutMs
            while (now() < end) {
                if (condition()) return true
                sleep(POLL_MS)
            }
            return condition()
        }

        if (!waitFor(BUSY_WAIT_MS) { !ffcInProgress() }) {
            log("the camera is busy with a calibration, giving up")
            return Result(null, 0, Outcome.CAMERA_BUSY)
        }
        if (!send(AUTO_OFF)) {
            log("could not switch the automatic calibration off")
            return Result(null, 0, Outcome.COULD_NOT_SEND)
        }
        val tOff = now()
        log("automatic calibration switched off; waiting for the camera to ask for a calibration")
        try {
            // 1. Wait until the camera asks for a calibration.
            var tWanted = -1L
            val deadline = tOff + WAIT_WANTED_MS
            while (now() < deadline) {
                if (isCancelled()) return Result(null, 0, Outcome.CANCELLED)
                if (calibrationWanted()) {
                    tWanted = now()
                    break
                }
                if (now() > tOff + SETTLE_MS && ffcStartedSince(tOff + SETTLE_MS)) {
                    log("the camera calibrated by itself although automatic calibration is off")
                    return Result(null, 0, Outcome.AUTO_NOT_STOPPED)
                }
                sleep(POLL_MS)
            }
            if (tWanted < 0) {
                log("the camera did not ask for a calibration within ${WAIT_WANTED_MS / 1000} s")
                return Result(null, 0, Outcome.NEVER_REQUESTED)
            }

            // 2. It has to wait for us now; if it starts by itself, a reaction to a command would prove nothing.
            log("the camera asks for a calibration; checking that it waits for a command")
            if (waitFor(HOLD_MS) { ffcStartedSince(tWanted) }) {
                log("the camera started the calibration by itself")
                return Result(null, 0, Outcome.AUTO_NOT_STOPPED)
            }

            // 3. Try the candidates.
            var tried = 0
            for (cmd in candidates) {
                if (isCancelled()) return Result(null, tried, Outcome.CANCELLED)
                tried++
                val t0 = now()
                if (!send(cmd)) {
                    log("could not send $cmd")
                    continue
                }
                log("sent $cmd")
                if (waitFor(RESPONSE_WINDOW_MS) { ffcStartedSince(t0) }) {
                    log("the shutter reacted to $cmd")
                    return Result(cmd, tried, Outcome.FOUND)
                }
            }
            return Result(null, tried, Outcome.NOT_FOUND)
        } finally {
            waitFor(RESTORE_WAIT_MS) { !ffcInProgress() }
            log("automatic calibration switched back on: ${send(AUTO_ON)}")
        }
    }

    private const val POLL_MS = 100L
    private const val BUSY_WAIT_MS = 10_000L

    /** Longer than the camera's own calibration period (~116 s). */
    private const val WAIT_WANTED_MS = 200_000L
    private const val SETTLE_MS = 1_500L
    private const val HOLD_MS = 3_000L
    private const val RESTORE_WAIT_MS = 5_000L
}
