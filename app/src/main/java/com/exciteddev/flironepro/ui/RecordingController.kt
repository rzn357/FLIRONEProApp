package com.exciteddev.flironepro.ui

import android.media.MediaActionSound
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.exciteddev.flironepro.R
import com.exciteddev.flironepro.media.VideoRecorder
import com.exciteddev.flironepro.pipeline.FrameStore
import com.exciteddev.flironepro.pipeline.StreamPipeline
import com.exciteddev.flironepro.render.Orientation
import com.exciteddev.flironepro.settings.AppSettings
import com.exciteddev.flironepro.util.toast
import java.util.Locale

/** Taking photos, recording video and saving raw frames; the video button shows the recording state. */
class RecordingController(
    private val activity: AppCompatActivity,
    private val views: StreamViews,
    private val settings: AppSettings,
    private val pipeline: StreamPipeline
) {
    private val sound = MediaActionSound().apply {
        load(MediaActionSound.SHUTTER_CLICK)
        load(MediaActionSound.START_VIDEO_RECORDING)
        load(MediaActionSound.STOP_VIDEO_RECORDING)
    }

    var recorder: VideoRecorder? = null
        private set

    init {
        pipeline.onPhotoSaved = { name ->
            activity.runOnUiThread { activity.toast(savedOrFailed(name, R.string.toast_photo_saved_fmt, R.string.toast_photo_failed)) }
        }
    }

    /** Connects the photo and the video button. */
    fun bind() {
        activity.findViewById<View>(R.id.btnPhoto).setOnClickListener { takePhoto() }
        views.btnVideo.setOnClickListener { toggleRecording() }
    }

    private fun takePhoto() {
        val processor = pipeline.processor
        if (processor == null || views.thermalView.currentFrame() == null) {
            activity.toast(activity.getString(R.string.toast_no_image))
        } else {
            sound.play(MediaActionSound.SHUTTER_CLICK)
            processor.requestPhoto()
        }
    }

    fun toggleRecording() {
        if (recorder != null) stopRecording(wait = false) else startRecording()
    }

    private fun startRecording() {
        val processor = pipeline.processor
        if (processor == null || views.thermalView.currentFrame() == null) {
            activity.toast(activity.getString(R.string.toast_no_image))
            return
        }
        val sideways = Orientation.isSideways(settings.rotation)
        val rec = VideoRecorder(activity.applicationContext, if (sideways) VIDEO_H else VIDEO_W, if (sideways) VIDEO_W else VIDEO_H)
        if (!rec.start()) {
            activity.toast(activity.getString(R.string.toast_video_start_failed))
            return
        }
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
        recorder = rec
        processor.recorder = rec
        refreshUi()
    }

    /** Ends the recording; [wait] = finish the file right here instead of on a background thread. */
    fun stopRecording(wait: Boolean) {
        val rec = recorder ?: return
        recorder = null
        pipeline.processor?.recorder = null
        sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        val finish = {
            val name = rec.stop()
            activity.runOnUiThread { activity.toast(savedOrFailed(name, R.string.toast_video_saved_fmt, R.string.toast_video_failed)) }
        }
        if (wait) finish() else Thread(finish, "flir-rec-stop").start()
        refreshUi()
    }

    /** The video button turns red with a stop icon and a timer while recording. */
    fun refreshUi(recording: Boolean = recorder != null) {
        views.btnVideo.background =
            ContextCompat.getDrawable(activity, if (recording) R.drawable.bg_round_button_rec else R.drawable.bg_round_button)
        views.videoIcon.setImageResource(if (recording) R.drawable.ic_stop else R.drawable.ic_videocam)
        views.videoTimer.visibility = if (recording) View.VISIBLE else View.GONE
        if (recording) views.videoTimer.text = TIMER_ZERO
        views.btnVideo.contentDescription = activity.getString(if (recording) R.string.cd_video_stop else R.string.cd_video)
    }

    /** Shows the length of the running recording on the video button. */
    fun updateTimer() {
        val rec = recorder?.takeIf { it.isRecording } ?: return
        val seconds = rec.elapsedMs() / MS_PER_SECOND
        views.videoTimer.text = String.format(Locale.US, "%d:%02d", seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
    }

    /** Saves the newest frame as it came from the camera (for analysis), and says so. */
    fun saveRawFrame() {
        val frame = pipeline.lastFrame
        val name = if (frame != null) FrameStore.save(activity, frame, "manual") else null
        activity.toast(savedOrFailed(name, R.string.toast_raw_saved_fmt, R.string.toast_raw_failed))
    }

    /** "Saved as <name>" or, if there is no name, the failure message. */
    private fun savedOrFailed(name: String?, savedFormat: Int, failed: Int): String =
        if (name != null) activity.getString(savedFormat, name) else activity.getString(failed)

    fun release() {
        sound.release()
    }

    private companion object {
        const val VIDEO_W = 640
        const val VIDEO_H = 480
        const val TIMER_ZERO = "0:00"
        const val MS_PER_SECOND = 1000
        const val SECONDS_PER_MINUTE = 60
    }
}
