package com.exciteddev.flironepro

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.SystemClock

/** Settings shared by the reader loops. */
private const val CHUNK = 16384
private const val READ_TIMEOUT_MS = 100

/** A read that returns sooner than this is an error (a real timeout takes the whole [READ_TIMEOUT_MS]). */
private const val FAST_FAILURE_MS = 20

/**
 * Reads the video stream (bulk IN 0x85) and feeds it to the frame parser. If reads keep failing immediately, the connection is
 * considered lost and [onLost] is called.
 */
internal class FrameReader(
    private val conn: UsbDeviceConnection,
    private val ep: UsbEndpoint,
    private val parser: FlirFrameParser,
    private val isRunning: () -> Boolean,
    private val onLost: () -> Unit,
    private val onState: (String) -> Unit
) {
    @Volatile var chunks = 0L
        private set

    @Volatile var bytes = 0L
        private set

    @Volatile var timeouts = 0L
        private set

    /** What the loop has seen since the last chunk of data. */
    private class Health {
        var lastData = SystemClock.elapsedRealtime()
        var loggedChunks = 0
        var consecutiveErrors = 0
        var fastFailures = 0
    }

    fun run() {
        val buf = ByteArray(CHUNK)
        val health = Health()
        while (isRunning()) {
            val t0 = SystemClock.elapsedRealtime()
            val n = conn.bulkTransfer(ep, buf, buf.size, READ_TIMEOUT_MS)
            val duration = SystemClock.elapsedRealtime() - t0
            if (n > 0) {
                onChunk(buf, n, health)
            } else if (onFailedRead(n, duration, health)) {
                break
            }
        }
    }

    private fun onChunk(buf: ByteArray, n: Int, health: Health) {
        chunks++
        bytes += n
        health.consecutiveErrors = 0
        health.fastFailures = 0
        health.lastData = SystemClock.elapsedRealtime()
        if (health.loggedChunks < LOGGED_CHUNKS) {
            health.loggedChunks++
            DiagLog.log("EP85 chunk #$chunks: $n bytes, start: ${UsbDescriptorParser.hex(buf, 0, minOf(n, PREVIEW_BYTES))}")
        }
        parser.feed(buf, n)
    }

    /** Handles a read that returned no data; true if the connection is considered lost. */
    private fun onFailedRead(n: Int, duration: Long, health: Health): Boolean {
        timeouts++
        health.consecutiveErrors++
        health.fastFailures = if (duration < FAST_FAILURE_MS) health.fastFailures + 1 else 0
        val lost = health.fastFailures >= LOST_AFTER_FAILURES
        if (lost) {
            DiagLog.log("Connection to the camera lost (bulkTransfer=$n immediately ${health.fastFailures} times in a row)")
            onLost()
        } else {
            logSilence(n, health)
        }
        return lost
    }

    private fun logSilence(n: Int, health: Health) {
        val errors = health.consecutiveErrors
        if (errors == 1 || errors % LOG_EVERY == 0) {
            val silent = SystemClock.elapsedRealtime() - health.lastData
            DiagLog.log("EP85 bulkTransfer=$n (timeout), in a row=$errors, without data $silent ms")
        }
        if (errors == SILENCE_WARNING_AT) onState("No data from the camera for ~3 s")
    }

    private companion object {
        const val LOGGED_CHUNKS = 12
        const val PREVIEW_BYTES = 16
        const val LOST_AFTER_FAILURES = 25
        const val LOG_EVERY = 50

        /** With the 100 ms read timeout this is about three seconds without data. */
        const val SILENCE_WARNING_AT = 30
    }
}

/**
 * Reads one of the service endpoints (0x81 / 0x83); one reader per endpoint, so file data is not lost. Everything raw goes to
 * the capture (during the experiment), the parsed messages are handled by the [ServiceChannel].
 */
internal class ServiceReader(
    private val conn: UsbDeviceConnection,
    private val ep: UsbEndpoint,
    private val channel: ServiceChannel,
    private val isRunning: () -> Boolean
) {
    fun run() {
        val buf = ByteArray(CHUNK)
        val parser = channel.parserFor(ep.address)
        var fastFailures = 0
        while (isRunning()) {
            val t0 = SystemClock.elapsedRealtime()
            val n = conn.bulkTransfer(ep, buf, buf.size, READ_TIMEOUT_MS)
            if (n > 0) {
                fastFailures = 0
                if (ep.address == FlirUsb.EP_SERVICE_B) channel.markData()
                channel.record(ep.address, buf, n)
                parser.feed(buf, n)
            } else if (SystemClock.elapsedRealtime() - t0 < FAST_FAILURE_MS) {
                // Immediate error (not a timeout): do not spin while the frame reader decides the connection is lost.
                if (++fastFailures > BACKOFF_AFTER_FAILURES) Thread.sleep(BACKOFF_MS)
            } else {
                fastFailures = 0
            }
        }
    }

    private companion object {
        const val BACKOFF_AFTER_FAILURES = 5
        const val BACKOFF_MS = 20L
    }
}
