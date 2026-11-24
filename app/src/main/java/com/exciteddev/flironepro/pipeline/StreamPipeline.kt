package com.exciteddev.flironepro.pipeline

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
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
import com.exciteddev.flironepro.usb.UsbOpener
import com.exciteddev.flironepro.usb.androidOpener
import kotlinx.coroutines.CoroutineScope

/** Makes the frame processor of a session (the tests make a fake one). */
internal fun interface ProcessorFactory {
    fun create(radiometry: Radiometry, view: PictureSink, texts: UiTexts): FrameProcessing
}

/**
 * The data side of the live screen: the camera (or the demo player), the frame processor, the radiometric model and the
 * statistics log, wired together for one session. A new session starts on every (re)start of the camera or the demo.
 * It receives the camera's frames and messages (see [FlirCamera.Listener]) on the camera's threads.
 *
 * It does not belong to a screen: the screen's picture view is [attach]ed to it for as long as the screen exists, and the
 * pipeline goes on (without showing anything) while there is none, e.g. while the screen is being recreated.
 *
 * [scope] runs the work that has to be finished in the background whatever happens to the screen (closing the video file); its
 * jobs must not be cancelled half way, so it must outlive the stream.
 *
 * The processors, the first overlay texts and the clock are what the tests replace, to run the pipeline without a screen, a
 * render thread or a camera.
 */
class StreamPipeline internal constructor(
    private val context: Context,
    private val settings: AppSettings,
    internal val scope: CoroutineScope,
    private val processors: ProcessorFactory,
    initialTexts: UiTexts,
    private val clock: () -> Long = SystemClock::elapsedRealtime
) : FlirCamera.Listener {

    constructor(context: Context, settings: AppSettings, scope: CoroutineScope) : this(
        context,
        settings,
        scope,
        ProcessorFactory { radiometry, view, texts ->
            FrameProcessor(context.applicationContext, settings, radiometry, view, texts)
        },
        context.overlayTexts()
    )

    /** Called (on the processing thread) when a photo was saved: the file name, or null if saving failed. */
    var onPhotoSaved: (String?) -> Unit = {}

    /** Called (on a background thread) when a video was finished: the file name, or null if saving failed. */
    var onVideoSaved: (String?) -> Unit = {}

    /** Called (on the camera's thread) when the connection to the camera is lost. */
    var onConnectionLost: () -> Unit = {}

    var radiometry = Radiometry().also { settings.applyTo(it) }
        private set

    @Volatile var processor: FrameProcessing? = null
        private set

    @Volatile var camera: FlirCamera? = null
        private set

    @Volatile var replay: ReplaySource? = null
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
    @Volatile private var texts: UiTexts = initialTexts

    val frameCount: Int get() = intake?.frameCount ?: 0

    /** Shows the pictures in [picture], with the overlay [overlayTexts] (they change with the language of the screen). */
    fun attach(picture: PictureSink, overlayTexts: UiTexts) {
        texts = overlayTexts
        processor?.overlayTexts = overlayTexts
        sink.attach(picture)
    }

    /** Stops showing the pictures in [picture] (the screen is gone); the stream itself goes on. */
    fun detach(picture: PictureSink) = sink.detach(picture)

    /** Starts reading the camera behind [opener], which is called [name] in the log; false if it could not be started. */
    internal fun startCamera(opener: UsbOpener, name: String): Boolean {
        if (statsLog == null) statsLog = FrameStatsLog(context)
        beginSession(live = true, fixedFpaK = null)
        DiagLog.log("=== Starting the stream ($name) ===")
        val cam = FlirCamera(opener, this, clock)
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

    /**
     * Starts a session fed by a [ReplaySource] instead of the camera; null if one is already running. The replayed frames do
     * not feed the temperature model, which gets the [anchor] and the sensor temperature [fixedFpaK] instead. [create] gets
     * the function the source has to deliver its frames to.
     */
    fun <T : ReplaySource> startReplay(fixedFpaK: Double, anchor: Radiometry.Anchor, create: (onFrame: (FlirFrame) -> Unit) -> T): T? {
        if (replay != null) return null
        beginSession(live = false, fixedFpaK = fixedFpaK)
        radiometry.setAnchor(anchor)
        val src = create { onFrame(it) }
        replay = src
        src.start()
        return src
    }

    fun stop() {
        replay?.shutdown()
        replay = null
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
        val newProcessor = processors.create(radiometry, sink, texts)
        newProcessor.onPhotoSaved = { name -> onPhotoSaved(name) }
        newProcessor.start()
        processor = newProcessor
        intake = FrameIntake(radiometry, newProcessor, if (live) statsLog else null, live, fixedFpaK, clock)
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
        const val SERVICE_LOG_CHARS = 300
    }
}

/** Starts reading the camera [device]; false if it could not be started. */
fun StreamPipeline.startCamera(manager: UsbManager, device: UsbDevice): Boolean =
    startCamera(androidOpener(manager, device), device.deviceName)
