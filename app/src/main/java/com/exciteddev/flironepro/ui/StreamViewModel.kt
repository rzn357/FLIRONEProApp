package com.exciteddev.flironepro.ui

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbManager
import androidx.lifecycle.AndroidViewModel
import com.exciteddev.flironepro.pipeline.StreamPipeline
import com.exciteddev.flironepro.settings.AppSettings

/**
 * Everything of the live screen that has to outlive the screen itself: the user's settings, the stream (camera, processing,
 * recording) and the way it is started and stopped. The activity can be created again at any time (the language or the theme
 * changes, the system recreates it), and the camera must not restart because of that; the views and the controllers that hold
 * them are built again by the new screen ([StreamScreen]) and meet the same stream.
 *
 * Nothing here may refer to an activity or a view: the pipeline gets its picture view only through
 * [StreamPipeline.attach], and tells the user what happens through [status].
 */
class StreamViewModel(application: Application) : AndroidViewModel(application) {
    val settings = AppSettings(application)
    val runMode = RunMode()
    val status = StreamStatus()
    val pipeline = StreamPipeline(application, settings)
    val session = StreamSession(application, application.getSystemService(Context.USB_SERVICE) as UsbManager, pipeline, runMode, status)

    init {
        pipeline.onPhotoSaved = { name -> status.notify(Notice.PhotoSaved(name)) }
        pipeline.onVideoSaved = { name -> status.notify(Notice.VideoSaved(name)) }
        session.register()
    }

    /** Sets how this launch of the screen runs; a demo of an earlier launch does not go on into a normal one. */
    fun applyRunPlan(demo: Boolean, selfTest: Boolean) {
        if (runMode.demo && !demo) session.stop()
        runMode.demo = demo
        runMode.selfTest = selfTest
    }

    /** The screen is gone for good (not recreated): nothing may go on streaming. */
    override fun onCleared() {
        session.unregister()
        session.stop()
    }
}
