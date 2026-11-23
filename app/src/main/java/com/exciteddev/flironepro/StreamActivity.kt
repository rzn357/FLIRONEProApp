package com.exciteddev.flironepro

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

/**
 * Main screen: the live picture with three display modes (thermal / visible / MSX), a draggable crosshair with its
 * temperature, markers of the hottest and coldest points, photo and video capture, emissivity settings and temperature
 * calibration. The only controls on top of the picture are the settings gear, the battery and the frame rate (top) and
 * the photo / video buttons (bottom).
 *
 * The activity itself only wires the parts together and forwards the lifecycle to them: the data side is the [StreamPipeline],
 * starting and stopping is the [StreamSession], and every kind of control has a class of its own.
 */
class StreamActivity :
    AppCompatActivity(),
    ThermalView.Listener {

    internal lateinit var settings: AppSettings
    internal lateinit var views: StreamViews
    internal lateinit var pipeline: StreamPipeline
    internal lateinit var geometry: ScreenGeometry
    internal lateinit var dialogs: ParameterDialogs
    internal lateinit var cards: ToolCards
    internal lateinit var recording: RecordingController
    internal lateinit var ffc: FfcController
    internal lateinit var hud: HudController
    internal lateinit var settingsPanel: SettingsPanel
    internal lateinit var chrome: ChromeController
    private lateinit var session: StreamSession
    private lateinit var debugCommands: DebugCommands
    private lateinit var runMode: RunMode

    private val ui = Handler(Looper.getMainLooper())
    private val saveSettingsRunnable = Runnable { settings.save() }

    private val ticker = object : Runnable {
        override fun run() {
            hud.updateTopBar()
            hud.updateHud()
            ffc.ensureAutoTuning()
            if (views.calCard.visibility == View.VISIBLE) cards.updateCalText()
            ui.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagLog.init(this)
        setContentView(R.layout.activity_stream)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        settings = AppSettings(this)
        val plan = DebugRun.plan(this, savedInstanceState, intent)
        runMode = RunMode(plan.demo, plan.selfTest)

        views = StreamViews(this)
        views.thermalView.settings = settings
        views.thermalView.texts = overlayTexts()
        views.thermalView.listener = this
        pipeline = StreamPipeline(this, settings, views.thermalView)
        geometry = ScreenGeometry(this, views, settings)
        dialogs = ParameterDialogs(this, settings, pipeline)
        cards = ToolCards(this, views, settings, pipeline, dialogs)
        recording = RecordingController(this, views, settings, pipeline)
        ffc = FfcController(this, settings, pipeline, runMode)
        settingsPanel = SettingsPanel(this, views.settingsHost)
        hud = HudController(this, views, pipeline, recording, runMode) { settingsPanel.isShown || cards.isOpen }
        chrome = ChromeController(this, views, cards, settingsPanel)
        session = StreamSession(this, getSystemService(Context.USB_SERVICE) as UsbManager, pipeline, hud, recording, ffc, runMode)
        debugCommands = DebugCommands(this, pipeline)

        chrome.bind()
        cards.bind()
        recording.bind()
        geometry.bindInsets()
        session.register()

        geometry.updateRotation()
        recording.refreshUi()
        hud.updateTopBar()

        // Diagnostic self-test (started from adb in debug builds): runs in demo mode, independent of window visibility.
        if (plan.runSelfTest) {
            runMode.demo = true
            session.startDemo()
            SelfTest(this, plan.language).start()
        }
        debugCommands.handle(intent)
    }

    override fun onStart() {
        super.onStart()
        ui.post(ticker)
        when {
            runMode.selfTest -> Unit
            runMode.demo -> session.startDemo()
            else -> session.ensureStarted()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!runMode.selfTest && !runMode.demo && pipeline.camera?.isRunning != true) session.ensureStarted()
        debugCommands.handle(intent)
    }

    override fun onStop() {
        ui.removeCallbacks(ticker)
        if (!runMode.selfTest) session.stop()
        super.onStop()
    }

    override fun onDestroy() {
        session.unregister()
        if (runMode.selfTest) session.stop()
        recording.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) chrome.hideSystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        geometry.updateRotation()
    }

    /** Called by the settings panel after any display-related setting changed. */
    internal fun onSettingsChanged() {
        geometry.updateRotation()
        pipeline.applySettings()
        views.thermalView.postInvalidateOnAnimation()
    }

    override fun onCrosshairMoved(u: Float, v: Float) {
        ui.removeCallbacks(saveSettingsRunnable)
        ui.postDelayed(saveSettingsRunnable, SAVE_DELAY_MS)
    }

    override fun onTap() = chrome.onTap()

    companion object {
        private const val TICK_MS = 300L
        private const val SAVE_DELAY_MS = 800L
    }
}
