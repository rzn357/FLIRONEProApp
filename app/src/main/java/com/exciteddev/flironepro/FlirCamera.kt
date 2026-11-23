package com.exciteddev.flironepro

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * USB layer for the FLIR ONE (VID 09CB, PID 1996).
 *
 * The start sequence reproduces the order known from the community protocol description:
 *   claim interfaces 0,1,2 -> SET_INTERFACE(alt 0) for 2 and 1 -> SET_INTERFACE(alt 1) for 1 ->
 *   SET_INTERFACE(alt 1) for 2 -> read frames from bulk IN 0x85.
 * Nothing is written to the camera's firmware or settings.
 */
class FlirCamera(
    private val manager: UsbManager,
    private val device: UsbDevice,
    private val listener: Listener,
) {
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
    private val claimed = ArrayList<UsbInterface>()
    private var frameThread: Thread? = null
    private var pollThread81: Thread? = null
    private var pollThread83: Thread? = null
    private var ep02: UsbEndpoint? = null

    /** JSON messages from 0x81/0x83 (except battery) for whoever waits for a reply to a command. */
    private val jsonQueue = java.util.concurrent.LinkedBlockingQueue<String>()
    @Volatile private var lastServiceDataAt = 0L
    private val messageCounts = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    /** If set, all service replies (0x81/0x83) are appended here as records [ep:1][t_ms:4][len:4][data]. */
    @Volatile
    private var capture: OutputStream? = null

    @Volatile var chunks = 0L
        private set
    @Volatile var bytes = 0L
        private set
    @Volatile var timeouts = 0L
        private set
    @Volatile var parser: FlirFrameParser? = null
        private set

    val isRunning: Boolean get() = running

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        val conn = manager.openDevice(device)
        if (conn == null) {
            fail("openDevice() returned null (no permission?)")
            return false
        }
        connection = conn

        val interfaces = HashMap<Int, UsbInterface>()
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            if (itf.alternateSetting == 0) interfaces[itf.id] = itf
        }
        val ep85 = findEndpoint(interfaces[2], 0x85)
        val ep81 = findEndpoint(interfaces[0], 0x81)
        val ep83 = findEndpoint(interfaces[1], 0x83)
        ep02 = findEndpoint(interfaces[0], 0x02)
        DiagLog.log("Interfaces: ${interfaces.keys.sorted()}, EP85=${ep85 != null}, EP81=${ep81 != null}, EP83=${ep83 != null}, EP02=${ep02 != null}")
        if (ep85 == null) {
            fail("Endpoint 0x85 not found in interface 2")
            closeConnection()
            return false
        }

        for (id in intArrayOf(0, 1, 2)) {
            val itf = interfaces[id] ?: continue
            val ok = conn.claimInterface(itf, true)
            DiagLog.log("claimInterface($id) = $ok")
            if (ok) claimed.add(itf)
        }

        // SET_INTERFACE control requests: bmRequestType 0x01, bRequest 0x0B, wValue = alt (0 stop / 1 start), wIndex = interface.
        control(conn, "stop interface 2", 0, 2, 0)
        control(conn, "stop interface 1", 0, 1, 0)
        control(conn, "start interface 1", 1, 1, 0)
        var r = control(conn, "start interface 2 (wLength=2)", 1, 2, 2)
        if (r < 0) r = control(conn, "start interface 2 (wLength=0)", 1, 2, 0)
        if (r < 0) {
            fail("Could not start the video stream (control transfer)")
            releaseAll(conn)
            return false
        }

        val p = FlirFrameParser(
            onFrame = { listener.onFrame(it) },
            onEvent = { logThrottled(it) },
        )
        parser = p
        chunks = 0; bytes = 0; timeouts = 0
        running = true

        frameThread = Thread({ frameLoop(conn, ep85, p) }, "flir-frames").also { it.start() }
        if (ep81 != null) pollThread81 = Thread({ pollLoop(conn, ep81, 0x81) }, "flir-poll81").also { it.start() }
        if (ep83 != null) pollThread83 = Thread({ pollLoop(conn, ep83, 0x83) }, "flir-poll83").also { it.start() }
        listener.onState("Stream started")
        return true
    }

    @Synchronized
    fun stop() {
        val conn = connection ?: return
        running = false
        frameThread?.join(1500)
        pollThread81?.join(1500)
        pollThread83?.join(1500)
        frameThread = null
        pollThread81 = null
        pollThread83 = null
        // Stop the stream the same way the start sequence begins.
        control(conn, "stop interface 2", 0, 2, 0)
        control(conn, "stop interface 1", 0, 1, 0)
        releaseAll(conn)
        DiagLog.log("Camera stopped. Chunks=$chunks, bytes=$bytes, timeouts=$timeouts, frames=${parser?.framesParsed}, resyncs=${parser?.resyncs}")
    }

    /**
     * EXPERIMENT (read-only): asks the camera to open and send the file CameraFiles.zip (probably holds the
     * calibration data) and records all raw replies from 0x81/0x83 into a file. Blocks for up to ~20 s, so run it from a background thread.
     */
    fun runCalibrationProbe(dir: File): String? {
        val conn = connection
        val ep = ep02
        if (!running || conn == null || ep == null) {
            DiagLog.log("[probe] the camera is not running or EP 0x02 is missing")
            return null
        }
        dir.mkdirs()
        val file = File(dir, "capture_${System.currentTimeMillis()}.bin")
        val out = FileOutputStream(file)
        captureStart = SystemClock.elapsedRealtime()
        messageCounts.clear()
        jsonQueue.clear()
        capture = out
        try {
            DiagLog.log("[probe] -> openFile CameraFiles.zip")
            sendCommand(conn, ep, FlirCommand.OPEN_CAMERA_FILES)

            // The camera replies with openFileStatus carrying a stream number; that number must be passed to readFile.
            var streamId: Int? = null
            val deadline = SystemClock.elapsedRealtime() + 3000
            while (streamId == null && SystemClock.elapsedRealtime() < deadline) {
                val msg = jsonQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                if ("openFileStatus" in msg) {
                    streamId = Regex("\"streamIdentifier\"\\s*:\\s*(-?\\d+)").find(msg)?.groupValues?.get(1)?.toIntOrNull()
                }
            }
            DiagLog.log("[probe] stream number from the camera reply: $streamId")
            val id = when {
                streamId != null && streamId >= 0 -> streamId.also { lastStreamId = it }
                else -> {
                    // A negative value means the file was already opened earlier in this session; try the last known number.
                    val fallback = lastStreamId
                    DiagLog.log("[probe] the camera did not give a new stream number, trying the last known one: $fallback")
                    fallback
                }
            }
            if (id == null) {
                DiagLog.log("[probe] no stream number - reconnect the camera and try again")
                return null
            }

            lastServiceDataAt = 0L
            DiagLog.log("[probe] -> readFile streamIdentifier=$id")
            sendCommand(conn, ep, FlirCommand.readFile(id))

            // Wait for data: up to 15 s, but stop once 2.5 s of silence follows the last data.
            val end = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < end) {
                Thread.sleep(250)
                val last = lastServiceDataAt
                if (last != 0L && SystemClock.elapsedRealtime() - last > 2500) break
            }
        } finally {
            capture = null
            synchronized(out) { runCatching { out.close() } }
        }
        DiagLog.log("[probe] done, ${file.length()} bytes in ${file.name}; messages by type: $messageCounts")
        return file.name
    }

    private var lastStreamId: Int? = null

    @Volatile private var captureStart = 0L

    /** Sends a framed JSON command on the service channel (EP 0x02). Returns true if both transfers were written. */
    fun sendJson(json: String): Boolean {
        val conn = connection
        val ep = ep02
        if (!running || conn == null || ep == null) return false
        return sendCommand(conn, ep, json)
    }

    private fun sendCommand(conn: UsbDeviceConnection, ep: UsbEndpoint, json: String): Boolean {
        val payload = FlirCommand.payload(json)
        val header = FlirCommand.header(payload.size)
        val r1 = conn.bulkTransfer(ep, header, header.size, 1000)
        val r2 = conn.bulkTransfer(ep, payload, payload.size, 1000)
        DiagLog.log("[command] ${payload.size} bytes: header -> $r1, payload -> $r2")
        return r1 >= 0 && r2 >= 0
    }

    private fun record(epAddress: Int, data: ByteArray, n: Int) {
        val out = capture ?: return
        val t = (SystemClock.elapsedRealtime() - captureStart).toInt()
        synchronized(out) {
            runCatching {
                out.write(epAddress)
                out.write(byteArrayOf(t.toByte(), (t shr 8).toByte(), (t shr 16).toByte(), (t shr 24).toByte()))
                out.write(byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte()))
                out.write(data, 0, n)
            }
        }
    }

    private fun frameLoop(conn: UsbDeviceConnection, ep: UsbEndpoint, p: FlirFrameParser) {
        val buf = ByteArray(CHUNK)
        var lastData = SystemClock.elapsedRealtime()
        var loggedChunks = 0
        var consecutiveErrors = 0
        var fastFailures = 0
        while (running) {
            val t0 = SystemClock.elapsedRealtime()
            val n = conn.bulkTransfer(ep, buf, buf.size, 100)
            val dt = SystemClock.elapsedRealtime() - t0
            if (n > 0) {
                chunks++
                bytes += n
                consecutiveErrors = 0
                fastFailures = 0
                lastData = SystemClock.elapsedRealtime()
                if (loggedChunks < 12) {
                    loggedChunks++
                    DiagLog.log("EP85 chunk #$chunks: $n bytes, start: ${UsbDescriptorParser.hex(buf, 0, minOf(n, 16))}")
                }
                p.feed(buf, n)
            } else {
                timeouts++
                consecutiveErrors++
                // A real timeout takes ~100 ms; an error that returns immediately means the connection is lost.
                fastFailures = if (dt < 20) fastFailures + 1 else 0
                if (fastFailures >= 25) {
                    DiagLog.log("Connection to the camera lost (bulkTransfer=$n immediately ${fastFailures} times in a row)")
                    running = false
                    listener.onLost()
                    break
                }
                if (consecutiveErrors == 1 || consecutiveErrors % 50 == 0) {
                    DiagLog.log("EP85 bulkTransfer=$n (timeout), in a row=$consecutiveErrors, without data ${SystemClock.elapsedRealtime() - lastData} ms")
                }
                if (consecutiveErrors == 30) listener.onState("No data from the camera for ~3 s")
            }
        }
    }

    /**
     * The camera sends service messages on 0x81 and 0x83; one thread per endpoint so file data is not lost.
     * Everything raw goes to capture (during the experiment), parsed messages are logged.
     */
    private fun pollLoop(conn: UsbDeviceConnection, ep: UsbEndpoint, epAddress: Int) {
        val buf = ByteArray(CHUNK)
        var logged = 0
        var batteryLogged = 0
        val parser = FlirMessageParser { type, payload ->
            messageCounts.merge(type, 1) { a, b -> a + b }
            val text = asText(payload, payload.size, 600)
            val isBattery = "batteryVoltageUpdate" in text || "batteryChargingCurrentUpdate" in text || "batteryChargingStateUpdate" in text
            if (payload.isNotEmpty() && payload[0] == '{'.code.toByte()) {
                runCatching { listener.onServiceMessage(String(payload, Charsets.UTF_8)) }
            }
            if (!isBattery) {
                lastServiceDataAt = SystemClock.elapsedRealtime()
                if (payload.isNotEmpty() && payload[0] == '{'.code.toByte()) jsonQueue.offer(String(payload, Charsets.UTF_8))
            }
            if (!isBattery || batteryLogged++ < 2) {
                if (logged++ < 60) {
                    DiagLog.log("EP${epAddress.toString(16)} message type=$type len=${payload.size} hex=${UsbDescriptorParser.hex(payload, 0, minOf(payload.size, 24))} text: $text")
                }
            }
        }
        var fastFailures = 0
        while (running) {
            val t0 = SystemClock.elapsedRealtime()
            val n = conn.bulkTransfer(ep, buf, buf.size, 100)
            if (n > 0) {
                fastFailures = 0
                if (epAddress == 0x83) lastServiceDataAt = SystemClock.elapsedRealtime()
                record(epAddress, buf, n)
                parser.feed(buf, n)
            } else if (SystemClock.elapsedRealtime() - t0 < 20) {
                // Immediate error (not a timeout): do not spin while frameLoop decides the connection is lost.
                if (++fastFailures > 5) Thread.sleep(20)
            } else {
                fastFailures = 0
            }
        }
    }

    private fun control(conn: UsbDeviceConnection, name: String, value: Int, index: Int, length: Int): Int {
        val data = if (length > 0) ByteArray(length) else null
        val r = conn.controlTransfer(0x01, 0x0B, value, index, data, length, 200)
        DiagLog.log("control [$name] value=$value index=$index len=$length -> $r")
        return r
    }

    private fun findEndpoint(itf: UsbInterface?, address: Int): UsbEndpoint? {
        itf ?: return null
        for (i in 0 until itf.endpointCount) {
            val ep = itf.getEndpoint(i)
            if (ep.address == address && ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) return ep
        }
        return null
    }

    private fun releaseAll(conn: UsbDeviceConnection) {
        for (itf in claimed) runCatching { conn.releaseInterface(itf) }
        claimed.clear()
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

    private var lastEventLog = 0L
    private fun logThrottled(msg: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEventLog > 500) {
            lastEventLog = now
            DiagLog.log("Parser: $msg")
        }
    }

    private fun asText(buf: ByteArray, n: Int, max: Int): String {
        val sb = StringBuilder()
        for (i in 0 until minOf(n, max)) {
            val c = buf[i].toInt() and 0xFF
            sb.append(if (c in 32..126) c.toChar() else '.')
        }
        return sb.toString()
    }

    private companion object {
        const val CHUNK = 16384
    }
}
