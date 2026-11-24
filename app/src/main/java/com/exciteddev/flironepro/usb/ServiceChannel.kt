package com.exciteddev.flironepro.usb

import android.os.SystemClock
import com.exciteddev.flironepro.debug.DiagLog
import com.exciteddev.flironepro.protocol.Bytes
import com.exciteddev.flironepro.protocol.FlirCommand
import com.exciteddev.flironepro.protocol.FlirMessageParser
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * What arrives on the camera's service endpoints (0x81 / 0x83): parses the messages, hands JSON ones to the listener, keeps the
 * replies that someone may wait for, and (during the file-reading experiment) records the raw bytes into a file.
 */
internal class ServiceChannel(private val now: () -> Long = SystemClock::elapsedRealtime, private val onJson: (String) -> Unit) {
    /** JSON messages (except battery reports) for whoever waits for a reply to a command. */
    val replies = LinkedBlockingQueue<String>()

    val messageCounts = ConcurrentHashMap<Int, Int>()

    /** Time (elapsedRealtime) of the newest service data that was not a battery report; 0 = none yet. */
    @Volatile var lastDataAt = 0L

    /** If set, all service replies are appended here as records [ep:1][t_ms:4][len:4][data]. */
    @Volatile private var capture: OutputStream? = null

    @Volatile private var captureStart = 0L

    /** Message counters of one endpoint, so that the log is not flooded. */
    private class MessageLog(val epAddress: Int) {
        var logged = 0
        var batteryLogged = 0
    }

    /** A parser for the messages of one endpoint. */
    fun parserFor(epAddress: Int): FlirMessageParser {
        val log = MessageLog(epAddress)
        return FlirMessageParser { type, payload -> handle(type, payload, log) }
    }

    fun markData() {
        lastDataAt = now()
    }

    private fun handle(type: Int, payload: ByteArray, log: MessageLog) {
        messageCounts.merge(type, 1) { a, b -> a + b }
        val text = Bytes.asciiText(payload, 0, minOf(payload.size, TEXT_CHARS))
        val isBattery = BATTERY_MESSAGES.any { it in text }
        val json = if (payload.isNotEmpty() && payload[0] == '{'.code.toByte()) String(payload, Charsets.UTF_8) else null
        if (json != null) runCatching { onJson(json) }
        if (!isBattery) {
            markData()
            json?.let { replies.offer(it) }
        }
        if ((!isBattery || log.batteryLogged++ < MAX_BATTERY_LOGGED) && log.logged++ < MAX_LOGGED) {
            val head = UsbDescriptorParser.hex(payload, 0, minOf(payload.size, HEX_BYTES))
            DiagLog.log("EP${log.epAddress.toString(HEX)} message type=$type len=${payload.size} hex=$head text: $text")
        }
    }

    fun startCapture(out: OutputStream) {
        captureStart = now()
        messageCounts.clear()
        replies.clear()
        capture = out
    }

    fun stopCapture() {
        capture = null
    }

    fun record(epAddress: Int, data: ByteArray, n: Int) {
        val out = capture ?: return
        val t = (now() - captureStart).toInt()
        synchronized(out) {
            runCatching {
                out.write(epAddress)
                out.write(littleEndian(t))
                out.write(littleEndian(n))
                out.write(data, 0, n)
            }
        }
    }

    private fun littleEndian(v: Int) = ByteArray(Int.SIZE_BYTES).also { Bytes.putU32(it, 0, v.toLong()) }

    private companion object {
        const val TEXT_CHARS = 600
        const val HEX_BYTES = 24
        const val HEX = 16
        const val MAX_LOGGED = 60
        const val MAX_BATTERY_LOGGED = 2
        val BATTERY_MESSAGES = listOf("batteryVoltageUpdate", "batteryChargingCurrentUpdate", "batteryChargingStateUpdate")
    }
}

/**
 * EXPERIMENT (read-only): asks the camera to open and send the file CameraFiles.zip (probably holds the calibration data) and
 * records all raw replies from 0x81/0x83 into a file. Blocks for up to ~20 s, so run it from a background thread.
 */
internal class CameraFilesProbe(private val camera: FlirCamera, private val service: ServiceChannel) {
    private var lastStreamId: Int? = null

    /** Returns the name of the file with the recording, or null if the experiment could not be done. */
    fun run(dir: File): String? {
        if (!camera.canSend) {
            DiagLog.log("[probe] the camera is not running or EP 0x02 is missing")
            return null
        }
        return capture(dir)
    }

    private fun capture(dir: File): String? {
        dir.mkdirs()
        val file = File(dir, "capture_${System.currentTimeMillis()}.bin")
        val out = FileOutputStream(file)
        service.startCapture(out)
        val ok = try {
            requestAndRead()
        } finally {
            service.stopCapture()
            synchronized(out) { runCatching { out.close() } }
        }
        if (!ok) return null
        DiagLog.log("[probe] done, ${file.length()} bytes in ${file.name}; messages by type: ${service.messageCounts}")
        return file.name
    }

    private fun requestAndRead(): Boolean {
        DiagLog.log("[probe] -> openFile CameraFiles.zip")
        camera.sendJson(FlirCommand.OPEN_CAMERA_FILES)
        val streamId = waitForStreamId()
        DiagLog.log("[probe] stream number from the camera reply: $streamId")
        val id = chooseStreamId(streamId)
        if (id == null) DiagLog.log("[probe] no stream number - reconnect the camera and try again")
        if (id != null) {
            service.lastDataAt = 0L
            DiagLog.log("[probe] -> readFile streamIdentifier=$id")
            camera.sendJson(FlirCommand.readFile(id))
            waitForSilence()
        }
        return id != null
    }

    /** The camera replies with openFileStatus carrying a stream number; that number must be passed to readFile. */
    private fun waitForStreamId(): Int? {
        var streamId: Int? = null
        val deadline = SystemClock.elapsedRealtime() + REPLY_WAIT_MS
        while (streamId == null && SystemClock.elapsedRealtime() < deadline) {
            val msg = service.replies.poll(POLL_REPLY_MS, TimeUnit.MILLISECONDS) ?: continue
            if ("openFileStatus" in msg) streamId = STREAM_ID.find(msg)?.groupValues?.get(1)?.toIntOrNull()
        }
        return streamId
    }

    private fun chooseStreamId(fromCamera: Int?): Int? {
        if (fromCamera != null && fromCamera >= 0) {
            lastStreamId = fromCamera
            return fromCamera
        }
        // A negative value means the file was already opened earlier in this session; try the last known number.
        DiagLog.log("[probe] the camera did not give a new stream number, trying the last known one: $lastStreamId")
        return lastStreamId
    }

    /** Waits for data: up to 15 s, but stops once 2.5 s of silence follows the last data. */
    private fun waitForSilence() {
        val end = SystemClock.elapsedRealtime() + DATA_WAIT_MS
        while (SystemClock.elapsedRealtime() < end) {
            Thread.sleep(SILENCE_POLL_MS)
            val last = service.lastDataAt
            if (last != 0L && SystemClock.elapsedRealtime() - last > SILENCE_MS) break
        }
    }

    private companion object {
        const val REPLY_WAIT_MS = 3000L
        const val POLL_REPLY_MS = 200L
        const val DATA_WAIT_MS = 15_000L
        const val SILENCE_POLL_MS = 250L
        const val SILENCE_MS = 2500L
        val STREAM_ID = Regex("\"streamIdentifier\"\\s*:\\s*(-?\\d+)")
    }
}
