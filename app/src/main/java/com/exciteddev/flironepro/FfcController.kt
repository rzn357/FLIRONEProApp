package com.exciteddev.flironepro

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The manual shutter calibration (see [ShutterCalibration]): asks the camera to close its shutter and recalibrate now. The
 * command is not publicly documented, so the first time the app searches for it; once found it is remembered and used directly.
 * Also makes sure that the camera's automatic calibration is on.
 */
class FfcController(
    private val activity: AppCompatActivity,
    private val settings: AppSettings,
    private val pipeline: StreamPipeline,
    private val runMode: RunMode
) {
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var searchRunning = false

    @Volatile private var searchCancelled = false

    /** The session in which the automatic calibration has been ensured (once per camera session). */
    private var ensuredFor: FrameIntake? = null

    /** The camera, if it is connected and delivers frames (not in the demo). */
    private val connectedCamera: FlirCamera?
        get() = pipeline.camera?.takeIf { it.isRunning && pipeline.frameCount > 0 && !runMode.demo }

    private val idle: Boolean get() = !runMode.demo && !searchRunning

    fun cancelSearch() {
        searchCancelled = true
    }

    /** Called from the settings: calibrates now (searching for the command first if it is not known yet). */
    fun request() {
        val cam = connectedCamera
        val known = settings.ffcCommand
        when {
            cam == null -> activity.toast(activity.getString(R.string.ffc_not_connected))
            searchRunning || inProgress() -> activity.toast(activity.getString(R.string.ffc_busy))
            known != null -> requestKnownCommand(cam, known)
            else -> askToSearch(cam)
        }
    }

    private fun inProgress(): Boolean = pipeline.intake?.ffc?.inProgress(SystemClock.elapsedRealtime()) == true

    private fun requestKnownCommand(cam: FlirCamera, command: String) {
        val t0 = SystemClock.elapsedRealtime()
        val sent = cam.sendJson(command)
        DiagLog.log("[ffc] manual request $command -> $sent (camera state: ${pipeline.intake?.ffc?.lastState})")
        activity.toast(activity.getString(R.string.ffc_requested))
        ui.postDelayed({
            val lastActivity = pipeline.intake?.ffc?.lastActivityAt ?: 0L
            if (pipeline.camera === cam && lastActivity < t0) {
                DiagLog.log("[ffc] the camera did not start a calibration (state: ${pipeline.intake?.ffc?.lastState})")
                activity.toast(activity.getString(R.string.ffc_no_reaction))
            }
        }, ShutterCalibration.RESPONSE_WINDOW_MS + REACTION_GRACE_MS)
    }

    private fun askToSearch(cam: FlirCamera) {
        MaterialAlertDialogBuilder(activity).setTitle(R.string.ffc_probe_title)
            .setMessage(R.string.ffc_probe_message)
            .setPositiveButton(R.string.btn_start) { _, _ -> startSearch(cam) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun startSearch(cam: FlirCamera) {
        searchCancelled = false
        searchRunning = true
        DiagLog.log("[ffc] searching for the shutter-calibration command (${ShutterCalibration.CANDIDATES.size} candidates)")
        val progress = MaterialAlertDialogBuilder(activity).setTitle(R.string.ffc_probe_title)
            .setMessage(R.string.ffc_probe_running)
            .setCancelable(false)
            .setNegativeButton(R.string.btn_cancel) { _, _ -> searchCancelled = true }
            .show()
        Thread({
            val result = runSearch(cam)
            DiagLog.log("[ffc] search finished: ${result.outcome}, tried ${result.tried}, command ${result.command}")
            activity.runOnUiThread {
                if (!activity.isDestroyed) progress.dismiss()
                if (!result.cancelled && !activity.isFinishing && !activity.isDestroyed) showResult(result)
            }
        }, "flir-ffc-probe").start()
    }

    private fun runSearch(cam: FlirCamera): ShutterCalibration.Result = try {
        ShutterCalibration.probe(
            candidates = ShutterCalibration.CANDIDATES,
            send = { cam.sendJson(it) },
            ffcStartedSince = { (pipeline.intake?.ffc?.lastActivityAt ?: 0L) >= it },
            ffcInProgress = { inProgress() },
            calibrationWanted = { pipeline.intake?.ffc?.wanted() == true },
            now = { SystemClock.elapsedRealtime() },
            sleep = { Thread.sleep(it) },
            log = { DiagLog.log("[ffc] $it") },
            isCancelled = { searchCancelled || pipeline.camera !== cam }
        )
    } finally {
        searchRunning = false
    }

    private fun showResult(result: ShutterCalibration.Result) {
        val found = result.command
        if (found != null) {
            settings.ffcCommand = found
            settings.save()
        }
        val text = when (result.outcome) {
            ShutterCalibration.Outcome.FOUND -> activity.getString(R.string.ffc_probe_found_fmt, found)
            ShutterCalibration.Outcome.NOT_FOUND -> activity.getString(R.string.ffc_result_not_found_fmt, result.tried)
            ShutterCalibration.Outcome.NEVER_REQUESTED -> activity.getString(R.string.ffc_result_never)
            ShutterCalibration.Outcome.AUTO_NOT_STOPPED -> activity.getString(R.string.ffc_result_auto)
            ShutterCalibration.Outcome.CAMERA_BUSY -> activity.getString(R.string.ffc_busy)
            ShutterCalibration.Outcome.COULD_NOT_SEND -> activity.getString(R.string.ffc_result_send)
            ShutterCalibration.Outcome.CANCELLED -> ""
        }
        MaterialAlertDialogBuilder(activity).setTitle(R.string.ffc_probe_title).setMessage(text)
            .setPositiveButton(R.string.btn_ok, null).show()
    }

    /**
     * A search that was cut short (the app was closed in the middle, the cable was pulled) may have left the camera's automatic
     * calibration switched off, so it is switched on once for every camera session.
     */
    fun ensureAutoTuning() {
        val cam = pipeline.camera?.takeIf { it.isRunning }
        val session = pipeline.intake?.takeIf { it.frameCount > 0 && it !== ensuredFor }
        if (cam != null && session != null && idle) {
            ensuredFor = session
            Thread({
                DiagLog.log("[ffc] automatic calibration ensured: ${cam.sendJson(ShutterCalibration.AUTO_ON)}")
            }, "flir-auto-on").start()
        }
    }

    private companion object {
        /** How long after the camera's reaction window to check whether it reacted. */
        const val REACTION_GRACE_MS = 1000L
    }
}
