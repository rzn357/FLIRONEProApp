package com.exciteddev.flironepro.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.exciteddev.flironepro.R
import com.exciteddev.flironepro.debug.DiagLog
import com.exciteddev.flironepro.pipeline.StreamPipeline
import com.exciteddev.flironepro.pipeline.startCamera
import com.exciteddev.flironepro.pipeline.stopRecording
import com.exciteddev.flironepro.usb.UsbReport

/**
 * Starting and stopping the stream: finds the camera, asks for the USB permission, reacts to the camera being unplugged, and
 * stops everything that belongs to a running stream (the camera or demo and the recording). It belongs to no screen (it works
 * with the application context) and tells the user what happens through the [StreamStatus].
 */
class StreamSession(
    private val context: Context,
    private val usbManager: UsbManager,
    private val pipeline: StreamPipeline,
    private val runMode: RunMode,
    private val status: StreamStatus
) {
    private val ui = Handler(Looper.getMainLooper())
    private var permissionAsked = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    DiagLog.log("Permission (live view): ${if (granted) "granted" else "denied"}")
                    if (granted) startCamera() else status.say(R.string.msg_permission_denied)
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    if (device?.vendorId == UsbReport.FLIR_VENDOR_ID && !runMode.demo) {
                        DiagLog.log("Camera detached")
                        stop()
                        status.say(R.string.msg_camera_disconnected)
                    }
                }
            }
        }
    }

    init {
        pipeline.onConnectionLost = {
            ui.post {
                stop()
                status.say(R.string.msg_connection_lost)
            }
        }
    }

    /** Starts listening for the permission answer and for the camera being unplugged. */
    fun register() {
        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun unregister() {
        context.unregisterReceiver(receiver)
    }

    private fun findCamera(): UsbDevice? = usbManager.deviceList.values.firstOrNull { it.vendorId == UsbReport.FLIR_VENDOR_ID }

    /** Starts the camera if it is plugged in and the permission is there; otherwise asks for it or says what is missing. */
    fun ensureStarted() {
        val device = findCamera()
        if (device == null) {
            status.say(R.string.msg_no_camera)
            return
        }
        if (usbManager.hasPermission(device)) {
            startCamera()
        } else {
            requestPermission(device)
            status.say(R.string.msg_wait_permission)
        }
    }

    private fun requestPermission(device: UsbDevice) {
        if (permissionAsked) return
        permissionAsked = true
        val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE)
        DiagLog.log("Requesting permission for ${device.deviceName}")
        usbManager.requestPermission(device, pending)
    }

    fun startCamera() {
        val device = findCamera()
        if (pipeline.camera?.isRunning == true || device == null) return
        if (pipeline.startCamera(usbManager, device)) status.say(R.string.msg_wait_first_frame)
    }

    fun stop() {
        pipeline.stopRecording()
        pipeline.stop()
    }

    private companion object {
        const val ACTION_PERMISSION = "com.exciteddev.flironepro.USB_PERMISSION_STREAM"
    }
}
