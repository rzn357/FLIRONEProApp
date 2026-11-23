package com.exciteddev.flironepro.ui

import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.exciteddev.flironepro.R
import com.exciteddev.flironepro.debug.DiagLog
import com.exciteddev.flironepro.pipeline.FrameIntake
import com.exciteddev.flironepro.pipeline.StreamPipeline
import com.exciteddev.flironepro.radiometry.ShutterCalibration
import com.exciteddev.flironepro.settings.AppSettings
import com.exciteddev.flironepro.usb.FlirCamera
import com.exciteddev.flironepro.util.toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The manual shutter calibration (see [ShutterCalibration]): asks the camera to close its shutter and recalibrate now. The
 * command is not publicly documented, so the first time the app searches for it; once found it is remembered and used directly.
 * Also makes sure that the camera's automatic calibration is on.
 *
 * The work runs in the activity's coroutine scope: when the screen goes away the search and the pending checks are cancelled
 * with it (the search still switches the automatic calibration back on, see [ShutterCalibration.probe]), and nothing keeps a
 * reference to the destroyed activity.
 */
class FfcController(
    private val activity: AppCompatActivity,
    private val settings: AppSettings,
    private val pipeline: StreamPipeline,
    private val runMode: RunMode
) {
    /** The running search for the command, or null; cancelling it (the dialog's button, the screen) ends the search. */
    private var search: Job? = null

    private val searchRunning: Boolean get() = search?.isActive == true

    /** The session in which the automatic calibration has been ensured (once per camera session). */
    private var ensuredFor: FrameIntake? = null

    /** The camera, if it is connected and delivers frames (not in the demo). */
    private val connectedCamera: FlirCamera?
        get() = pipeline.camera?.takeIf { it.isRunning && pipeline.frameCount > 0 && !runMode.demo }

    private val idle: Boolean get() = !runMode.demo && !searchRunning

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
        activity.lifecycleScope.launch {
            val sent = withContext(Dispatchers.IO) { cam.sendJson(command) }
            DiagLog.log("[ffc] manual request $command -> $sent (camera state: ${pipeline.intake?.ffc?.lastState})")
            activity.toast(activity.getString(R.string.ffc_requested))
            delay(ShutterCalibration.RESPONSE_WINDOW_MS + REACTION_GRACE_MS)
            val lastActivity = pipeline.intake?.ffc?.lastActivityAt ?: 0L
            if (pipeline.camera === cam && lastActivity < t0) {
                DiagLog.log("[ffc] the camera did not start a calibration (state: ${pipeline.intake?.ffc?.lastState})")
                activity.toast(activity.getString(R.string.ffc_no_reaction))
            }
        }
    }

    private fun askToSearch(cam: FlirCamera) {
        MaterialAlertDialogBuilder(activity).setTitle(R.string.ffc_probe_title)
            .setMessage(R.string.ffc_probe_message)
            .setPositiveButton(R.string.btn_start) { _, _ -> startSearch(cam) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun startSearch(cam: FlirCamera) {
        DiagLog.log("[ffc] searching for the shutter-calibration command (${ShutterCalibration.CANDIDATES.size} candidates)")
        val progress = MaterialAlertDialogBuilder(activity).setTitle(R.string.ffc_probe_title)
            .setMessage(R.string.ffc_probe_running)
            .setCancelable(false)
            .setNegativeButton(R.string.btn_cancel) { _, _ -> search?.cancel() }
            .show()
        search = activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { runSearch(cam) }
                DiagLog.log("[ffc] search finished: ${result.outcome}, tried ${result.tried}, command ${result.command}")
                if (!result.cancelled && !activity.isFinishing) showResult(result)
            } finally {
                if (!activity.isDestroyed) progress.dismiss()
            }
        }
    }

    private suspend fun runSearch(cam: FlirCamera): ShutterCalibration.Result = ShutterCalibration.probe(
        candidates = ShutterCalibration.CANDIDATES,
        send = { cam.sendJson(it) },
        ffcStartedSince = { (pipeline.intake?.ffc?.lastActivityAt ?: 0L) >= it },
        ffcInProgress = { inProgress() },
        calibrationWanted = { pipeline.intake?.ffc?.wanted() == true },
        now = { SystemClock.elapsedRealtime() },
        sleep = { delay(it) },
        log = { DiagLog.log("[ffc] $it") },
        isCancelled = { pipeline.camera !== cam }
    )

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
            activity.lifecycleScope.launch(Dispatchers.IO) {
                DiagLog.log("[ffc] automatic calibration ensured: ${cam.sendJson(ShutterCalibration.AUTO_ON)}")
            }
        }
    }

    private companion object {
        /** How long after the camera's reaction window to check whether it reacted. */
        const val REACTION_GRACE_MS = 1000L
    }
}
