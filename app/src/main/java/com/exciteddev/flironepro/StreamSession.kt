package com.exciteddev.flironepro

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/**
 * Starting and stopping the stream: finds the camera, asks for the USB permission, reacts to the camera being unplugged, and
 * stops everything that belongs to a running stream (the camera or demo, the recording, a calibration search).
 */
class StreamSession(
    private val activity: AppCompatActivity,
    private val usbManager: UsbManager,
    private val pipeline: StreamPipeline,
    private val hud: HudController,
    private val recording: RecordingController,
    private val ffc: FfcController,
    private val runMode: RunMode
) {
    private val ui = Handler(Looper.getMainLooper())
    private var permissionAsked = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    DiagLog.log("Permission (live view): ${if (granted) "granted" else "denied"}")
                    if (granted) startCamera() else hud.showMessage(R.string.msg_permission_denied)
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    if (device?.vendorId == UsbReport.FLIR_VENDOR_ID && !runMode.demo) {
                        DiagLog.log("Camera detached")
                        stop()
                        hud.showMessage(R.string.msg_camera_disconnected)
                    }
                }
            }
        }
    }

    init {
        pipeline.onConnectionLost = {
            ui.post {
                stop()
                hud.showMessage(R.string.msg_connection_lost)
            }
        }
    }

    /** Starts listening for the permission answer and for the camera being unplugged. */
    fun register() {
        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(activity, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun unregister() {
        activity.unregisterReceiver(receiver)
    }

    private fun findCamera(): UsbDevice? = usbManager.deviceList.values.firstOrNull { it.vendorId == UsbReport.FLIR_VENDOR_ID }

    /** Starts the camera if it is plugged in and the permission is there; otherwise asks for it or says what is missing. */
    fun ensureStarted() {
        val device = findCamera()
        if (device == null) {
            hud.showMessage(R.string.msg_no_camera)
            return
        }
        if (usbManager.hasPermission(device)) {
            startCamera()
        } else {
            requestPermission(device)
            hud.showMessage(R.string.msg_wait_permission)
        }
    }

    private fun requestPermission(device: UsbDevice) {
        if (permissionAsked) return
        permissionAsked = true
        val intent = Intent(ACTION_PERMISSION).setPackage(activity.packageName)
        val pending = PendingIntent.getBroadcast(activity, 0, intent, PendingIntent.FLAG_MUTABLE)
        DiagLog.log("Requesting permission for ${device.deviceName}")
        usbManager.requestPermission(device, pending)
    }

    fun startCamera() {
        val device = findCamera()
        if (pipeline.camera?.isRunning == true || device == null) return
        if (pipeline.startCamera(usbManager, device)) hud.showMessage(R.string.msg_wait_first_frame)
    }

    fun startDemo() {
        // The self-test feeds frames faster (20/s) to check the performance headroom.
        val src = pipeline.startDemo(if (runMode.selfTest) SELF_TEST_FRAME_MS else DemoSource.FRAME_MS) ?: return
        hud.showMessage(R.string.msg_demo_running)
        ui.postDelayed({
            if (pipeline.demoSource === src && src.frameCount == 0 && pipeline.frameCount == 0) hud.showMessage(R.string.msg_demo_no_frames)
        }, NO_DEMO_FRAMES_AFTER_MS)
    }

    fun stop() {
        ffc.cancelSearch()
        recording.stopRecording(wait = false)
        pipeline.stop()
    }

    private companion object {
        const val ACTION_PERMISSION = "com.exciteddev.flironepro.USB_PERMISSION_STREAM"
        const val SELF_TEST_FRAME_MS = 50L
        const val NO_DEMO_FRAMES_AFTER_MS = 1500L
    }
}
