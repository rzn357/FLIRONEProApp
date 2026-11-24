package com.exciteddev.flironepro.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.exciteddev.flironepro.debug.DiagLog
import com.exciteddev.flironepro.debug.ThrottledLog
import com.exciteddev.flironepro.protocol.FlirFrame
import com.exciteddev.flironepro.protocol.FlirFrameParser

/**
 * USB layer for the FLIR ONE (VID 09CB, PID 1996): opens the device, runs the start sequence (see [FlirUsb]) and reads the
 * frames and the service messages on background threads. Nothing is written to the camera's firmware or settings.
 *
 * The device is reached through a [UsbLink]; [now] is the clock the readers use to tell a timeout from an error (the tests
 * replace both).
 */
class FlirCamera internal constructor(
    private val opener: UsbOpener,
    private val listener: Listener,
    private val now: () -> Long = SystemClock::elapsedRealtime
) {
    constructor(manager: UsbManager, device: UsbDevice, listener: Listener) : this(androidOpener(manager, device), listener)

    interface Listener {
        fun onFrame(frame: FlirFrame)
        fun onState(message: String)

        /** The connection to the camera was lost (e.g. it was unplugged). Called from the reader thread. */
        fun onLost()

        /** A JSON message from the camera (battery, board information, etc.). Called from the receiving thread. */
        fun onServiceMessage(json: String) {}
    }

    @Volatile
    private var running = false
    private var link: UsbLink? = null
    private var claimed: List<Int> = emptyList()
    private var threads: List<Thread> = emptyList()
    private var hasCommandEndpoint = false
    private var frameReader: FrameReader? = null
    private var frameParser: FlirFrameParser? = null
    private val parserEvents = ThrottledLog("Parser", EVENT_LOG_GAP_MS)

    internal val service = ServiceChannel(now) { json -> listener.onServiceMessage(json) }

    /** The read-only experiment that reads CameraFiles.zip from the camera (not used by the app itself). */
    internal val filesProbe = CameraFilesProbe(this, service)

    val isRunning: Boolean get() = running

    /** True while commands can be written to the camera. */
    internal val canSend: Boolean get() = running && link != null && hasCommandEndpoint

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        val opened = opener.open()
        if (opened == null) fail("openDevice() returned null (no permission?)")
        return opened != null && open(opened)
    }

    private fun open(opened: UsbLink): Boolean {
        link = opened
        val hasFrames = FlirUsb.hasEndpoint(opened, FlirUsb.VIDEO_INTERFACE, FlirUsb.EP_FRAMES)
        val serviceEndpoints = listOf(
            FlirUsb.SERVICE_INTERFACE_A to FlirUsb.EP_SERVICE_A,
            FlirUsb.SERVICE_INTERFACE_B to FlirUsb.EP_SERVICE_B
        ).filter { (id, address) -> FlirUsb.hasEndpoint(opened, id, address) }.map { it.second }
        hasCommandEndpoint = FlirUsb.hasEndpoint(opened, FlirUsb.SERVICE_INTERFACE_A, FlirUsb.EP_COMMANDS)
        DiagLog.log(
            "Interfaces: ${opened.interfaces.keys.sorted()}, EP85=$hasFrames, EP81=${FlirUsb.EP_SERVICE_A in serviceEndpoints}, " +
                "EP83=${FlirUsb.EP_SERVICE_B in serviceEndpoints}, EP02=$hasCommandEndpoint"
        )
        if (!hasFrames) {
            fail("Endpoint 0x85 not found in interface 2")
            closeLink()
            return false
        }
        claimed = FlirUsb.claimAll(opened)
        val streaming = FlirUsb.startVideo(opened)
        if (streaming) launchReaders(opened, serviceEndpoints) else abortStart(opened)
        return streaming
    }

    private fun abortStart(opened: UsbLink) {
        fail("Could not start the video stream (control transfer)")
        releaseAll(opened)
    }

    private fun launchReaders(opened: UsbLink, serviceEndpoints: List<Int>) {
        val parser = FlirFrameParser(onFrame = { listener.onFrame(it) }, onEvent = parserEvents::log)
        val reader = FrameReader(opened, parser, { running }, ::onLost, listener::onState, now)
        frameParser = parser
        frameReader = reader
        running = true
        val serviceThreads = serviceEndpoints.map { endpoint ->
            val serviceReader = ServiceReader(opened, endpoint, service, { running }, now)
            Thread(serviceReader::run, "flir-poll${endpoint.toString(HEX)}")
        }
        threads = listOf(Thread(reader::run, "flir-frames")) + serviceThreads
        threads.forEach { it.start() }
        listener.onState("Stream started")
    }

    private fun onLost() {
        running = false
        listener.onLost()
    }

    @Synchronized
    fun stop() {
        val opened = link ?: return
        running = false
        threads.forEach { it.join(JOIN_MS) }
        threads = emptyList()
        FlirUsb.stopVideo(opened)
        releaseAll(opened)
        DiagLog.log(
            "Camera stopped. Chunks=${frameReader?.chunks}, bytes=${frameReader?.bytes}, timeouts=${frameReader?.timeouts}, " +
                "frames=${frameParser?.framesParsed}, resyncs=${frameParser?.resyncs}"
        )
    }

    /** Sends a framed JSON command on the service channel (EP 0x02). Returns true if both transfers were written. */
    fun sendJson(json: String): Boolean {
        val opened = link
        return running && opened != null && hasCommandEndpoint && FlirUsb.sendCommand(opened, json)
    }

    private fun releaseAll(opened: UsbLink) {
        for (id in claimed) runCatching { opened.releaseInterface(id) }
        claimed = emptyList()
        closeLink()
    }

    private fun closeLink() {
        runCatching { link?.close() }
        link = null
    }

    private fun fail(message: String) {
        DiagLog.log("ERROR: $message")
        listener.onState("Error: $message")
    }

    private companion object {
        const val JOIN_MS = 1500L
        const val EVENT_LOG_GAP_MS = 500L
        const val HEX = 16
    }
}
