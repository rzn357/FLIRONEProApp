package com.exciteddev.flironepro.usb

import com.exciteddev.flironepro.protocol.FlirCommand
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingDeque

/** The interfaces and bulk endpoints of the FLIR ONE as its descriptors list them. */
internal val FLIR_LAYOUT: Map<Int, Set<Int>> = mapOf(
    FlirUsb.SERVICE_INTERFACE_A to setOf(FlirUsb.EP_SERVICE_A, FlirUsb.EP_COMMANDS),
    FlirUsb.SERVICE_INTERFACE_B to setOf(FlirUsb.EP_SERVICE_B),
    FlirUsb.VIDEO_INTERFACE to setOf(FlirUsb.EP_FRAMES)
)

/** The camera's service message (what arrives on EP 0x81 / 0x83) with the JSON [json]; it is framed like a command. */
internal fun serviceMessage(json: String): ByteArray {
    val payload = FlirCommand.payload(json)
    return FlirCommand.header(payload.size) + payload
}

/**
 * A USB device that is not there: the tests feed it the bytes the camera would send and look at what the driver did.
 *
 * A read of an endpoint hands out the bytes fed to it; when there are none it waits like a real read that times out, which is
 * what [idle] does (it gets the timeout; the default really waits). An empty chunk fed with [feedError] makes one read fail
 * at once, as a read of a camera that was pulled out does; [breakReads] makes every read of an endpoint do that.
 */
internal class FakeUsbLink(
    override val interfaces: Map<Int, Set<Int>> = FLIR_LAYOUT,
    private val idle: (timeoutMs: Int) -> Unit = { Thread.sleep(it.toLong()) }
) : UsbLink {
    /** What the driver asked for with control transfers, as "set alt=1 if=2 len=2". */
    val controls = CopyOnWriteArrayList<String>()

    /** The interfaces the driver claimed, and the ones it released, in order. */
    val claimed = CopyOnWriteArrayList<Int>()
    val released = CopyOnWriteArrayList<Int>()

    /** The bulk OUT transfers, as (endpoint, bytes). */
    val written = CopyOnWriteArrayList<Pair<Int, ByteArray>>()

    @Volatile var closed = false
        private set

    /** The interfaces that refuse to be claimed. */
    @Volatile var unclaimable: Set<Int> = emptySet()

    /** The result of a SET_INTERFACE request (alt, interface, length) -> bytes moved or negative; by default it works. */
    @Volatile var setInterfaceResult: (alt: Int, id: Int, length: Int) -> Int = { _, _, length -> length }

    @Volatile var writesFail = false

    private val incoming = ConcurrentHashMap<Int, LinkedBlockingDeque<ByteArray>>()
    private val reads = ConcurrentHashMap<Int, Int>()
    private val broken = ConcurrentHashMap.newKeySet<Int>()

    /** Makes [bytes] arrive on [endpoint]. */
    fun feed(endpoint: Int, bytes: ByteArray) {
        queue(endpoint).add(bytes)
    }

    /** Makes [bytes] arrive on [endpoint] in chunks of [chunkSize] bytes. */
    fun feedChunked(endpoint: Int, bytes: ByteArray, chunkSize: Int) {
        bytes.asList().chunked(chunkSize).forEach { feed(endpoint, it.toByteArray()) }
    }

    /** Makes the next [count] reads of [endpoint] fail at once. */
    fun feedError(endpoint: Int, count: Int = 1) = repeat(count) { feed(endpoint, ByteArray(0)) }

    /** Makes every read of [endpoint] fail at once from now on (the camera was pulled out). */
    fun breakReads(endpoint: Int) {
        broken.add(endpoint)
    }

    /** True if bytes fed to [endpoint] are waiting to be read. */
    fun hasPending(endpoint: Int): Boolean = queue(endpoint).isNotEmpty()

    fun readsOf(endpoint: Int): Int = reads[endpoint] ?: 0

    private fun queue(endpoint: Int) = incoming.getOrPut(endpoint) { LinkedBlockingDeque() }

    override fun claimInterface(id: Int): Boolean = (id !in unclaimable).also { if (it) claimed.add(id) }

    override fun releaseInterface(id: Int) {
        released.add(id)
    }

    override fun bulkTransfer(endpoint: Int, buffer: ByteArray, length: Int, timeoutMs: Int): Int =
        if (endpoint and DIRECTION_IN == 0) write(endpoint, buffer, length) else read(endpoint, buffer, length, timeoutMs)

    private fun write(endpoint: Int, buffer: ByteArray, length: Int): Int {
        if (writesFail) return FAILED
        written.add(endpoint to buffer.copyOf(length))
        return length
    }

    private fun read(endpoint: Int, buffer: ByteArray, length: Int, timeoutMs: Int): Int {
        reads.merge(endpoint, 1) { a, b -> a + b }
        val chunk = if (endpoint in broken) ByteArray(0) else queue(endpoint).poll()
        if (chunk == null) idle(timeoutMs)
        if (chunk == null || chunk.isEmpty()) return FAILED
        val n = minOf(chunk.size, length)
        chunk.copyInto(buffer, 0, 0, n)
        if (n < chunk.size) queue(endpoint).addFirst(chunk.copyOfRange(n, chunk.size))
        return n
    }

    override fun setInterface(alt: Int, id: Int, length: Int, timeoutMs: Int): Int {
        controls.add("set alt=$alt if=$id len=$length")
        return setInterfaceResult(alt, id, length)
    }

    override fun close() {
        closed = true
    }

    private companion object {
        const val DIRECTION_IN = 0x80
        const val FAILED = -1
    }
}
