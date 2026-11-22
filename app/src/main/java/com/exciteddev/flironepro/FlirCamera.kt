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
 * USB-шар для FLIR ONE (VID 09CB, PID 1996).
 *
 * Послідовність запуску відтворює відомий з протоколу спільноти порядок:
 *   claim інтерфейсів 0,1,2 -> SET_INTERFACE(alt 0) для 2 і 1 -> SET_INTERFACE(alt 1) для 1 ->
 *   SET_INTERFACE(alt 1) для 2 -> читання кадрів з bulk IN 0x85.
 * Нічого не записується у прошивку чи налаштування камери.
 */
class FlirCamera(
    private val manager: UsbManager,
    private val device: UsbDevice,
    private val listener: Listener,
) {
    interface Listener {
        fun onFrame(frame: FlirFrame)
        fun onState(message: String)

        /** Зв'язок з камерою втрачено (наприклад, її відключили). Викликається з потоку читання. */
        fun onLost()

        /** JSON-повідомлення камери (батарея, інформація про плату тощо). Викликається з потоку прийому. */
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

    /** JSON-повідомлення з 0x81/0x83 (крім батареї) для тих, хто чекає відповіді на команду. */
    private val jsonQueue = java.util.concurrent.LinkedBlockingQueue<String>()
    @Volatile private var lastServiceDataAt = 0L
    private val messageCounts = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    /** Якщо задано, усі службові відповіді (0x81/0x83) дописуються сюди записами [ep:1][t_ms:4][len:4][дані]. */
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
            fail("openDevice() повернув null (немає дозволу?)")
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
        DiagLog.log("Інтерфейси: ${interfaces.keys.sorted()}, EP85=${ep85 != null}, EP81=${ep81 != null}, EP83=${ep83 != null}, EP02=${ep02 != null}")
        if (ep85 == null) {
            fail("Не знайдено ендпоінт 0x85 в інтерфейсі 2")
            closeConnection()
            return false
        }

        for (id in intArrayOf(0, 1, 2)) {
            val itf = interfaces[id] ?: continue
            val ok = conn.claimInterface(itf, true)
            DiagLog.log("claimInterface($id) = $ok")
            if (ok) claimed.add(itf)
        }

        // Керуючі запити SET_INTERFACE: bmRequestType 0x01, bRequest 0x0B, wValue = alt (0 стоп / 1 старт), wIndex = інтерфейс.
        control(conn, "stop interface 2", 0, 2, 0)
        control(conn, "stop interface 1", 0, 1, 0)
        control(conn, "start interface 1", 1, 1, 0)
        var r = control(conn, "start interface 2 (wLength=2)", 1, 2, 2)
        if (r < 0) r = control(conn, "start interface 2 (wLength=0)", 1, 2, 0)
        if (r < 0) {
            fail("Не вдалося запустити відеопотік (control transfer)")
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
        listener.onState("Потік запущено")
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
        // Зупиняємо потік так само, як це робить початок послідовності.
        control(conn, "stop interface 2", 0, 2, 0)
        control(conn, "stop interface 1", 0, 1, 0)
        releaseAll(conn)
        DiagLog.log("Камеру зупинено. Шматків=$chunks, байт=$bytes, таймаутів=$timeouts, кадрів=${parser?.framesParsed}, ресинхронізацій=${parser?.resyncs}")
    }

    /**
     * ЕКСПЕРИМЕНТ (лише читання): просить камеру відкрити й віддати файл CameraFiles.zip (там, імовірно,
     * калібрувальні дані) та записує всі сирі відповіді з 0x81/0x83 у файл. Блокує виклик ~5 с — запускати з фонового потоку.
     */
    fun runCalibrationProbe(dir: File): String? {
        val conn = connection
        val ep = ep02
        if (!running || conn == null || ep == null) {
            DiagLog.log("[probe] камера не запущена або немає EP 0x02")
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

            // Камера відповідає openFileStatus із номером потоку; саме його треба передати в readFile.
            var streamId: Int? = null
            val deadline = SystemClock.elapsedRealtime() + 3000
            while (streamId == null && SystemClock.elapsedRealtime() < deadline) {
                val msg = jsonQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                if ("openFileStatus" in msg) {
                    streamId = Regex("\"streamIdentifier\"\\s*:\\s*(-?\\d+)").find(msg)?.groupValues?.get(1)?.toIntOrNull()
                }
            }
            DiagLog.log("[probe] номер потоку з відповіді камери: $streamId")
            val id = when {
                streamId != null && streamId >= 0 -> streamId.also { lastStreamId = it }
                else -> {
                    // Негативне значення — файл уже відкрито раніше в цьому сеансі; пробуємо останній відомий номер.
                    val fallback = lastStreamId
                    DiagLog.log("[probe] камера не видала новий номер потоку, пробую останній відомий: $fallback")
                    fallback
                }
            }
            if (id == null) {
                DiagLog.log("[probe] немає номера потоку — перепідключіть камеру й повторіть")
                return null
            }

            lastServiceDataAt = 0L
            DiagLog.log("[probe] -> readFile streamIdentifier=$id")
            sendCommand(conn, ep, FlirCommand.readFile(id))

            // Чекаємо дані: до 15 с, але закінчуємо, коли після останніх даних 2,5 с тиша.
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
        DiagLog.log("[probe] готово, ${file.length()} байт у ${file.name}; повідомлень за типами: $messageCounts")
        return file.name
    }

    private var lastStreamId: Int? = null

    @Volatile private var captureStart = 0L

    private fun sendCommand(conn: UsbDeviceConnection, ep: UsbEndpoint, json: String) {
        val payload = FlirCommand.payload(json)
        val header = FlirCommand.header(payload.size)
        val r1 = conn.bulkTransfer(ep, header, header.size, 1000)
        val r2 = conn.bulkTransfer(ep, payload, payload.size, 1000)
        DiagLog.log("[probe] команда ${payload.size} байт: заголовок -> $r1, вантаж -> $r2")
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
                    DiagLog.log("EP85 шматок #$chunks: $n байт, початок: ${UsbDescriptorParser.hex(buf, 0, minOf(n, 16))}")
                }
                p.feed(buf, n)
            } else {
                timeouts++
                consecutiveErrors++
                // Справжній таймаут триває ~100 мс; помилка, що повертається миттєво, означає втрату зв'язку.
                fastFailures = if (dt < 20) fastFailures + 1 else 0
                if (fastFailures >= 25) {
                    DiagLog.log("Зв'язок із камерою втрачено (bulkTransfer=$n миттєво ${fastFailures} разів підряд)")
                    running = false
                    listener.onLost()
                    break
                }
                if (consecutiveErrors == 1 || consecutiveErrors % 50 == 0) {
                    DiagLog.log("EP85 bulkTransfer=$n (таймаут), підряд=$consecutiveErrors, без даних ${SystemClock.elapsedRealtime() - lastData} мс")
                }
                if (consecutiveErrors == 30) listener.onState("Немає даних від камери вже ~3 с")
            }
        }
    }

    /**
     * Камера шле службові повідомлення на 0x81 і 0x83; окремий потік на кожен ендпоінт, щоб дані файлу не губились.
     * Усе сире йде у capture (під час експерименту), розібрані повідомлення логуються.
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
                    DiagLog.log("EP${epAddress.toString(16)} повідомлення type=$type len=${payload.size} hex=${UsbDescriptorParser.hex(payload, 0, minOf(payload.size, 24))} текст: $text")
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
                // Миттєва помилка (не таймаут): не крутимося вхолосту, поки frameLoop вирішує, що зв'язок втрачено.
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
        DiagLog.log("ПОМИЛКА: $message")
        listener.onState("Помилка: $message")
    }

    private var lastEventLog = 0L
    private fun logThrottled(msg: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEventLog > 500) {
            lastEventLog = now
            DiagLog.log("Парсер: $msg")
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
