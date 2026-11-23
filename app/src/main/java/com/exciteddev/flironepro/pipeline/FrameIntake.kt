package com.exciteddev.flironepro.pipeline

import android.os.SystemClock
import com.exciteddev.flironepro.debug.DiagLog
import com.exciteddev.flironepro.debug.FrameStatsLog
import com.exciteddev.flironepro.protocol.FlirFrame
import com.exciteddev.flironepro.protocol.ThermalTelemetry
import com.exciteddev.flironepro.radiometry.FfcTracker
import com.exciteddev.flironepro.radiometry.FpsMeter
import com.exciteddev.flironepro.radiometry.Radiometry
import com.exciteddev.flironepro.radiometry.ThermalImage
import com.exciteddev.flironepro.radiometry.ThermalStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What happens to every frame on the thread that delivers it (USB or demo): the cheap decoding, the shutter-calibration
 * bookkeeping, the radiometric anchor and the statistics log. The heavy work is left to the [FrameProcessor].
 * One instance serves one camera (or demo) session.
 *
 * @param statsLog where the per-frame statistics go (null: none, as in the demo)
 * @param live false for the demo: its frames neither feed the temperature model nor the statistics
 * @param fixedFpaK the sensor temperature to use instead of the one in the telemetry (the demo's recorded value)
 */
class FrameIntake(
    private val radiometry: Radiometry,
    private val processor: FrameProcessor,
    private val statsLog: FrameStatsLog?,
    private val live: Boolean,
    private val fixedFpaK: Double?
) {
    val ffc = FfcTracker()
    private val fpsMeter = FpsMeter()
    private val wallClock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile var frameCount = 0
        private set

    val fps: Float get() = fpsMeter.fps

    fun handle(frame: FlirFrame) {
        frameCount++
        val now = SystemClock.elapsedRealtime()
        fpsMeter.onFrame(now)

        val raw = ThermalImage.decode(frame, IntArray(ThermalImage.W * ThermalImage.H))
        val stats = raw?.let { ThermalImage.stats(it) }
        val status = frame.statusText(STATUS_CHARS)
        val ffcState = observeCalibrationState(status, now)
        val shutterK = SHUTTER.find(status)?.groupValues?.get(1)?.toDoubleOrNull()
        val telemetry = ThermalTelemetry.read(frame)
        // Lepton telemetry word 24: the sensor temperature in centikelvin.
        val fpaK = fixedFpaK ?: telemetry?.let { it[TELEMETRY_FPA_WORD] / CENTIKELVIN_PER_KELVIN }

        if (stats != null && live) {
            radiometry.onFrame(frame.isFfc, stats, shutterK, fpaK)
            logStats(frame, stats, ffcState, shutterK, telemetry, fpaK)
        }
        if (frameCount == 1 || frameCount % LOG_EVERY == 0) {
            val rate = String.format(Locale.US, "%.1f", fps)
            DiagLog.log("Frame #$frameCount: thermal=${frame.thermalSize} jpg=${frame.jpgSize} fps=$rate ffc=${frame.isFfc}")
        }

        // The thermal content is not updated during the shutter calibration and on the next frame.
        val valid = ffc.contentAllowed(frame.isFfc, now) && raw != null
        processor.submit(FrameSample(frame, raw, valid, fpaK))
    }

    /** Notes the calibration state in the status text (and logs a change); returns it. */
    private fun observeCalibrationState(status: String, now: Long): String? {
        val seen = ffc.observe(status, now)
        if (seen.changed) {
            DiagLog.log("[ffc] state ${seen.previous ?: "-"} -> ${seen.state ?: "-"}; status: ${status.take(STATUS_LOG_CHARS)}")
        }
        return seen.state
    }

    /** One CSV row: raw frame statistics, shutter temperature, Lepton telemetry words 20..31 and the computed temperatures. */
    private fun logStats(frame: FlirFrame, s: ThermalStats, ffcState: String?, shutterK: Double?, telemetry: IntArray?, fpaK: Double?) {
        val words = TELEMETRY_WORDS_LOGGED.joinToString(",") { telemetry?.get(it)?.toString() ?: "" }

        fun celsius(raw: Int) = fpaK?.let { radiometry.celsius(raw, it) }?.let { String.format(Locale.US, "%.2f", it) } ?: ""
        val anchorRaw = radiometry.anchor?.raw?.let { String.format(Locale.US, "%.0f", it) } ?: ""
        statsLog?.log(
            "${wallClock.format(Date())},$frameCount,${if (frame.isFfc) 1 else 0},${ffcState ?: ""},${shutterK ?: ""}," +
                "${s.min},${s.max},${s.mean},${s.center},$words," +
                "${celsius(s.center)},${celsius(s.min)},${celsius(s.max)},$anchorRaw"
        )
    }

    private companion object {
        val SHUTTER = Regex("\"shutterTemperature\"\\s*:\\s*([0-9.]+)")
        const val STATUS_CHARS = 400
        const val STATUS_LOG_CHARS = 300
        const val LOG_EVERY = 100
        const val TELEMETRY_FPA_WORD = 24
        const val CENTIKELVIN_PER_KELVIN = 100.0
        val TELEMETRY_WORDS_LOGGED = 20..31
    }
}
