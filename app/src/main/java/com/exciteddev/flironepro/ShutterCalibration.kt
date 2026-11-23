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
    ): Result = Search(send, ffcStartedSince, ffcInProgress, calibrationWanted, now, sleep, log, isCancelled).run(candidates)

    /** How the wait for the camera's request ended: it asked at [at], or the search is over with [result]. */
    private class Wait(val at: Long = -1, val result: Result? = null)

    /** One run of the search; everything it needs from the outside is injected. */
    private class Search(
        val send: (String) -> Boolean,
        val ffcStartedSince: (Long) -> Boolean,
        val ffcInProgress: () -> Boolean,
        val calibrationWanted: () -> Boolean,
        val now: () -> Long,
        val sleep: (Long) -> Unit,
        val log: (String) -> Unit,
        val isCancelled: () -> Boolean
    ) {
        fun run(candidates: List<String>): Result = precheck() ?: searchWithAutomaticCalibrationOff(candidates)

        /** The search can only start when the camera is idle and accepts the command that switches its automatic calibration off. */
        private fun precheck(): Result? = when {
            !waitFor(BUSY_WAIT_MS) { !ffcInProgress() } -> {
                log("the camera is busy with a calibration, giving up")
                Result(null, 0, Outcome.CAMERA_BUSY)
            }

            !send(AUTO_OFF) -> {
                log("could not switch the automatic calibration off")
                Result(null, 0, Outcome.COULD_NOT_SEND)
            }

            else -> null
        }

        private fun searchWithAutomaticCalibrationOff(candidates: List<String>): Result {
            val tOff = now()
            log("automatic calibration switched off; waiting for the camera to ask for a calibration")
            return try {
                // 1. Wait until the camera asks for a calibration, 2. check that it waits for us, 3. try the candidates.
                val wait = waitUntilWanted(tOff)
                wait.result ?: heldByCamera(wait.at) ?: tryCandidates(candidates)
            } finally {
                waitFor(RESTORE_WAIT_MS) { !ffcInProgress() }
                log("automatic calibration switched back on: ${send(AUTO_ON)}")
            }
        }

        private fun waitUntilWanted(tOff: Long): Wait {
            val deadline = tOff + WAIT_WANTED_MS
            var wait: Wait? = null
            while (wait == null && now() < deadline) wait = pollWanted(tOff)
            return wait ?: Wait(result = neverRequested())
        }

        /** One look at the camera; null if nothing happened yet (after a short sleep). */
        private fun pollWanted(tOff: Long): Wait? {
            val wait = when {
                isCancelled() -> Wait(result = Result(null, 0, Outcome.CANCELLED))

                calibrationWanted() -> Wait(at = now())

                now() > tOff + SETTLE_MS && ffcStartedSince(tOff + SETTLE_MS) -> {
                    log("the camera calibrated by itself although automatic calibration is off")
                    Wait(result = Result(null, 0, Outcome.AUTO_NOT_STOPPED))
                }

                else -> null
            }
            if (wait == null) sleep(POLL_MS)
            return wait
        }

        private fun neverRequested(): Result {
            log("the camera did not ask for a calibration within ${WAIT_WANTED_MS / MS_PER_SECOND} s")
            return Result(null, 0, Outcome.NEVER_REQUESTED)
        }

        /** It has to wait for us now; if it starts by itself, a reaction to a command would prove nothing. */
        private fun heldByCamera(tWanted: Long): Result? {
            log("the camera asks for a calibration; checking that it waits for a command")
            val startedByItself = waitFor(HOLD_MS) { ffcStartedSince(tWanted) }
            if (startedByItself) log("the camera started the calibration by itself")
            return if (startedByItself) Result(null, 0, Outcome.AUTO_NOT_STOPPED) else null
        }

        private fun tryCandidates(candidates: List<String>): Result {
            var tried = 0
            var found: String? = null
            for (cmd in candidates) {
                if (isCancelled()) return Result(null, tried, Outcome.CANCELLED)
                tried++
                if (tryCommand(cmd)) {
                    found = cmd
                    break
                }
            }
            return Result(found, tried, if (found != null) Outcome.FOUND else Outcome.NOT_FOUND)
        }

        /** Sends a candidate and watches whether the shutter reacts. */
        private fun tryCommand(cmd: String): Boolean {
            val t0 = now()
            val sent = send(cmd)
            log(if (sent) "sent $cmd" else "could not send $cmd")
            val reacted = sent && waitFor(RESPONSE_WINDOW_MS) { ffcStartedSince(t0) }
            if (reacted) log("the shutter reacted to $cmd")
            return reacted
        }

        private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
            val end = now() + timeoutMs
            while (now() < end) {
                if (condition()) return true
                sleep(POLL_MS)
            }
            return condition()
        }
    }

    private const val POLL_MS = 100L
    private const val BUSY_WAIT_MS = 10_000L
    private const val MS_PER_SECOND = 1000

    /** Longer than the camera's own calibration period (~116 s). */
    private const val WAIT_WANTED_MS = 200_000L
    private const val SETTLE_MS = 1_500L
    private const val HOLD_MS = 3_000L
    private const val RESTORE_WAIT_MS = 5_000L
}
