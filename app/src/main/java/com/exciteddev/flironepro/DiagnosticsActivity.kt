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

/** USB diagnostics: lists all USB devices, asks for permission for the FLIR camera and saves the report to a file. */
class DiagnosticsActivity : AppCompatActivity() {

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
                    logEvent("Permission for ${device?.deviceName}: ${if (granted) "GRANTED" else "DENIED"}")
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    device?.deviceName?.let { permissionDenied.remove(it) }
                    logEvent("Attached: ${device?.deviceName}")
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    device?.deviceName?.let {
                        permissionRequested.remove(it)
                        permissionDenied.remove(it)
                    }
                    logEvent("Detached: ${device?.deviceName}")
                }
            }
            scan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.diag_title)
        DiagLog.init(this)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        reportView = findViewById(R.id.reportView)

        findViewById<Button>(R.id.btnScan).setOnClickListener {
            permissionDenied.clear()
            scan()
        }
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("USB report", reportView.text))
            Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
        }

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        scan()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        unregisterReceiver(usbReceiver)
        super.onDestroy()
    }

    private fun scan() {
        val devices = usbManager.deviceList.values.sortedBy { it.deviceName }
        val sb = StringBuilder()
        sb.appendLine("FLIR ONE Pro App - USB report, ${timestamp()}")
        sb.appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("USB devices: ${devices.size}")
        sb.appendLine()

        if (events.isNotEmpty()) {
            sb.appendLine("--- Events ---")
            events.forEach { sb.appendLine(it) }
            sb.appendLine()
        }

        if (devices.isEmpty()) {
            sb.appendLine("No USB devices found. Connect the camera to the phone's USB-C port.")
        }

        for (device in devices) {
            val needsPermission = device.vendorId == UsbReport.FLIR_VENDOR_ID &&
                !usbManager.hasPermission(device) &&
                device.deviceName !in permissionDenied &&
                permissionRequested.add(device.deviceName)
            if (needsPermission) requestPermission(device)

            sb.appendLine(runCatching { UsbReport.describe(usbManager, device) }
                .getOrElse { "!! Error describing ${device.deviceName}: $it\n" })
        }

        val text = sb.toString()
        reportView.text = text
        Log.i(TAG, text)
        // Do not overwrite a report that contains a device with an empty scan (e.g. after the camera was unplugged).
        if (devices.isNotEmpty()) {
            runCatching { File(filesDir, REPORT_FILE).writeText(text) }
                .onFailure { Log.w(TAG, "Could not save the report", it) }
        }
    }

    private fun requestPermission(device: UsbDevice) {
        logEvent("Requesting permission for ${device.deviceName}")
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
        // The system adds extras (EXTRA_DEVICE, EXTRA_PERMISSION_GRANTED) to the intent, hence FLAG_MUTABLE.
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
