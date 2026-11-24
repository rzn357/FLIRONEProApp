package com.exciteddev.flironepro.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * What the camera driver needs from an open USB device, and nothing more. The real implementation wraps Android's
 * [UsbDeviceConnection] ([AndroidUsbLink]); the tests use a fake one, so the driver, the readers and everything behind them run
 * on the JVM without a camera.
 */
internal interface UsbLink {
    /** The bulk endpoint addresses of every interface (at alternate setting 0), by interface number. */
    val interfaces: Map<Int, Set<Int>>

    /** Claims interface [id] (taking it from the kernel driver if there is one); false if that did not work. */
    fun claimInterface(id: Int): Boolean

    fun releaseInterface(id: Int)

    /** Reads from or writes to a bulk endpoint; the number of bytes moved, or a negative number (a timeout is one, too). */
    fun bulkTransfer(endpoint: Int, buffer: ByteArray, length: Int, timeoutMs: Int): Int

    /**
     * The SET_INTERFACE request: selects alternate setting [alt] of interface [id]; [length] bytes of zeros go along as its data
     * stage (0: no data stage). Returns the number of bytes moved, or a negative number if the camera refused.
     */
    fun setInterface(alt: Int, id: Int, length: Int, timeoutMs: Int): Int

    fun close()
}

/** Opens the USB device; null if that is not possible (e.g. there is no permission). */
internal fun interface UsbOpener {
    fun open(): UsbLink?
}

/** The opener of a real device. */
internal fun androidOpener(manager: UsbManager, device: UsbDevice) = UsbOpener {
    manager.openDevice(device)?.let { AndroidUsbLink(it, device) }
}

/** [UsbLink] over a [UsbDeviceConnection]. */
internal class AndroidUsbLink(private val connection: UsbDeviceConnection, device: UsbDevice) : UsbLink {
    private val primary: Map<Int, UsbInterface> = (0 until device.interfaceCount)
        .map { device.getInterface(it) }
        .filter { it.alternateSetting == 0 }
        .associateBy { it.id }

    private val endpoints: Map<Int, UsbEndpoint> = primary.values
        .flatMap { itf -> (0 until itf.endpointCount).map { itf.getEndpoint(it) } }
        .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        .associateBy { it.address }

    override val interfaces: Map<Int, Set<Int>> = primary.mapValues { (_, itf) ->
        (0 until itf.endpointCount).map { itf.getEndpoint(it) }
            .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
            .map { it.address }
            .toSet()
    }

    override fun claimInterface(id: Int): Boolean = primary[id]?.let { connection.claimInterface(it, true) } ?: false

    override fun releaseInterface(id: Int) {
        primary[id]?.let { connection.releaseInterface(it) }
    }

    override fun bulkTransfer(endpoint: Int, buffer: ByteArray, length: Int, timeoutMs: Int): Int {
        val ep = endpoints[endpoint] ?: return NO_SUCH_ENDPOINT
        return connection.bulkTransfer(ep, buffer, length, timeoutMs)
    }

    override fun setInterface(alt: Int, id: Int, length: Int, timeoutMs: Int): Int {
        val data = if (length > 0) ByteArray(length) else null
        return connection.controlTransfer(REQUEST_TYPE, REQUEST_SET_INTERFACE, alt, id, data, length, timeoutMs)
    }

    override fun close() = connection.close()

    private companion object {
        const val NO_SUCH_ENDPOINT = -1

        // bmRequestType 0x01 (host to device, standard, interface), bRequest 0x0B (SET_INTERFACE), wValue = alt, wIndex = interface.
        const val REQUEST_TYPE = 0x01
        const val REQUEST_SET_INTERFACE = 0x0B
    }
}
