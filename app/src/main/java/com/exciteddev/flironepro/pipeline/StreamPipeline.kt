package com.exciteddev.flironepro.pipeline

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.exciteddev.flironepro.debug.DemoSource
import com.exciteddev.flironepro.debug.DiagLog
import com.exciteddev.flironepro.debug.FrameStatsLog
import com.exciteddev.flironepro.protocol.BatteryState
import com.exciteddev.flironepro.protocol.FlirFrame
import com.exciteddev.flironepro.protocol.ServiceMessages
import com.exciteddev.flironepro.radiometry.Radiometry
import com.exciteddev.flironepro.render.UiTexts
import com.exciteddev.flironepro.render.overlayTexts
import com.exciteddev.flironepro.settings.AppSettings
import com.exciteddev.flironepro.usb.FlirCamera

/**
 * The data side of the live screen: the camera (or the demo player), the frame processor, the radiometric model and the
 * statistics log, wired together for one session. A new session starts on every (re)start of the camera or the demo.
 * It receives the camera's frames and messages (see [FlirCamera.Listener]) on the camera's threads.
 *
 * It does not belong to a screen: the screen's picture view is [attach]ed to it for as long as the screen exists, and the
 * pipeline goes on (without showing anything) while there is none, e.g. while the screen is being recreated.
 */
class StreamPipeline(private val context: Context, private val settings: AppSettings) : FlirCamera.Listener {

    /** Called (on the processing thread) when a photo was saved: the file name, or null if saving failed. */
    var onPhotoSaved: (String?) -> Unit = {}

    /** Called (on a background thread) when a video was finished: the file name, or null if saving failed. */
    var onVideoSaved: (String?) -> Unit = {}

    /** Called (on the camera's thread) when the connection to the camera is lost. */
    var onConnectionLost: () -> Unit = {}

    var radiometry = Radiometry().also { settings.applyTo(it) }
        private set

    @Volatile var processor: FrameProcessor? = null
        private set

    @Volatile var camera: FlirCamera? = null
        private set

    @Volatile var demoSource: DemoSource? = null
        private set

    @Volatile var battery = BatteryState()
        private set

    /** The newest frame, kept for saving it as a raw file. */
    @Volatile var lastFrame: FlirFrame? = null
        private set

    /** The frame bookkeeping of the running session; null while nothing runs. */
    @Volatile var intake: FrameIntake? = null
        private set

    private var statsLog: FrameStatsLog? = null
    private val sink = DetachableSink()

    /** The texts of the overlays in the language of the attached screen. */
    @Volatile private var texts: UiTexts = context.overlayTexts()

    val frameCount: Int get() = intake?.frameCount ?: 0

    /** Shows the pictures in [picture], with the overlay [overlayTexts] (they change with the language of the screen). */
    fun attach(picture: PictureSink, overlayTexts: UiTexts) {
        texts = overlayTexts
        processor?.renderer?.texts = overlayTexts
        sink.attach(picture)
    }

    /** Stops showing the pictures in [picture] (the screen is gone); the stream itself goes on. */
    fun detach(picture: PictureSink) = sink.detach(picture)

    /** Starts reading the camera; false if it could not be started. */
    fun startCamera(manager: UsbManager, device: UsbDevice): Boolean {
        if (statsLog == null) statsLog = FrameStatsLog(context)
        beginSession(live = true, fixedFpaK = null)
        DiagLog.log("=== Starting the stream (${device.deviceName}) ===")
        val cam = FlirCamera(manager, device, this)
        camera = cam
        val started = cam.start()
        if (!started) {
            camera = null
            processor?.shutdown()
            processor = null
            intake = null
        }
        return started
    }

    /** Starts replaying the saved frames, [frameMs] apart; null if the demo is already running. */
    fun startDemo(frameMs: Long): DemoSource? {
        if (demoSource != null) return null
        beginSession(live = false, fixedFpaK = DEMO_FPA_K)
        radiometry.setAnchor(DEMO_ANCHOR)
        DiagLog.log("=== Starting the demo ===")
        val src = DemoSource(FrameStore.dir(context), frameMs) { onFrame(it) }
        demoSource = src
        src.start()
        return src
    }

    fun stop() {
        demoSource?.shutdown()
        demoSource = null
        camera?.stop()
        camera = null
        processor?.shutdown()
        processor = null
        // The render thread is gone, so nothing can bring the last picture back: show the empty state (with its message) again.
        sink.clear()
        intake = null
        statsLog?.close()
        statsLog = null
    }

    /** Re-reads the user's settings into the temperature model. */
    fun applySettings() = settings.applyTo(radiometry)

    private fun beginSession(live: Boolean, fixedFpaK: Double?) {
        battery = BatteryState()
        radiometry = Radiometry()
        settings.applyTo(radiometry)
        // A quick restart does not make the camera calibrate its shutter again, so reuse a recent calibration meanwhile.
        if (live) {
            radiometry.fallbackAnchor = Radiometry.recentAnchor(System.currentTimeMillis())
            if (radiometry.fallbackAnchor != null) {
                DiagLog.log("Using the shutter calibration of the previous session until a new one arrives")
            }
        }
        sink.clear()
        val newProcessor = FrameProcessor(context.applicationContext, settings, radiometry, sink, texts)
        newProcessor.onPhotoSaved = { name -> onPhotoSaved(name) }
        newProcessor.start()
        processor = newProcessor
        intake = FrameIntake(radiometry, newProcessor, if (live) statsLog else null, live, fixedFpaK)
    }

    override fun onFrame(frame: FlirFrame) {
        lastFrame = frame
        intake?.handle(frame)
    }

    override fun onState(message: String) {
        DiagLog.log("State: $message")
    }

    override fun onLost() {
        onConnectionLost()
    }

    override fun onServiceMessage(json: String) {
        battery = ServiceMessages.updateBattery(battery, json)
        // Everything except the battery reports is logged: replies to our commands (e.g. the shutter-calibration search) end up here.
        if ("attery" !in json) DiagLog.log("[service] ${json.take(SERVICE_LOG_CHARS)}")
    }

    private companion object {
        /** The demo's anchor and sensor temperature, taken from verified measurements (room / freezer). */
        val DEMO_ANCHOR = Radiometry.Anchor(3845.0, 304.92, 305.21)
        const val DEMO_FPA_K = 305.4
        const val SERVICE_LOG_CHARS = 300
    }
}
