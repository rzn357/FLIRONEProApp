package com.exciteddev.flironepro.ui

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.exciteddev.flironepro.R
import com.exciteddev.flironepro.debug.DiagLog

/**
 * Main screen: the live picture with three display modes (thermal / visible / MSX), a draggable crosshair with its
 * temperature, markers of the hottest and coldest points, photo and video capture, emissivity settings and temperature
 * calibration. The only controls on top of the picture are the settings gear, the battery and the frame rate (top) and
 * the photo / video buttons (bottom).
 *
 * The activity holds no state of its own. The stream, the settings and the way the stream starts and stops are in the
 * [StreamViewModel], which survives the activity being created again; the views and the controllers are the [StreamScreen],
 * built here. What is left for the activity is the lifecycle: it starts and stops the stream and forwards the rest.
 *
 * The manifest keeps the activity from being recreated on rotation (the picture just turns), but anything else that recreates
 * it (a change of language, for example) no longer touches the camera.
 */
class StreamActivity : AppCompatActivity() {

    private lateinit var model: StreamViewModel
    private lateinit var screen: StreamScreen
    private lateinit var devTools: DevTools

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagLog.init(this)
        setContentView(R.layout.activity_stream)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        model = ViewModelProvider(this)[StreamViewModel::class.java]
        // Debug builds only: the developer tools (started from adb) can turn this run into a demo or a self-test.
        devTools = createDevTools(this, model)
        devTools.planRun(savedInstanceState, intent)
        screen = StreamScreen(this, model)
        screen.bind()
        devTools.screenReady(screen, intent)
    }

    override fun onStart() {
        super.onStart()
        screen.startTicking()
        val runMode = model.runMode
        when {
            runMode.selfTest -> Unit
            runMode.demo -> devTools.startDemo()
            else -> model.session.ensureStarted()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val runMode = model.runMode
        if (!runMode.selfTest && !runMode.demo && model.pipeline.camera?.isRunning != true) model.session.ensureStarted()
        devTools.newIntent(intent)
    }

    override fun onStop() {
        screen.stopTicking()
        // A screen that is only being created again (not left) leaves the stream running for the next one.
        if (!model.runMode.selfTest && !isChangingConfigurations) model.session.stop()
        super.onStop()
    }

    override fun onDestroy() {
        screen.release()
        if (model.runMode.selfTest && !isChangingConfigurations) model.session.stop()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) screen.chrome.hideSystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        screen.geometry.updateRotation()
    }
}
