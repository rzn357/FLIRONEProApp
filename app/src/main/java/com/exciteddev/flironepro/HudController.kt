package com.exciteddev.flironepro

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity

/**
 * What the screen says about the stream: the frame rate and battery in the top pill, the video timer, and the notes and
 * messages that the picture view draws over itself.
 *
 * @param overlayOpen tells whether a panel or card covers the picture (then the notes are hidden)
 */
class HudController(
    private val activity: AppCompatActivity,
    private val views: StreamViews,
    private val pipeline: StreamPipeline,
    private val recording: RecordingController,
    private val runMode: RunMode,
    private val overlayOpen: () -> Boolean
) {
    private val ui = Handler(Looper.getMainLooper())
    private val batteryDrawable = BatteryDrawable()
    private var message: String? = null

    init {
        views.batteryIcon.setImageDrawable(batteryDrawable)
    }

    /** Frame rate and battery in the top-right pill (shown only while frames are coming). */
    fun updateTopBar() {
        val streaming = pipeline.processor != null && pipeline.frameCount > 0
        views.statusPill.visibility = if (streaming) View.VISIBLE else View.INVISIBLE
        val battery = pipeline.battery
        val percentage = battery.percentage
        if (percentage != null) {
            views.batteryIcon.visibility = View.VISIBLE
            views.batteryText.visibility = View.VISIBLE
            batteryDrawable.update(percentage, battery.charging == true)
            views.batteryText.text = activity.getString(R.string.battery_fmt, percentage)
        } else {
            views.batteryIcon.visibility = View.GONE
            views.batteryText.visibility = View.GONE
        }
        val fps = pipeline.intake?.fps ?: 0f
        views.fpsText.text = activity.getString(R.string.fps_fmt, activity.format1(fps.toDouble()))
        recording.updateTimer()
    }

    /** Message shown in the middle of the empty screen (no picture yet). */
    fun showMessage(resId: Int) {
        message = activity.getString(resId)
        ui.post { updateHud() }
    }

    fun updateHud() {
        views.thermalView.hud = Hud(message, runMode.demo, showNotes = !overlayOpen())
    }
}
