package com.exciteddev.flironepro

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Етап 0: показує всі USB-пристрої, просить дозвіл для FLIR і зберігає звіт у файл. */
class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private lateinit var reportView: TextView

    private val events = ArrayDeque<String>()
    private val permissionRequested = HashSet<String>()
    private val permissionDenied = HashSet<String>()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    device?.deviceName?.let {
                        permissionRequested.remove(it)
                        if (!granted) permissionDenied.add(it)
                    }
                    logEvent("Дозвіл для ${device?.deviceName}: ${if (granted) "НАДАНО" else "ВІДХИЛЕНО"}")
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    device?.deviceName?.let { permissionDenied.remove(it) }
                    logEvent("Підключено: ${device?.deviceName}")
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    device?.deviceName?.let {
                        permissionRequested.remove(it)
                        permissionDenied.remove(it)
                    }
                    logEvent("Відключено: ${device?.deviceName}")
                }
            }
            scan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        DiagLog.init(this)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        reportView = findViewById(R.id.reportView)

        findViewById<Button>(R.id.btnScan).setOnClickListener {
            permissionDenied.clear()
            scan()
        }
        findViewById<Button>(R.id.btnLive).setOnClickListener {
            startActivity(Intent(this, StreamActivity::class.java))
        }
        findViewById<Button>(R.id.btnDemo).setOnClickListener {
            startActivity(Intent(this, StreamActivity::class.java).putExtra(StreamActivity.EXTRA_DEMO, true))
        }
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("USB report", reportView.text))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            logEvent("Застосунок запущено через підключення USB-пристрою")
        }
        scan()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            logEvent("Повторний запуск через підключення USB-пристрою")
        }
        scan()
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    private fun scan() {
        val devices = usbManager.deviceList.values.sortedBy { it.deviceName }
        val sb = StringBuilder()
        sb.appendLine("FLIR ONE Pro App — USB-звіт, ${timestamp()}")
        sb.appendLine("Телефон: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("USB-пристроїв: ${devices.size}")
        sb.appendLine()

        if (events.isNotEmpty()) {
            sb.appendLine("--- Події ---")
            events.forEach { sb.appendLine(it) }
            sb.appendLine()
        }

        if (devices.isEmpty()) {
            sb.appendLine("USB-пристроїв не знайдено. Підключіть камеру в USB-C порт телефону.")
        }

        for (device in devices) {
            val needsPermission = device.vendorId == UsbReport.FLIR_VENDOR_ID &&
                !usbManager.hasPermission(device) &&
                device.deviceName !in permissionDenied &&
                permissionRequested.add(device.deviceName)
            if (needsPermission) requestPermission(device)

            sb.appendLine(runCatching { UsbReport.describe(usbManager, device) }
                .getOrElse { "!! Помилка опису ${device.deviceName}: $it\n" })
        }

        val text = sb.toString()
        reportView.text = text
        Log.i(TAG, text)
        // Не затираємо звіт з пристроєм порожнім скануванням (наприклад, після відключення камери).
        if (devices.isNotEmpty()) {
            runCatching { File(filesDir, REPORT_FILE).writeText(text) }
                .onFailure { Log.w(TAG, "Не вдалося зберегти звіт", it) }
        }
    }

    private fun requestPermission(device: UsbDevice) {
        logEvent("Запит дозволу для ${device.deviceName}")
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        // Системі потрібно додати до інтенту extras (EXTRA_DEVICE, EXTRA_PERMISSION_GRANTED), тому FLAG_MUTABLE.
        val pending = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        usbManager.requestPermission(device, pending)
    }

    private fun logEvent(message: String) {
        val line = "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}  $message"
        Log.i(TAG, line)
        events.addLast(line)
        while (events.size > MAX_EVENTS) events.removeFirst()
    }

    private fun timestamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private companion object {
        const val TAG = "FlirDiag"
        const val REPORT_FILE = "usb_report.txt"
        const val MAX_EVENTS = 40
        const val ACTION_USB_PERMISSION = "com.exciteddev.flironepro.USB_PERMISSION"
    }
}
