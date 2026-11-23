package com.exciteddev.flironepro.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.exciteddev.flironepro.render.overlayTexts
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * The part of the live screen that lives and dies with one activity: the views, the controllers that hold them and the wiring
 * between them. It is built in one go from the [StreamViewModel] (which keeps the stream and the settings), and a new one is
 * built when the activity is created again. Nothing that must survive that belongs here.
 *
 * The controllers get what they need through their constructors, none of them reaches into the activity. The parts are
 * exposed for the developer self-test, which drives the whole screen; [SettingsPanel] and the controllers use only what
 * they are given.
 */
internal class StreamScreen(val activity: AppCompatActivity, private val model: StreamViewModel) : ThermalView.Listener {

    val settings = model.settings
    val pipeline = model.pipeline
    val views = StreamViews(activity)
    val geometry = ScreenGeometry(activity, views, settings)
    val dialogs = ParameterDialogs(activity, settings, pipeline)
    val cards = ToolCards(activity, views, settings, pipeline, dialogs)
    val recording = RecordingController(activity, views, settings, pipeline)
    val ffc = FfcController(activity, settings, pipeline, model.runMode)

    private val settingsActions = object : SettingsActions {
        override fun onChanged() = onSettingsChanged()

        override fun openEmissivity() = dialogs.openEmissivity()

        override fun openReflected() = dialogs.openReflected()

        override fun openCalibration() = cards.openCalibration()

        override fun openAlignment() = cards.openAlignment()

        override fun requestFfc() = ffc.request()

        override fun hideControls() = chrome.setControlsHidden(true)

        override fun saveRawFrame() = recording.saveRawFrame()
    }

    val settingsPanel: SettingsPanel = SettingsPanel(activity, views.settingsHost, geometry, settings, settingsActions)
    val hud: HudController = HudController(activity, views, pipeline, recording, model.runMode) { settingsPanel.isShown || cards.isOpen }
    val chrome: ChromeController = ChromeController(activity, views, cards, settingsPanel)

    private val ui = Handler(Looper.getMainLooper())
    private val saveSettingsRunnable = Runnable { settings.save() }

    /** Shows what the stream says (see [StreamStatus]) for as long as this screen lives. */
    private var listening: Job? = null

    private val ticker = object : Runnable {
        override fun run() {
            hud.updateTopBar()
            hud.updateHud()
            ffc.ensureAutoTuning()
            if (views.calCard.visibility == View.VISIBLE) cards.updateCalText()
            ui.postDelayed(this, TICK_MS)
        }
    }

    /** Connects the views and the controllers, and starts showing the stream: the picture and what the stream has to say. */
    fun bind() {
        val texts = activity.overlayTexts()
        views.thermalView.settings = settings
        views.thermalView.texts = texts
        views.thermalView.listener = this
        chrome.bind()
        cards.bind()
        recording.bind()
        geometry.bindInsets()
        geometry.updateRotation()
        recording.refreshUi()
        hud.updateTopBar()
        pipeline.attach(views.thermalView, texts)
        listening = activity.lifecycleScope.launch { listenToStatus() }
    }

    /** The activity is going away: the stream must not keep a reference to its views. */
    fun release() {
        listening?.cancel()
        pipeline.detach(views.thermalView)
        ui.removeCallbacksAndMessages(null)
        recording.release()
    }

    fun startTicking() {
        ui.post(ticker)
    }

    fun stopTicking() {
        ui.removeCallbacks(ticker)
    }

    /** Called after any display-related setting changed. */
    fun onSettingsChanged() {
        geometry.updateRotation()
        pipeline.applySettings()
        views.thermalView.postInvalidateOnAnimation()
    }

    /** Runs on the main thread: it starts with the current message and with the notices that waited for a screen. */
    private suspend fun listenToStatus() = coroutineScope {
        launch { model.status.message.filterNotNull().collect { hud.showMessage(it) } }
        launch { model.status.notices.collect { recording.show(it) } }
    }

    override fun onCrosshairMoved(u: Float, v: Float) {
        ui.removeCallbacks(saveSettingsRunnable)
        ui.postDelayed(saveSettingsRunnable, SAVE_DELAY_MS)
    }

    override fun onTap() = chrome.onTap()

    private companion object {
        const val TICK_MS = 300L
        const val SAVE_DELAY_MS = 800L
    }
}
