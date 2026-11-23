package com.exciteddev.flironepro

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.MediaActionSound
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import kotlin.math.abs

/**
 * Main screen: the live picture with three display modes (thermal / visible / MSX), a draggable crosshair with its
 * temperature, markers of the hottest and coldest points, photo and video capture, emissivity settings and temperature
 * calibration. The only controls on top of the picture are the settings gear, the battery and the frame rate (top) and
 * the photo / video buttons (bottom).
 */
class StreamActivity :
    AppCompatActivity(),
    FlirCamera.Listener,
    ThermalView.Listener {

    private lateinit var usbManager: UsbManager
    internal lateinit var thermalView: ThermalView
    internal lateinit var settings: AppSettings
    internal lateinit var rootView: FrameLayout
    private lateinit var settingsPanel: SettingsPanel

    private lateinit var topBar: View
    private lateinit var statusPill: View
    private lateinit var batteryIcon: ImageView
    private lateinit var batteryText: TextView
    private lateinit var fpsText: TextView
    private lateinit var bottomBar: View
    private lateinit var btnVideo: View
    private lateinit var videoIcon: ImageView
    private lateinit var videoTimer: TextView
    private lateinit var cardHost: View
    private lateinit var calCard: View
    private lateinit var calText: TextView
    private lateinit var alignCard: View
    private lateinit var alignInfo: TextView

    private val ui = Handler(Looper.getMainLooper())
    private var camera: FlirCamera? = null
    internal var processor: FrameProcessor? = null
    private var demoSource: DemoSource? = null
    private var radiometry = Radiometry()
    private var statsLog: FrameStatsLog? = null
    private var permissionAsked = false
    private val sound = MediaActionSound()

    @Volatile private var battery = BatteryState()
    private val batteryDrawable = BatteryDrawable()
    private var message: String? = null
    private var recorder: VideoRecorder? = null
    private var demo = false
    private var selfTest = false
    private var controlsHidden = false

    @Volatile private var lastFrame: FlirFrame? = null

    /** Display-cutout / system-bar insets (used to keep the settings screen clear of the notch). */
    internal var contentInsets: androidx.core.graphics.Insets = androidx.core.graphics.Insets.NONE

    /** Test hook: pretend the view has this size in pixels (width, height); null = use the real one. */
    internal var testViewSize: Pair<Int, Int>? = null

    /** Test hook: pretend the screen has this rotation (degrees); null = use the real one. */
    internal var forcedDisplayRotation: Int? = null

    // State touched only by the frame thread (USB or demo).
    @Volatile private var frameCount = 0
    private var fpsStart = 0L
    private var fpsFrames = 0

    @Volatile private var fps = 0f

    @Volatile private var ffcActive = false
    private var dropAfterFfc = false

    @Volatile private var lastFfcState: String? = null
    private var autoTuningEnsured = false

    /** Time (elapsedRealtime) of the newest frame that showed a calibration under way (state PROGRESS or shutter closed); 0 = none. */
    @Volatile private var lastFfcActivityAt = 0L

    @Volatile private var ffcProbeRunning = false

    @Volatile private var ffcProbeCancelled = false
    private var demoFpa: Double? = null
    private val shutterRegex = Regex("\"shutterTemperature\"\\s*:\\s*([0-9.]+)")
    private val ffcStateRegex = Regex("\"ffcState\"\\s*:\\s*\"([A-Za-z_]+)\"")

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    DiagLog.log("Permission (live view): ${if (granted) "granted" else "denied"}")
                    if (granted) startCamera() else showEmptyMessage(R.string.msg_permission_denied)
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val d = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    if (d?.vendorId == UsbReport.FLIR_VENDOR_ID && !demo) {
                        DiagLog.log("Camera detached")
                        stopPipeline()
                        showEmptyMessage(R.string.msg_camera_disconnected)
                    }
                }
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateTopBar()
            updateHud()
            ensureAutoTuning()
            if (calCard.visibility == View.VISIBLE) updateCalText()
            ui.postDelayed(this, 300)
        }
    }

    private val saveSettingsRunnable = Runnable { settings.save() }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagLog.init(this)
        setContentView(R.layout.activity_stream)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        settings = AppSettings(this)
        settings.applyTo(radiometry)

        // The demo player and the self-test are developer tools of debuggable builds only; they are started from adb.
        demo = debuggable && intent.getBooleanExtra(EXTRA_DEMO, false)
        selfTest = debuggable && intent.getBooleanExtra(EXTRA_SELFTEST, false)
        // Self-test only (debug): optionally switch the app language first. A fresh launch from adb switches the language and the
        // activity is recreated; the recreated instance runs the test; any later recreation (e.g. when the language is restored)
        // behaves as a normal screen, so the test can never loop.
        val testLang = intent.getStringExtra(EXTRA_SELFTEST_LANG)
        var runSelfTest = false
        if (selfTest) {
            val prefs = getSharedPreferences("selftest", Context.MODE_PRIVATE)
            if (savedInstanceState == null) {
                val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                if (testLang != null && current != testLang) {
                    prefs.edit().putString("prevLang", current).putBoolean("pending", true).apply()
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(testLang))
                } else {
                    runSelfTest = true
                }
            } else if (prefs.getBoolean("pending", false)) {
                prefs.edit().putBoolean("pending", false).apply()
                runSelfTest = true
            } else {
                selfTest = false
                demo = false
            }
        }

        rootView = findViewById(R.id.root)
        thermalView = findViewById(R.id.thermalView)
        thermalView.settings = settings
        thermalView.texts = buildTexts()
        thermalView.listener = this
        topBar = findViewById(R.id.topBar)
        statusPill = findViewById(R.id.statusPill)
        batteryIcon = findViewById(R.id.batteryIcon)
        batteryIcon.setImageDrawable(batteryDrawable)
        batteryText = findViewById(R.id.batteryText)
        fpsText = findViewById(R.id.fpsText)
        bottomBar = findViewById(R.id.bottomBar)
        btnVideo = findViewById(R.id.btnVideo)
        videoIcon = findViewById(R.id.videoIcon)
        videoTimer = findViewById(R.id.videoTimer)
        cardHost = findViewById(R.id.cardHost)
        calCard = findViewById(R.id.calCard)
        calText = findViewById(R.id.calText)
        alignCard = findViewById(R.id.alignCard)
        alignInfo = findViewById(R.id.alignInfo)

        settingsPanel = SettingsPanel(this, findViewById(R.id.settingsHost))
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            hideCards()
            settingsPanel.toggle()
        }
        findViewById<View>(R.id.btnPhoto).setOnClickListener { takePhoto() }
        btnVideo.setOnClickListener { toggleRecording() }

        findViewById<Button>(R.id.btnCalAdd).setOnClickListener { calibrationAddPoint() }
        findViewById<Button>(R.id.btnCalReset).setOnClickListener { calibrationReset() }
        findViewById<Button>(R.id.btnCalClose).setOnClickListener { hideCards() }
        findViewById<Button>(R.id.btnAlignLeft).setOnClickListener { alignChange(dx = 0.005f) }
        findViewById<Button>(R.id.btnAlignRight).setOnClickListener { alignChange(dx = -0.005f) }
        findViewById<Button>(R.id.btnAlignUp).setOnClickListener { alignChange(dy = 0.005f) }
        findViewById<Button>(R.id.btnAlignDown).setOnClickListener { alignChange(dy = -0.005f) }
        findViewById<Button>(R.id.btnAlignSmaller).setOnClickListener { alignChange(ds = 0.01f) }
        findViewById<Button>(R.id.btnAlignBigger).setOnClickListener { alignChange(ds = -0.01f) }
        findViewById<Button>(R.id.btnAlignReset).setOnClickListener {
            settings.alignment = Alignment()
            alignChange()
        }
        findViewById<Button>(R.id.btnAlignDone).setOnClickListener { hideCards() }

        sound.load(MediaActionSound.SHUTTER_CLICK)
        sound.load(MediaActionSound.START_VIDEO_RECORDING)
        sound.load(MediaActionSound.STOP_VIDEO_RECORDING)

        // Keep the controls clear of the display cutout.
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars())
            contentInsets = i
            topBar.updatePadding(left = dp(8) + i.left, top = dp(8) + i.top, right = dp(8) + i.right)
            bottomBar.updatePadding(bottom = dp(16) + i.bottom)
            cardHost.updatePadding(left = dp(8) + i.left, right = dp(8) + i.right)
            insets
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        settingsPanel.isShown -> settingsPanel.hide()

                        calCard.visibility == View.VISIBLE || alignCard.visibility == View.VISIBLE -> hideCards()

                        controlsHidden -> setControlsHidden(false)

                        else -> {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            }
        )

        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        updateRotation()
        refreshRecordingUi()
        updateTopBar()

        // Diagnostic self-test (started from adb in debug builds): runs in demo mode, independent of window visibility.
        if (runSelfTest) {
            demo = true
            startDemo()
            SelfTest(this, testLang).start()
        }
        handleDebugIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        ui.post(ticker)
        if (selfTest) return
        if (demo) startDemo() else ensureStarted()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!selfTest && !demo && camera?.isRunning != true) ensureStarted()
        handleDebugIntent(intent)
    }

    override fun onStop() {
        ui.removeCallbacks(ticker)
        if (!selfTest) stopPipeline()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        if (selfTest) stopPipeline()
        sound.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateRotation()
    }

    private fun hideSystemBars() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Size of the root view in pixels (or the test override). */
    internal fun viewSize(): Pair<Int, Int> {
        testViewSize?.let { return it }
        val dm = resources.displayMetrics
        return Pair(rootView.width.takeIf { it > 0 } ?: dm.widthPixels, rootView.height.takeIf { it > 0 } ?: dm.heightPixels)
    }

    /** While the full-screen settings are open, the bars and the overlays under it are hidden, so nothing ghosts through. */
    internal fun onSettingsVisibility(shown: Boolean) {
        thermalView.overlaysSuppressed = shown
        val v = when {
            shown -> View.INVISIBLE
            controlsHidden -> View.GONE
            else -> View.VISIBLE
        }
        topBar.visibility = v
        bottomBar.visibility = v
        thermalView.postInvalidateOnAnimation()
    }

    internal fun appLocale(): Locale = resources.configuration.locales[0]

    private fun fmt1(x: Double): String = String.format(appLocale(), "%.1f", x)

    private fun buildTexts() = UiTexts(
        locale = appLocale(),
        waitingForCalibration = getString(R.string.note_waiting_ffc),
        shutterCalibration = getString(R.string.note_ffc),
        demoNote = getString(R.string.note_demo),
        emissivityFormat = getString(R.string.note_emissivity_fmt)
    )

    // ---------------------------------------------------------------- orientation

    @Suppress("DEPRECATION")
    private fun displayRotationDegrees(): Int {
        forcedDisplayRotation?.let { return it }
        return when ((getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /** Recomputes the effective image rotation from the screen orientation and the user's settings. */
    internal fun updateRotation() {
        settings.rotation = Orientation.effective(settings.autoRotate, settings.rotationOffset, displayRotationDegrees())
        thermalView.postInvalidateOnAnimation()
    }

    /** Called by the settings panel after any display-related setting changed. */
    internal fun onSettingsChanged() {
        updateRotation()
        settings.applyTo(radiometry)
        thermalView.postInvalidateOnAnimation()
    }

    // ---------------------------------------------------------------- start / stop

    private fun ensureStarted() {
        val device = usbManager.deviceList.values.firstOrNull { it.vendorId == UsbReport.FLIR_VENDOR_ID }
        if (device == null) {
            showEmptyMessage(R.string.msg_no_camera)
            return
        }
        if (!usbManager.hasPermission(device)) {
            if (!permissionAsked) {
                permissionAsked = true
                val intent = Intent(ACTION_PERMISSION).setPackage(packageName)
                val pending = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_MUTABLE)
                DiagLog.log("Requesting permission for ${device.deviceName}")
                usbManager.requestPermission(device, pending)
            }
            showEmptyMessage(R.string.msg_wait_permission)
            return
        }
        startCamera()
    }

    private fun newPipeline() {
        resetFrameState()
        radiometry = Radiometry()
        settings.applyTo(radiometry)
        // A quick restart does not make the camera calibrate its shutter again, so reuse a recent calibration meanwhile.
        if (!demo) {
            radiometry.fallbackAnchor = Radiometry.recentAnchor(System.currentTimeMillis())
            if (radiometry.fallbackAnchor !=
                null
            ) {
                DiagLog.log("Using the shutter calibration of the previous session until a new one arrives")
            }
        }
        thermalView.clear()
        processor = FrameProcessor(applicationContext, settings, radiometry, thermalView, buildTexts()).also {
            it.onPhotoSaved = { name ->
                runOnUiThread {
                    toast(if (name != null) getString(R.string.toast_photo_saved_fmt, name) else getString(R.string.toast_photo_failed))
                }
            }
            it.start()
        }
    }

    private fun startCamera() {
        if (camera?.isRunning == true) return
        val device = usbManager.deviceList.values.firstOrNull { it.vendorId == UsbReport.FLIR_VENDOR_ID } ?: return
        newPipeline()
        if (statsLog == null) statsLog = FrameStatsLog(this)
        DiagLog.log("=== Starting the stream (${device.deviceName}) ===")
        val cam = FlirCamera(usbManager, device, this)
        camera = cam
        if (cam.start()) {
            showEmptyMessage(R.string.msg_wait_first_frame)
        } else {
            camera = null
            processor?.shutdown()
            processor = null
        }
    }

    private fun startDemo() {
        if (demoSource != null) return
        newPipeline()
        // Anchor for the demo, taken from verified measurements (room / freezer).
        radiometry.setAnchor(Radiometry.Anchor(3845.0, 304.92, 305.21))
        demoFpa = 305.4
        DiagLog.log("=== Starting the demo ===")
        // The self-test feeds frames faster (20/s) to check the performance headroom.
        val src = DemoSource(FrameStore.dir(this), if (selfTest) 50L else DemoSource.FRAME_MS) { handleFrame(it) }
        demoSource = src
        src.start()
        showEmptyMessage(R.string.msg_demo_running)
        ui.postDelayed({
            if (demoSource === src && src.frameCount == 0 && frameCount == 0) showEmptyMessage(R.string.msg_demo_no_frames)
        }, 1500)
    }

    private fun stopPipeline() {
        ffcProbeCancelled = true
        stopRecording(wait = false)
        demoSource?.shutdown()
        demoSource = null
        camera?.stop()
        camera = null
        processor?.shutdown()
        processor = null
        statsLog?.close()
        statsLog = null
    }

    private fun resetFrameState() {
        frameCount = 0
        fpsStart = 0L
        fpsFrames = 0
        fps = 0f
        ffcActive = false
        dropAfterFfc = false
        lastFfcState = null
        autoTuningEnsured = false
        lastFfcActivityAt = 0L
        demoFpa = null
        battery = BatteryState()
    }

    // ---------------------------------------------------------------- frames (USB or demo thread)

    override fun onFrame(frame: FlirFrame) = handleFrame(frame)

    override fun onState(message: String) {
        DiagLog.log("State: $message")
    }

    override fun onLost() {
        ui.post {
            stopPipeline()
            showEmptyMessage(R.string.msg_connection_lost)
        }
    }

    override fun onServiceMessage(json: String) {
        battery = ServiceMessages.updateBattery(battery, json)
        // Everything except the battery reports is logged: replies to our commands (e.g. the shutter-calibration search) end up here.
        if ("attery" !in json) DiagLog.log("[service] ${json.take(300)}")
    }

    private fun handleFrame(frame: FlirFrame) {
        lastFrame = frame
        frameCount++
        val now = SystemClock.elapsedRealtime()
        if (fpsStart == 0L) fpsStart = now
        fpsFrames++
        if (now - fpsStart >= 1000) {
            fps = fpsFrames * 1000f / (now - fpsStart)
            fpsStart = now
            fpsFrames = 0
        }

        val raw = ThermalImage.decode(frame, IntArray(ThermalImage.W * ThermalImage.H))
        val stats = raw?.let { ThermalImage.stats(it) }
        val status = frame.statusText(400)
        val ffcStateNow = ffcStateRegex.find(status)?.groupValues?.get(1)
        if (ffcStateNow != lastFfcState) {
            DiagLog.log("[ffc] state ${lastFfcState ?: "-"} -> ${ffcStateNow ?: "-"}; status: ${status.take(300)}")
            lastFfcState = ffcStateNow
        }
        if (ffcStateNow == FFC_STATE_PROGRESS) lastFfcActivityAt = now
        val shutterK = shutterRegex.find(status)?.groupValues?.get(1)?.toDoubleOrNull()
        val tele = ThermalImage.telemetry(frame)
        val fpaK = demoFpa ?: tele?.let { it[24] / 100.0 } // Lepton telemetry word 24: sensor temperature, centikelvin

        if (stats != null && !demo) {
            radiometry.onFrame(frame.isFfc, stats, shutterK, fpaK)
            logFrameStats(frame, stats, status, shutterK, tele, fpaK)
        }
        if (frameCount == 1 || frameCount % 100 == 0) {
            DiagLog.log(
                "Frame #$frameCount: thermal=${frame.thermalSize} jpg=${frame.jpgSize} fps=${String.format(
                    Locale.US,
                    "%.1f",
                    fps
                )} ffc=${frame.isFfc}"
            )
        }

        // The thermal content is not updated during the shutter calibration and on the next frame.
        var valid = false
        if (frame.isFfc) {
            ffcActive = true
            lastFfcActivityAt = now
        } else if (ffcActive) {
            ffcActive = false
            dropAfterFfc = true
        } else if (dropAfterFfc) {
            dropAfterFfc = false
        } else {
            valid = raw != null
        }
        processor?.submit(FrameSample(frame, raw, valid, fpaK))
    }

    /** One CSV row: raw frame statistics, shutter temperature, Lepton telemetry words 20..31 and the computed temperatures. */
    private fun logFrameStats(frame: FlirFrame, s: ThermalStats, status: String, shutterK: Double?, tele: IntArray?, fpaK: Double?) {
        val ffcState = ffcStateRegex.find(status)?.groupValues?.get(1) ?: ""
        val tw = if (tele != null) (20..31).joinToString(",") { tele[it].toString() } else ",,,,,,,,,,,"
        val wall = java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(java.util.Date())
        fun t(raw: Int) = fpaK?.let { f -> radiometry.celsius(raw, f) }?.let { String.format(Locale.US, "%.2f", it) } ?: ""
        val anchorRaw = radiometry.anchor?.raw?.let { String.format(Locale.US, "%.0f", it) } ?: ""
        statsLog?.log(
            "$wall,$frameCount,${if (frame.isFfc) 1 else 0},$ffcState,${shutterK ?: ""},${s.min},${s.max},${s.mean},${s.center},$tw," +
                "${t(s.center)},${t(s.min)},${t(s.max)},$anchorRaw"
        )
    }

    // ---------------------------------------------------------------- top bar, messages

    override fun onCrosshairMoved(u: Float, v: Float) {
        ui.removeCallbacks(saveSettingsRunnable)
        ui.postDelayed(saveSettingsRunnable, 800)
    }

    override fun onTap() {
        if (controlsHidden) setControlsHidden(false)
    }

    internal fun setControlsHidden(hidden: Boolean) {
        controlsHidden = hidden
        val v = if (hidden) View.GONE else View.VISIBLE
        topBar.visibility = v
        bottomBar.visibility = v
        if (hidden) {
            hideCards()
            toast(getString(R.string.toast_controls_hidden))
        }
    }

    /** Frame rate and battery in the top-right pill (shown only while frames are coming). */
    private fun updateTopBar() {
        val streaming = processor != null && frameCount > 0
        statusPill.visibility = if (streaming) View.VISIBLE else View.INVISIBLE
        val b = battery
        val pct = b.percentage
        if (pct != null) {
            batteryIcon.visibility = View.VISIBLE
            batteryText.visibility = View.VISIBLE
            batteryDrawable.update(pct, b.charging == true)
            batteryText.text = getString(R.string.battery_fmt, pct)
        } else {
            batteryIcon.visibility = View.GONE
            batteryText.visibility = View.GONE
        }
        fpsText.text = getString(R.string.fps_fmt, fmt1(fps.toDouble()))
        recorder?.takeIf { it.isRecording }?.let { r ->
            val sec = r.elapsedMs() / 1000
            videoTimer.text = String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
        }
    }

    /** Message shown in the middle of the empty screen (no picture yet). */
    private fun showEmptyMessage(resId: Int) {
        message = getString(resId)
        ui.post { updateHud() }
    }

    private fun updateHud() {
        val overlayOpen = settingsPanel.isShown || calCard.visibility == View.VISIBLE || alignCard.visibility == View.VISIBLE
        thermalView.hud = Hud(message, demo, showNotes = !overlayOpen)
    }

    internal fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- photo and video

    private fun takePhoto() {
        val p = processor
        if (p == null || thermalView.currentFrame() == null) {
            toast(getString(R.string.toast_no_image))
            return
        }
        sound.play(MediaActionSound.SHUTTER_CLICK)
        p.requestPhoto()
    }

    private fun toggleRecording() {
        if (recorder != null) {
            stopRecording(wait = false)
            return
        }
        val p = processor
        if (p == null || thermalView.currentFrame() == null) {
            toast(getString(R.string.toast_no_image))
            return
        }
        val rotated = settings.rotation % 180 != 0
        val rec = VideoRecorder(applicationContext, if (rotated) VIDEO_H else VIDEO_W, if (rotated) VIDEO_W else VIDEO_H)
        if (!rec.start()) {
            toast(getString(R.string.toast_video_start_failed))
            return
        }
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
        recorder = rec
        p.recorder = rec
        refreshRecordingUi()
    }

    private fun stopRecording(wait: Boolean) {
        val rec = recorder ?: return
        recorder = null
        processor?.recorder = null
        sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        val finish = {
            val name = rec.stop()
            runOnUiThread {
                toast(if (name != null) getString(R.string.toast_video_saved_fmt, name) else getString(R.string.toast_video_failed))
            }
        }
        if (wait) finish() else Thread(finish, "flir-rec-stop").start()
        refreshRecordingUi()
    }

    /** The video button turns red with a stop icon and a timer while recording. */
    internal fun refreshRecordingUi(rec: Boolean = recorder != null) {
        btnVideo.background = ContextCompat.getDrawable(this, if (rec) R.drawable.bg_round_button_rec else R.drawable.bg_round_button)
        videoIcon.setImageResource(if (rec) R.drawable.ic_stop else R.drawable.ic_videocam)
        videoTimer.visibility = if (rec) View.VISIBLE else View.GONE
        if (rec) videoTimer.text = "0:00"
        btnVideo.contentDescription = getString(if (rec) R.string.cd_video_stop else R.string.cd_video)
    }

    // ---------------------------------------------------------------- settings actions

    internal fun currentLanguageTag(): String = if (appLocale().language == "uk") "uk" else "en"

    internal fun setLanguage(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    private val debuggable: Boolean
        get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Developer tool (debuggable builds only): sends raw JSON commands to the camera once frames are flowing, so that the
     * camera's reactions can be studied from adb. Commands are separated by "@@"; the gap between them is in milliseconds.
     */
    private fun handleDebugIntent(i: Intent) {
        if (!debuggable) return
        val raw = i.getStringExtra(EXTRA_SEND_JSON) ?: return
        i.removeExtra(EXTRA_SEND_JSON)
        val gap = i.getIntExtra(EXTRA_SEND_GAP, 3000).toLong()
        val commands = raw.split("@@").map { it.trim() }.filter { it.isNotEmpty() }
        Thread({
            val end = SystemClock.elapsedRealtime() + 20_000
            while (SystemClock.elapsedRealtime() < end && !(camera?.isRunning == true && frameCount > 0)) Thread.sleep(200)
            val cam = camera
            if (cam == null) {
                DiagLog.log("[debug] no camera to send to")
                return@Thread
            }
            for (c in commands) {
                val ok = cam.sendJson(c)
                DiagLog.log("[debug] sent $c -> $ok")
                Thread.sleep(gap)
            }
        }, "flir-debug-send").start()
    }

    internal fun openDiagnostics() {
        startActivity(Intent(this, DiagnosticsActivity::class.java))
    }

    internal fun saveRawFrame() {
        val f = lastFrame
        val name = if (f != null) FrameStore.save(this, f, "manual") else null
        toast(if (name != null) getString(R.string.toast_raw_saved_fmt, name) else getString(R.string.toast_raw_failed))
    }

    // ---------------------------------------------------------------- manual shutter calibration

    /**
     * Makes the camera close its shutter and recalibrate now. The command is not publicly documented, so the first time the app
     * searches for it (see [ShutterCalibration]); once found it is remembered and used directly.
     */
    internal fun requestShutterCalibration() {
        val cam = camera
        if (demo || cam == null || !cam.isRunning || frameCount == 0) {
            toast(getString(R.string.ffc_not_connected))
            return
        }
        if (ffcProbeRunning || ffcInProgress()) {
            toast(getString(R.string.ffc_busy))
            return
        }
        val known = settings.ffcCommand
        if (known != null) {
            val t0 = SystemClock.elapsedRealtime()
            val sent = cam.sendJson(known)
            DiagLog.log("[ffc] manual request $known -> $sent (camera state: $lastFfcState)")
            toast(getString(R.string.ffc_requested))
            ui.postDelayed({
                if (camera === cam && lastFfcActivityAt < t0) {
                    DiagLog.log("[ffc] the camera did not start a calibration (state: $lastFfcState)")
                    toast(getString(R.string.ffc_no_reaction))
                }
            }, ShutterCalibration.RESPONSE_WINDOW_MS + 1000)
            return
        }
        MaterialAlertDialogBuilder(this).setTitle(R.string.ffc_probe_title)
            .setMessage(R.string.ffc_probe_message)
            .setPositiveButton(R.string.btn_start) { _, _ -> startShutterCommandSearch(cam) }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    /** True while the shutter is closed or a calibration has just started. */
    private fun ffcInProgress(): Boolean =
        ffcActive || lastFfcState == FFC_STATE_PROGRESS || SystemClock.elapsedRealtime() - lastFfcActivityAt < 800

    /** True while the camera itself asks for a calibration. */
    private fun ffcWanted(): Boolean = lastFfcState == FFC_STATE_DESIRED || lastFfcState == FFC_STATE_VALID_IMG

    private fun startShutterCommandSearch(cam: FlirCamera) {
        ffcProbeCancelled = false
        ffcProbeRunning = true
        DiagLog.log("[ffc] searching for the shutter-calibration command (${ShutterCalibration.CANDIDATES.size} candidates)")
        val progress = MaterialAlertDialogBuilder(this).setTitle(R.string.ffc_probe_title)
            .setMessage(R.string.ffc_probe_running)
            .setCancelable(false)
            .setNegativeButton(R.string.btn_cancel) { _, _ -> ffcProbeCancelled = true }
            .show()
        Thread({
            val result = try {
                ShutterCalibration.probe(
                    candidates = ShutterCalibration.CANDIDATES,
                    send = { cam.sendJson(it) },
                    ffcStartedSince = { lastFfcActivityAt >= it },
                    ffcInProgress = { ffcInProgress() },
                    calibrationWanted = { ffcWanted() },
                    now = { SystemClock.elapsedRealtime() },
                    sleep = { Thread.sleep(it) },
                    log = { DiagLog.log("[ffc] $it") },
                    isCancelled = { ffcProbeCancelled || camera !== cam }
                )
            } finally {
                ffcProbeRunning = false
            }
            DiagLog.log("[ffc] search finished: ${result.outcome}, tried ${result.tried}, command ${result.command}")
            runOnUiThread {
                if (!isDestroyed) progress.dismiss()
                if (result.cancelled || isFinishing || isDestroyed) return@runOnUiThread
                val found = result.command
                if (found != null) {
                    settings.ffcCommand = found
                    settings.save()
                }
                val text = when (result.outcome) {
                    ShutterCalibration.Outcome.FOUND -> getString(R.string.ffc_probe_found_fmt, found)
                    ShutterCalibration.Outcome.NOT_FOUND -> getString(R.string.ffc_result_not_found_fmt, result.tried)
                    ShutterCalibration.Outcome.NEVER_REQUESTED -> getString(R.string.ffc_result_never)
                    ShutterCalibration.Outcome.AUTO_NOT_STOPPED -> getString(R.string.ffc_result_auto)
                    ShutterCalibration.Outcome.CAMERA_BUSY -> getString(R.string.ffc_busy)
                    ShutterCalibration.Outcome.COULD_NOT_SEND -> getString(R.string.ffc_result_send)
                    ShutterCalibration.Outcome.CANCELLED -> ""
                }
                MaterialAlertDialogBuilder(this).setTitle(R.string.ffc_probe_title).setMessage(text)
                    .setPositiveButton(R.string.btn_ok, null).show()
            }
        }, "flir-ffc-probe").start()
    }

    /**
     * A search that was cut short (the app was closed in the middle, the cable was pulled) may have left the camera's automatic
     * calibration switched off, so it is switched on once for every camera session.
     */
    private fun ensureAutoTuning() {
        if (autoTuningEnsured || demo || ffcProbeRunning) return
        val cam = camera ?: return
        if (!cam.isRunning || frameCount == 0) return
        autoTuningEnsured = true
        Thread({ DiagLog.log("[ffc] automatic calibration ensured: ${cam.sendJson(ShutterCalibration.AUTO_ON)}") }, "flir-auto-on").start()
    }

    private val emissivityPresets = listOf(
        R.string.emis_none to 1.00,
        R.string.emis_skin to 0.98,
        R.string.emis_water to 0.96,
        R.string.emis_plastic to 0.95,
        R.string.emis_masonry to 0.93,
        R.string.emis_glass to 0.92,
        R.string.emis_wood to 0.90,
        R.string.emis_oxidized to 0.80,
        R.string.emis_shiny to 0.20
    )

    internal fun openEmissivityDialog() {
        val labels =
            emissivityPresets.map { String.format(Locale.US, "%.2f — %s", it.second, getString(it.first)) } +
                getString(R.string.emis_custom)
        val current = emissivityPresets.indexOfFirst { abs(it.second - settings.emissivity) < 0.005 }.let {
            if (it <
                0
            ) {
                labels.lastIndex
            } else {
                it
            }
        }
        MaterialAlertDialogBuilder(this).setTitle(R.string.emis_title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { d, which ->
                d.dismiss()
                if (which < emissivityPresets.size) {
                    setEmissivity(emissivityPresets[which].second)
                } else {
                    promptNumber(getString(R.string.emis_custom_title), null, String.format(Locale.US, "%.2f", settings.emissivity)) {
                        setEmissivity(it)
                    }
                }
            }.setNegativeButton(R.string.btn_cancel, null).show()
    }

    private fun setEmissivity(e: Double) {
        settings.emissivity = e.coerceIn(Radiometry.MIN_EMISSIVITY, 1.0)
        radiometry.emissivity = settings.emissivity
        settings.save()
    }

    internal fun openReflectedDialog() {
        promptNumber(
            getString(R.string.reflected_title),
            getString(R.string.reflected_hint),
            String.format(Locale.US, "%.0f", settings.reflectedC)
        ) {
            settings.reflectedC = it.coerceIn(-50.0, 200.0)
            radiometry.reflectedC = settings.reflectedC
            settings.save()
        }
    }

    private fun promptNumber(title: String, hint: String?, initial: String, onOk: (Double) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(initial)
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        val b = MaterialAlertDialogBuilder(this).setTitle(title).setView(box)
        if (hint != null) b.setMessage(hint)
        b.setPositiveButton(R.string.btn_ok) { _, _ ->
            val v = input.text.toString().replace(',', '.').toDoubleOrNull()
            if (v == null) toast(getString(R.string.err_invalid_number)) else onOk(v)
        }.setNegativeButton(R.string.btn_cancel, null).show()
    }

    // ---------------------------------------------------------------- edge alignment card

    private fun hideCards() {
        calCard.visibility = View.GONE
        alignCard.visibility = View.GONE
    }

    internal fun openAlignmentCard() {
        settings.mode = DisplayMode.MSX
        settings.save()
        hideCards()
        alignCard.visibility = View.VISIBLE
        alignChange()
    }

    private fun alignChange(dx: Float = 0f, dy: Float = 0f, ds: Float = 0f) {
        val a = settings.alignment
        settings.alignment = a.copy(
            scale = (a.scale + ds).coerceIn(0.5f, 1.5f),
            dx = (a.dx + dx).coerceIn(-0.4f, 0.4f),
            dy = (a.dy + dy).coerceIn(-0.4f, 0.4f)
        )
        settings.save()
        val n = settings.alignment
        val f = { x: Float -> String.format(appLocale(), "%+.3f", x) }
        alignInfo.text = getString(R.string.align_info_fmt, f(n.dx), f(n.dy), String.format(appLocale(), "%.3f", n.scale))
        thermalView.postInvalidateOnAnimation()
    }

    // ---------------------------------------------------------------- calibration card

    internal fun openCalibrationCard() {
        hideCards()
        calCard.visibility = View.VISIBLE
        updateCalText()
    }

    internal fun updateCalText() {
        val vf = thermalView.currentFrame()?.second
        val live = vf?.cross?.tempC?.let { String.format(appLocale(), "%.1f °C", it) } ?: "—"
        val pts = settings.calibrationPoints
        val fit = TempCalibration.fit(pts)
        val mode = when {
            pts.isEmpty() -> getString(R.string.cal_mode_factory)
            fit.fittedSlope -> getString(R.string.cal_mode_fit_fmt, pts.size)
            else -> getString(R.string.cal_mode_offset_fmt, pts.size)
        }
        val eps = String.format(appLocale(), "%.2f", settings.emissivity)
        calText.text = getString(R.string.cal_hint) + "\n" +
            getString(R.string.cal_live_fmt, live, eps) + "\n" +
            getString(R.string.cal_params_fmt, fmt1(fit.countsPerPhi), String.format(appLocale(), "%+.0f", fit.offsetCounts), mode)
    }

    private fun calibrationAddPoint() {
        val s = processor?.calibrationSample()
        if (s == null) {
            toast(getString(R.string.cal_no_data))
            return
        }
        promptNumber(
            getString(R.string.cal_prompt_title),
            getString(R.string.cal_prompt_hint),
            s.tempC?.let { String.format(Locale.US, "%.1f", it) } ?: ""
        ) { addCalibrationPoint(it, s) }
    }

    /** Adds a reference point, refits the model and saves. Returns a description of the result. */
    internal fun addCalibrationPoint(trueC: Double, s: CalSample): String {
        val pt = radiometry.calibrationPoint(s.relative, s.phiShutter, trueC, radiometry.emissivity, radiometry.reflectedC)
        settings.calibrationPoints = (settings.calibrationPoints + pt).takeLast(TempCalibration.MAX_POINTS)
        settings.applyTo(radiometry)
        settings.save()
        val fit = TempCalibration.fit(settings.calibrationPoints)
        val text = getString(
            R.string.cal_added_fmt,
            fmt1(fit.countsPerPhi),
            String.format(appLocale(), "%+.0f", fit.offsetCounts),
            getString(if (fit.fittedSlope) R.string.cal_fit_full else R.string.cal_fit_offset_only)
        )
        DiagLog.log("Calibration: $text; points ${settings.calibrationPoints.size}")
        toast(text)
        updateCalText()
        return text
    }

    internal fun calibrationReset() {
        settings.calibrationPoints = emptyList()
        settings.applyTo(radiometry)
        settings.save()
        toast(getString(R.string.cal_reset_done))
        updateCalText()
    }

    // ---------------------------------------------------------------- helpers for the self-test

    internal fun onUiAndWait(block: () -> Unit) {
        val latch = CountDownLatch(1)
        runOnUiThread {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }

    internal fun refreshForTest() {
        updateTopBar()
        updateHud()
    }

    internal fun openSettingsForTest(open: Boolean) {
        if (open) settingsPanel.show() else settingsPanel.hide()
    }

    internal fun hideCardsForTest() = hideCards()

    companion object {
        const val EXTRA_DEMO = "demo"
        const val EXTRA_SELFTEST = "selftest"
        const val EXTRA_SELFTEST_LANG = "selftest_lang"
        const val EXTRA_SEND_JSON = "send_json"
        const val EXTRA_SEND_GAP = "send_gap_ms"
        private const val ACTION_PERMISSION = "com.exciteddev.flironepro.USB_PERMISSION_STREAM"
        private const val FFC_STATE_PROGRESS = "FFC_PROGRESS"
        private const val FFC_STATE_DESIRED = "FFC_DESIRED"
        private const val FFC_STATE_VALID_IMG = "FFC_VALID_IMG"
        private const val VIDEO_W = 640
        private const val VIDEO_H = 480
    }
}
