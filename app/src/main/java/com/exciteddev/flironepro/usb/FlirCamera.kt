package com.exciteddev.flironepro.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.exciteddev.flironepro.debug.DiagLog
import com.exciteddev.flironepro.debug.ThrottledLog
import com.exciteddev.flironepro.protocol.FlirFrame
import com.exciteddev.flironepro.protocol.FlirFrameParser

/**
 * USB layer for the FLIR ONE (VID 09CB, PID 1996): opens the device, runs the start sequence (see [FlirUsb]) and reads the
 * frames and the service messages on background threads. Nothing is written to the camera's firmware or settings.
 */
class FlirCamera(private val manager: UsbManager, private val device: UsbDevice, private val listener: Listener) {
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
    private var connection: UsbDeviceConnection? = null
    private var claimed: List<UsbInterface> = emptyList()
    private var threads: List<Thread> = emptyList()
    private var commandEndpoint: UsbEndpoint? = null
    private var frameReader: FrameReader? = null
    private var frameParser: FlirFrameParser? = null
    private val parserEvents = ThrottledLog("Parser", EVENT_LOG_GAP_MS)

    internal val service = ServiceChannel { json -> listener.onServiceMessage(json) }

    /** The read-only experiment that reads CameraFiles.zip from the camera (not used by the app itself). */
    internal val filesProbe = CameraFilesProbe(this, service)

    val isRunning: Boolean get() = running

    /** True while commands can be written to the camera. */
    internal val canSend: Boolean get() = running && connection != null && commandEndpoint != null

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        val conn = manager.openDevice(device)
        if (conn == null) fail("openDevice() returned null (no permission?)")
        return conn != null && open(conn)
    }

    private fun open(conn: UsbDeviceConnection): Boolean {
        connection = conn
        val interfaces = FlirUsb.primaryInterfaces(device)
        val frames = FlirUsb.findEndpoint(interfaces[FlirUsb.VIDEO_INTERFACE], FlirUsb.EP_FRAMES)
        val serviceA = FlirUsb.findEndpoint(interfaces[FlirUsb.SERVICE_INTERFACE_A], FlirUsb.EP_SERVICE_A)
        val serviceB = FlirUsb.findEndpoint(interfaces[FlirUsb.SERVICE_INTERFACE_B], FlirUsb.EP_SERVICE_B)
        commandEndpoint = FlirUsb.findEndpoint(interfaces[FlirUsb.SERVICE_INTERFACE_A], FlirUsb.EP_COMMANDS)
        DiagLog.log(
            "Interfaces: ${interfaces.keys.sorted()}, EP85=${frames != null}, EP81=${serviceA != null}, " +
                "EP83=${serviceB != null}, EP02=${commandEndpoint != null}"
        )
        if (frames == null) {
            fail("Endpoint 0x85 not found in interface 2")
            closeConnection()
            return false
        }
        claimed = FlirUsb.claimAll(conn, interfaces)
        val streaming = FlirUsb.startVideo(conn)
        if (streaming) launchReaders(conn, frames, listOfNotNull(serviceA, serviceB)) else abortStart(conn)
        return streaming
    }

    private fun abortStart(conn: UsbDeviceConnection) {
        fail("Could not start the video stream (control transfer)")
        releaseAll(conn)
    }

    private fun launchReaders(conn: UsbDeviceConnection, frames: UsbEndpoint, serviceEndpoints: List<UsbEndpoint>) {
        val parser = FlirFrameParser(onFrame = { listener.onFrame(it) }, onEvent = parserEvents::log)
        val reader = FrameReader(conn, frames, parser, { running }, ::onLost, listener::onState)
        frameParser = parser
        frameReader = reader
        running = true
        val serviceThreads = serviceEndpoints.map { ep ->
            val serviceReader = ServiceReader(conn, ep, service) { running }
            Thread(serviceReader::run, "flir-poll${ep.address.toString(HEX)}")
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
        val conn = connection ?: return
        running = false
        threads.forEach { it.join(JOIN_MS) }
        threads = emptyList()
        FlirUsb.stopVideo(conn)
        releaseAll(conn)
        DiagLog.log(
            "Camera stopped. Chunks=${frameReader?.chunks}, bytes=${frameReader?.bytes}, timeouts=${frameReader?.timeouts}, " +
                "frames=${frameParser?.framesParsed}, resyncs=${frameParser?.resyncs}"
        )
    }

    /** Sends a framed JSON command on the service channel (EP 0x02). Returns true if both transfers were written. */
    fun sendJson(json: String): Boolean {
        val conn = connection
        val ep = commandEndpoint
        return running && conn != null && ep != null && FlirUsb.sendCommand(conn, ep, json)
    }

    private fun releaseAll(conn: UsbDeviceConnection) {
        for (itf in claimed) runCatching { conn.releaseInterface(itf) }
        claimed = emptyList()
        closeConnection()
    }

    private fun closeConnection() {
        runCatching { connection?.close() }
        connection = null
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
