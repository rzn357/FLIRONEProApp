package com.exciteddev.flironepro

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import kotlin.math.abs

/**
 * Основний екран: живе зображення з трьома режимами (тепловізор / звичайна камера / MSX), рухомий хрестик із температурою,
 * маркери найгарячішої й найхолоднішої точок, фото й відео, налаштування випромінювальної здатності та калібрування температури.
 */
class StreamActivity : AppCompatActivity(), FlirCamera.Listener, ThermalView.Listener {

    private lateinit var usbManager: UsbManager
    private lateinit var thermalView: ThermalView
    private lateinit var bottomBar: View
    private lateinit var calPanel: View
    private lateinit var calText: TextView
    private lateinit var btnMode: Button
    private lateinit var btnPalette: Button
    private lateinit var btnVideo: Button
    private lateinit var settings: AppSettings

    private val ui = Handler(Looper.getMainLooper())
    private var camera: FlirCamera? = null
    private var processor: FrameProcessor? = null
    private var demoSource: DemoSource? = null
    private var radiometry = Radiometry()
    private var statsLog: FrameStatsLog? = null
    private var permissionAsked = false

    @Volatile private var battery = BatteryState()
    private var message: String? = null
    private var messageUntil = 0L
    private var recorder: VideoRecorder? = null
    private var demo = false
    private var selfTest = false
    private var uiHidden = false
    @Volatile private var lastFrame: FlirFrame? = null

    // Стан, який чіпає лише потік кадрів (USB або демо).
    private var frameCount = 0
    private var fpsStart = 0L
    private var fpsFrames = 0
    private var fps = 0f
    private var ffcActive = false
    private var dropAfterFfc = false
    private var demoFpa: Double? = null
    private val shutterRegex = Regex("\"shutterTemperature\"\\s*:\\s*([0-9.]+)")
    private val ffcStateRegex = Regex("\"ffcState\"\\s*:\\s*\"([A-Za-z_]+)\"")

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    DiagLog.log("Дозвіл (екран перегляду): ${if (granted) "надано" else "відхилено"}")
                    if (granted) startCamera() else showMessage("Дозвіл на доступ до камери відхилено", persistent = true)
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val d = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    if (d?.vendorId == UsbReport.FLIR_VENDOR_ID && !demo) {
                        DiagLog.log("Камеру відключено")
                        stopPipeline()
                        showMessage("Камеру відключено", persistent = true)
                    }
                }
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateHud()
            if (calPanel.visibility == View.VISIBLE) updateCalText()
            ui.postDelayed(this, 300)
        }
    }

    private val saveSettingsRunnable = Runnable { settings.save() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagLog.init(this)
        setContentView(R.layout.activity_stream)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        settings = AppSettings(this)
        settings.applyTo(radiometry)

        demo = intent.getBooleanExtra(EXTRA_DEMO, false)
        selfTest = intent.getBooleanExtra(EXTRA_SELFTEST, false)

        thermalView = findViewById(R.id.thermalView)
        thermalView.settings = settings
        thermalView.listener = this
        bottomBar = findViewById(R.id.bottomBar)
        calPanel = findViewById(R.id.calPanel)
        calText = findViewById(R.id.calText)
        btnMode = findViewById(R.id.btnMode)
        btnPalette = findViewById(R.id.btnPalette)
        btnVideo = findViewById(R.id.btnVideo)

        btnMode.setOnClickListener {
            settings.mode = settings.mode.next()
            settings.save()
            refreshButtons()
        }
        btnPalette.setOnClickListener {
            settings.palette = settings.palette.next()
            settings.save()
            refreshButtons()
        }
        findViewById<Button>(R.id.btnPhoto).setOnClickListener { takePhoto() }
        btnVideo.setOnClickListener { toggleRecording() }
        findViewById<Button>(R.id.btnMenu).setOnClickListener { showMenu() }
        findViewById<Button>(R.id.btnCalAdd).setOnClickListener { calibrationAddPoint() }
        findViewById<Button>(R.id.btnCalReset).setOnClickListener { calibrationReset() }
        findViewById<Button>(R.id.btnCalClose).setOnClickListener { calPanel.visibility = View.GONE }
        refreshButtons()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    calPanel.visibility == View.VISIBLE -> calPanel.visibility = View.GONE
                    uiHidden -> setUiHidden(false)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })

        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // Діагностичний самотест (запускається з adb): працює в демо-режимі й не залежить від видимості вікна.
        if (selfTest) {
            demo = true
            startDemo()
            runSelfTest()
        }
    }

    override fun onStart() {
        super.onStart()
        ui.post(ticker)
        if (selfTest) return
        if (demo) startDemo() else ensureStarted()
    }

    override fun onStop() {
        ui.removeCallbacks(ticker)
        if (!selfTest) stopPipeline()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        if (selfTest) stopPipeline()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        thermalView.postInvalidateOnAnimation()
    }

    private fun hideSystemBars() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    // ---------------------------------------------------------------- запуск/зупинка

    private fun ensureStarted() {
        val device = usbManager.deviceList.values.firstOrNull { it.vendorId == UsbReport.FLIR_VENDOR_ID }
        if (device == null) {
            showMessage("Камеру не знайдено. Підключіть FLIR ONE Pro до USB-C порту (або відкрийте демо в головному меню).", persistent = true)
            return
        }
        if (!usbManager.hasPermission(device)) {
            if (!permissionAsked) {
                permissionAsked = true
                val intent = Intent(ACTION_PERMISSION).setPackage(packageName)
                val pending = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_MUTABLE)
                DiagLog.log("Запит дозволу для ${device.deviceName}")
                usbManager.requestPermission(device, pending)
            }
            showMessage("Очікую дозвіл на доступ до камери…", persistent = true)
            return
        }
        startCamera()
    }

    private fun newPipeline() {
        resetFrameState()
        radiometry = Radiometry()
        settings.applyTo(radiometry)
        processor = FrameProcessor(applicationContext, settings, radiometry, thermalView).also {
            it.onPhotoSaved = { name ->
                runOnUiThread { toast(if (name != null) "Фото збережено: $name" else "Не вдалося зберегти фото") }
            }
            it.start()
        }
    }

    private fun startCamera() {
        if (camera?.isRunning == true) return
        val device = usbManager.deviceList.values.firstOrNull { it.vendorId == UsbReport.FLIR_VENDOR_ID } ?: return
        newPipeline()
        if (statsLog == null) statsLog = FrameStatsLog(this)
        DiagLog.log("=== Запуск потоку (${device.deviceName}) ===")
        val cam = FlirCamera(usbManager, device, this)
        camera = cam
        if (cam.start()) {
            showMessage("Очікую перший кадр…", persistent = true)
        } else {
            camera = null
            processor?.shutdown()
            processor = null
        }
    }

    private fun startDemo() {
        if (demoSource != null) return
        newPipeline()
        // Опорна точка для демо: взята з перевірених вимірювань (кімната/мороз).
        radiometry.setAnchor(Radiometry.Anchor(3845.0, 304.92, 305.21))
        demoFpa = 305.4
        DiagLog.log("=== Запуск демо ===")
        // У самотесті кадри подаються швидше (20/с), щоб перевірити запас продуктивності.
        val src = DemoSource(File(filesDir, "frames"), if (selfTest) 50L else DemoSource.FRAME_MS) { handleFrame(it) }
        demoSource = src
        src.start()
        showMessage("Демо: програються збережені кадри", persistent = false)
    }

    private fun stopPipeline() {
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
        demoFpa = null
        battery = BatteryState()
    }

    // ---------------------------------------------------------------- прийом кадрів (потік USB або демо)

    override fun onFrame(frame: FlirFrame) = handleFrame(frame)

    override fun onState(message: String) {
        DiagLog.log("Стан: $message")
        showMessage(message, persistent = true)
    }

    override fun onLost() {
        ui.post {
            stopPipeline()
            showMessage("Зв'язок із камерою втрачено (її відключено?). Підключіть камеру знову й відкрийте екран заново.", persistent = true)
        }
    }

    override fun onServiceMessage(json: String) {
        battery = ServiceMessages.updateBattery(battery, json)
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
        if (frameCount == 1) ui.post { clearPersistentMessage() }

        val raw = ThermalImage.decode(frame, IntArray(ThermalImage.W * ThermalImage.H))
        val stats = raw?.let { ThermalImage.stats(it) }
        val status = frame.statusText(400)
        val shutterK = shutterRegex.find(status)?.groupValues?.get(1)?.toDoubleOrNull()
        val tele = ThermalImage.telemetry(frame)
        val fpaK = demoFpa ?: tele?.let { it[24] / 100.0 } // слово 24 телеметрії Lepton: температура сенсора, сК

        if (stats != null && !demo) {
            radiometry.onFrame(frame.isFfc, stats, shutterK, fpaK)
            logFrameStats(frame, stats, status, shutterK, tele, fpaK)
        }
        if (frameCount == 1 || frameCount % 100 == 0) {
            DiagLog.log("Кадр #$frameCount: thermal=${frame.thermalSize} jpg=${frame.jpgSize} fps=${String.format(Locale.US, "%.1f", fps)} ffc=${frame.isFfc}")
        }

        // Під час FFC і на наступному кадрі тепловий вміст не оновлюємо.
        var valid = false
        if (frame.isFfc) {
            ffcActive = true
        } else if (ffcActive) {
            ffcActive = false
            dropAfterFfc = true
        } else if (dropAfterFfc) {
            dropAfterFfc = false
        } else {
            valid = raw != null
        }
        processor?.submit(FrameSample(frame, raw, valid, fpaK, fps))
    }

    /** Рядок CSV: сирі значення кадру, температура затвора, слова 20..31 телеметрії та обчислені температури. */
    private fun logFrameStats(frame: FlirFrame, s: ThermalStats, status: String, shutterK: Double?, tele: IntArray?, fpaK: Double?) {
        val ffcState = ffcStateRegex.find(status)?.groupValues?.get(1) ?: ""
        val tw = if (tele != null) (20..31).joinToString(",") { tele[it].toString() } else ",,,,,,,,,,,"
        val wall = java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(java.util.Date())
        fun t(raw: Int) = fpaK?.let { f -> radiometry.celsius(raw, f) }?.let { String.format(Locale.US, "%.2f", it) } ?: ""
        val anchorRaw = radiometry.anchor?.raw?.let { String.format(Locale.US, "%.0f", it) } ?: ""
        statsLog?.log(
            "$wall,$frameCount,${if (frame.isFfc) 1 else 0},$ffcState,${shutterK ?: ""},${s.min},${s.max},${s.mean},${s.center},$tw," +
                "${t(s.center)},${t(s.min)},${t(s.max)},$anchorRaw",
        )
    }

    // ---------------------------------------------------------------- екран і повідомлення

    override fun onCrosshairMoved(u: Float, v: Float) {
        ui.removeCallbacks(saveSettingsRunnable)
        ui.postDelayed(saveSettingsRunnable, 800)
    }

    override fun onTap() {
        if (uiHidden) setUiHidden(false)
    }

    private fun setUiHidden(hidden: Boolean) {
        uiHidden = hidden
        bottomBar.visibility = if (hidden) View.GONE else View.VISIBLE
        if (hidden) calPanel.visibility = View.GONE
        if (hidden) toast("Торкніться екрана, щоб повернути інтерфейс")
    }

    private fun refreshButtons() {
        btnMode.text = "Режим: " + when (settings.mode) {
            DisplayMode.MSX -> "MSX"
            DisplayMode.THERMAL -> "Тепло"
            DisplayMode.VISIBLE -> "Звичайна"
        }
        btnPalette.text = settings.palette.label
        btnPalette.isEnabled = settings.mode != DisplayMode.VISIBLE
        btnVideo.text = if (recorder != null) "■ Стоп" else "● Відео"
    }

    private fun showMessage(text: String, persistent: Boolean = false, ms: Long = 4000) {
        message = text
        messageUntil = if (persistent) Long.MAX_VALUE else SystemClock.elapsedRealtime() + ms
        ui.post { updateHud() }
    }

    private fun clearPersistentMessage() {
        if (messageUntil == Long.MAX_VALUE) message = null
        updateHud()
    }

    private fun updateHud() {
        val msg = message?.takeIf { SystemClock.elapsedRealtime() < messageUntil }
        thermalView.hud = Hud(battery, recorder?.takeIf { it.isRecording }?.elapsedMs(), msg, demo)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- фото й відео

    private fun takePhoto() {
        val p = processor
        if (p == null) {
            toast("Немає зображення для збереження")
            return
        }
        p.requestPhoto()
    }

    private fun toggleRecording() {
        if (recorder != null) {
            stopRecording(wait = false)
            return
        }
        val p = processor
        if (p == null) {
            toast("Немає зображення для запису")
            return
        }
        val rotated = settings.rotation % 180 != 0
        val rec = VideoRecorder(applicationContext, if (rotated) VIDEO_H else VIDEO_W, if (rotated) VIDEO_W else VIDEO_H)
        if (!rec.start()) {
            toast("Не вдалося почати запис відео")
            return
        }
        recorder = rec
        p.recorder = rec
        refreshButtons()
        showMessage("Запис відео розпочато")
    }

    private fun stopRecording(wait: Boolean) {
        val rec = recorder ?: return
        recorder = null
        processor?.recorder = null
        val finish = {
            val name = rec.stop()
            runOnUiThread { toast(if (name != null) "Відео збережено: $name" else "Відео не збережено (запис надто короткий?)") }
        }
        if (wait) finish() else Thread(finish, "flir-rec-stop").start()
        refreshButtons()
    }

    // ---------------------------------------------------------------- меню

    private fun showMenu() {
        val items = arrayOf(
            "Випромінювальна здатність: ${String.format(Locale.US, "%.2f", settings.emissivity)}",
            "Відбита температура: ${String.format(Locale.US, "%.0f", settings.reflectedC)} °C",
            "Калібрування температури…",
            "Сила контурів MSX: ${listOf("слабка", "середня", "сильна")[settings.msxLevel.coerceIn(0, 2)]}",
            "Вирівнювання MSX…",
            "Маркери гарячої/холодної точок: ${if (settings.showMarkers) "увімк." else "вимк."}",
            "Шкала температур: ${if (settings.showScale) "увімк." else "вимк."}",
            "Поворот зображення: ${settings.rotation}°",
            "Дзеркало: ${if (settings.mirror) "увімк." else "вимк."}",
            "Хрестик у центр",
            "Сховати інтерфейс",
            "Зберегти сирий кадр (для аналізу)",
        )
        AlertDialog.Builder(this).setTitle("Меню").setItems(items) { _, which ->
            when (which) {
                0 -> showEmissivityDialog()
                1 -> promptNumber("Відбита температура, °C", "Температура навколишніх предметів (зазвичай кімнатна).", settings.reflectedC.toString()) {
                    settings.reflectedC = it.coerceIn(-50.0, 200.0)
                    radiometry.reflectedC = settings.reflectedC
                    settings.save()
                }

                2 -> openCalibrationPanel()
                3 -> {
                    settings.msxLevel = (settings.msxLevel + 1) % 3
                    settings.save()
                    showMessage("Сила контурів: ${listOf("слабка", "середня", "сильна")[settings.msxLevel]}")
                }

                4 -> showAlignDialog()
                5 -> { settings.showMarkers = !settings.showMarkers; settings.save() }
                6 -> { settings.showScale = !settings.showScale; settings.save() }
                7 -> { settings.rotation = (settings.rotation + 90) % 360; settings.save() }
                8 -> { settings.mirror = !settings.mirror; settings.save() }
                9 -> { settings.crossU = 0.5f; settings.crossV = 0.5f; settings.save() }
                10 -> setUiHidden(true)
                11 -> {
                    val f = lastFrame
                    val name = if (f != null) FrameStore.save(this, f, "manual") else null
                    toast(if (name != null) "Збережено сирий кадр: $name" else "Не вдалося зберегти кадр")
                }
            }
        }.setNegativeButton("Закрити", null).show()
    }

    private val emissivityPresets = listOf(
        "Без поправки" to 1.00,
        "Шкіра людини" to 0.98,
        "Вода, лід" to 0.96,
        "Матовий пластик, гума, фарба" to 0.95,
        "Цегла, бетон, папір" to 0.93,
        "Скло" to 0.92,
        "Дерево, тканина" to 0.90,
        "Окиснений метал" to 0.80,
        "Блискучий метал (дуже неточно)" to 0.20,
    )

    private fun showEmissivityDialog() {
        val labels = emissivityPresets.map { String.format(Locale.US, "%.2f — %s", it.second, it.first) } + "Своє значення…"
        val current = emissivityPresets.indexOfFirst { abs(it.second - settings.emissivity) < 0.005 }.let { if (it < 0) labels.lastIndex else it }
        AlertDialog.Builder(this).setTitle("Випромінювальна здатність")
            .setSingleChoiceItems(labels.toTypedArray(), current) { d, which ->
                d.dismiss()
                if (which < emissivityPresets.size) {
                    setEmissivity(emissivityPresets[which].second)
                } else {
                    promptNumber("Своє значення (0.05–1.00)", null, String.format(Locale.US, "%.2f", settings.emissivity)) { setEmissivity(it) }
                }
            }.setNegativeButton("Скасувати", null).show()
    }

    private fun setEmissivity(e: Double) {
        settings.emissivity = e.coerceIn(Radiometry.MIN_EMISSIVITY, 1.0)
        radiometry.emissivity = settings.emissivity
        settings.save()
        showMessage(String.format(Locale.US, "Випромінювальна здатність: %.2f", settings.emissivity))
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
        val b = AlertDialog.Builder(this).setTitle(title).setView(box)
        if (hint != null) b.setMessage(hint)
        b.setPositiveButton("OK") { _, _ ->
            val v = input.text.toString().replace(',', '.').toDoubleOrNull()
            if (v == null) toast("Некоректне число") else onOk(v)
        }.setNegativeButton("Скасувати", null).show()
    }

    // ---------------------------------------------------------------- вирівнювання MSX

    private fun showAlignDialog() {
        settings.mode = DisplayMode.MSX
        refreshButtons()
        val info = TextView(this).apply { setPadding(dp(16), dp(12), dp(16), dp(4)) }
        fun refresh() {
            val a = settings.alignment
            info.text = String.format(Locale.US, "Зсув X %+.3f, зсув Y %+.3f, масштаб %.3f", a.dx, a.dy, a.scale)
            thermalView.postInvalidateOnAnimation()
        }
        fun change(dx: Float = 0f, dy: Float = 0f, ds: Float = 0f) {
            val a = settings.alignment
            settings.alignment = a.copy(scale = (a.scale + ds).coerceIn(0.5f, 1.5f), dx = (a.dx + dx).coerceIn(-0.4f, 0.4f), dy = (a.dy + dy).coerceIn(-0.4f, 0.4f))
            refresh()
        }
        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun row(vararg b: Button) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            b.forEach { addView(it) }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(info)
            addView(row(button("◀ вліво") { change(dx = 0.005f) }, button("вправо ▶") { change(dx = -0.005f) }))
            addView(row(button("▲ вгору") { change(dy = 0.005f) }, button("вниз ▼") { change(dy = -0.005f) }))
            addView(row(button("Контури − ") { change(ds = 0.01f) }, button("Контури +") { change(ds = -0.01f) }))
            addView(row(button("Скинути") { settings.alignment = Alignment(); refresh() }))
        }
        refresh()
        val d = AlertDialog.Builder(this).setTitle("Вирівнювання контурів")
            .setMessage("Стрілки зсувають контури видимої камери відносно теплової картинки.")
            .setView(col).setPositiveButton("Готово") { _, _ -> settings.save() }.create()
        d.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(android.view.Gravity.BOTTOM)
        }
        d.show()
    }

    // ---------------------------------------------------------------- калібрування температури

    private fun openCalibrationPanel() {
        calPanel.visibility = View.VISIBLE
        updateCalText()
    }

    private fun updateCalText() {
        val vf = thermalView.currentFrame()?.second
        val live = vf?.cross?.tempC?.let { String.format(Locale.US, "%.1f °C", it) } ?: "—"
        val pts = settings.calibrationPoints
        val fit = TempCalibration.fit(pts)
        val mode = if (pts.isEmpty()) "заводське" else if (fit.fittedSlope) "підгонка за ${pts.size} точками" else "зсув за ${pts.size} точк."
        calText.text = String.format(
            Locale.US,
            "Калібрування: наведіть хрестик на предмет з відомою температурою.\nЗараз під хрестиком: %s   (ε %.2f)\nМасштаб %.1f, зсув %+.0f — %s",
            live, settings.emissivity, fit.countsPerPhi, fit.offsetCounts, mode,
        )
    }

    private fun calibrationAddPoint() {
        val p = processor
        val s = p?.calibrationSample()
        if (s == null) {
            toast("Немає даних: дочекайтесь картинки та калібрування затвором")
            return
        }
        promptNumber("Справжня температура під хрестиком, °C", "Ви вимірюєте предмет під хрестиком — введіть показання вашого термометра.", s.tempC?.let { String.format(Locale.US, "%.1f", it) } ?: "") {
            addCalibrationPoint(it, s)
        }
    }

    /** Додає еталонну точку, підганяє модель, зберігає. Повертає опис результату. */
    fun addCalibrationPoint(trueC: Double, s: CalSample): String {
        val pt = radiometry.calibrationPoint(s.relative, s.phiShutter, trueC, radiometry.emissivity, radiometry.reflectedC)
        settings.calibrationPoints = (settings.calibrationPoints + pt).takeLast(TempCalibration.MAX_POINTS)
        settings.applyTo(radiometry)
        settings.save()
        val fit = TempCalibration.fit(settings.calibrationPoints)
        val text = String.format(Locale.US, "Еталон додано. Масштаб %.1f, зсув %+.0f (%s)", fit.countsPerPhi, fit.offsetCounts, if (fit.fittedSlope) "повна підгонка" else "лише зсув")
        DiagLog.log("Калібрування: $text; точок ${settings.calibrationPoints.size}")
        showMessage(text)
        updateCalText()
        return text
    }

    private fun calibrationReset() {
        settings.calibrationPoints = emptyList()
        settings.applyTo(radiometry)
        settings.save()
        showMessage("Калібрування скинуто до заводського")
        updateCalText()
    }

    // ---------------------------------------------------------------- самотест (adb)

    private fun onUiAndWait(block: () -> Unit) {
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

    private fun snapshotView(w: Int, h: Int, file: File) {
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        onUiAndWait {
            thermalView.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            thermalView.layout(0, 0, w, h)
            updateHud()
            thermalView.draw(Canvas(bm))
        }
        FileOutputStream(file).use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun runSelfTest() {
        Thread({
            try {
                DiagLog.log("SELFTEST: старт")
                Thread.sleep(3500)
                val dir = File(filesDir, "snapshots").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                for (rot in intArrayOf(0, 90)) {
                    for (m in DisplayMode.entries) {
                        onUiAndWait { settings.mode = m; settings.rotation = rot }
                        Thread.sleep(900)
                        val w = if (rot == 0) 1080 else 1080
                        val h = if (rot == 0) 1500 else 1500
                        snapshotView(w, h, File(dir, "view_${m.name}_$rot.png"))
                    }
                }
                // Продуктивність: по 4 с у кожному режимі, див. рядки «Обробка кадру» в лозі.
                for (m in DisplayMode.entries) {
                    onUiAndWait { settings.rotation = 0; settings.mode = m }
                    Thread.sleep(4500)
                }
                onUiAndWait { settings.mode = DisplayMode.MSX }
                Thread.sleep(600)

                // Калібрування: еталонна точка під хрестиком.
                val s = processor?.calibrationSample()
                if (s != null) {
                    var out = ""
                    onUiAndWait { out = addCalibrationPoint(s.tempC ?: 25.0, s) }
                    DiagLog.log("SELFTEST: калібрування: $out")
                    onUiAndWait { calibrationReset() }
                } else {
                    DiagLog.log("SELFTEST: немає зразка для калібрування")
                }

                // Фото (у приватний файл, не в галерею) та відео (у приватний файл).
                val frame = thermalView.currentFrame()
                if (frame != null) {
                    val bm = processor?.renderSnapshot(960, 720)
                    if (bm != null) FileOutputStream(File(dir, "photo.png")).use { bm.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                val rec = VideoRecorder(applicationContext, VIDEO_W, VIDEO_H, File(dir, "test.mp4"))
                if (rec.start()) {
                    processor?.recorder = rec
                    Thread.sleep(2500)
                    processor?.recorder = null
                    val name = rec.stop()
                    DiagLog.log("SELFTEST: відео: $name, кадрів=${rec.framesEncoded}, розмір=${File(dir, "test.mp4").length()}")
                } else {
                    DiagLog.log("SELFTEST: відео не стартувало")
                }

                // Дотик: перевіряємо перетворення координат на пристрої.
                onUiAndWait {
                    settings.rotation = 90
                    val t = thermalView
                    t.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1500, View.MeasureSpec.EXACTLY))
                    t.layout(0, 0, 1080, 1500)
                    val tr = ViewTransform(1080f, 1500f, 4f / 3f, 90, false)
                    val x = tr.toViewX(0.25f, 0.75f)
                    val y = tr.toViewY(0.25f, 0.75f)
                    val now = SystemClock.uptimeMillis()
                    val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
                    t.onTouchEvent(down)
                    down.recycle()
                    DiagLog.log("SELFTEST: дотик (0.25,0.75) через поворот 90° -> хрестик (${settings.crossU}, ${settings.crossV})")
                    settings.rotation = 0
                }
                // Відновлюємо налаштування за замовчуванням, щоб тест нічого не лишив у застосунку.
                onUiAndWait {
                    settings.mode = DisplayMode.MSX
                    settings.rotation = 0
                    settings.crossU = 0.5f
                    settings.crossV = 0.5f
                    settings.calibrationPoints = emptyList()
                    settings.applyTo(radiometry)
                    settings.save()
                }
                DiagLog.log("SELFTEST: завершено")
                runOnUiThread { finish() }
            } catch (e: Exception) {
                DiagLog.log("SELFTEST: помилка $e")
            }
        }, "flir-selftest").start()
    }

    companion object {
        const val EXTRA_DEMO = "demo"
        const val EXTRA_SELFTEST = "selftest"
        private const val ACTION_PERMISSION = "com.exciteddev.flironepro.USB_PERMISSION_STREAM"
        private const val VIDEO_W = 640
        private const val VIDEO_H = 480
    }
}
