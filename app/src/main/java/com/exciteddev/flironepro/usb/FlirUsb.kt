package com.exciteddev.flironepro.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.exciteddev.flironepro.debug.DiagLog
import com.exciteddev.flironepro.protocol.FlirCommand

/**
 * The USB layout of the FLIR ONE (VID 09CB, PID 1996) and the start sequence that reproduces the order known from the community
 * protocol description:
 *   claim interfaces 0,1,2 -> SET_INTERFACE(alt 0) for 2 and 1 -> SET_INTERFACE(alt 1) for 1 ->
 *   SET_INTERFACE(alt 1) for 2 -> read frames from bulk IN 0x85.
 * Nothing is written to the camera's firmware or settings.
 */
internal object FlirUsb {
    /** Bulk endpoints: the frames, the two service channels and the command channel (OUT). */
    const val EP_FRAMES = 0x85
    const val EP_SERVICE_A = 0x81
    const val EP_SERVICE_B = 0x83
    const val EP_COMMANDS = 0x02

    /** Interface 0 holds EP 0x81 and 0x02, interface 1 holds EP 0x83, interface 2 holds the frames (EP 0x85). */
    const val SERVICE_INTERFACE_A = 0
    const val SERVICE_INTERFACE_B = 1
    const val VIDEO_INTERFACE = 2

    // SET_INTERFACE control requests: bmRequestType 0x01, bRequest 0x0B, wValue = alt (0 stop / 1 start), wIndex = interface.
    private const val REQUEST_TYPE = 0x01
    private const val REQUEST_SET_INTERFACE = 0x0B
    private const val CONTROL_TIMEOUT_MS = 200
    private const val COMMAND_TIMEOUT_MS = 1000

    /** Length of the data stage of the start request for the video interface; some firmware wants 0 instead. */
    private const val START_VIDEO_LENGTH = 2

    /** The interfaces of the device at alternate setting 0, by interface number. */
    fun primaryInterfaces(device: UsbDevice): Map<Int, UsbInterface> {
        val interfaces = HashMap<Int, UsbInterface>()
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            if (itf.alternateSetting == 0) interfaces[itf.id] = itf
        }
        return interfaces
    }

    fun findEndpoint(itf: UsbInterface?, address: Int): UsbEndpoint? = itf?.let { i ->
        (0 until i.endpointCount).map { i.getEndpoint(it) }
            .firstOrNull { it.address == address && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
    }

    /** Claims the three interfaces (those that exist) and returns the ones that were claimed. */
    fun claimAll(conn: UsbDeviceConnection, interfaces: Map<Int, UsbInterface>): List<UsbInterface> {
        val claimed = ArrayList<UsbInterface>()
        for (id in intArrayOf(SERVICE_INTERFACE_A, SERVICE_INTERFACE_B, VIDEO_INTERFACE)) {
            val itf = interfaces[id] ?: continue
            val ok = conn.claimInterface(itf, true)
            DiagLog.log("claimInterface($id) = $ok")
            if (ok) claimed.add(itf)
        }
        return claimed
    }

    /** Starts the video stream; false if the camera refused both variants of the start request. */
    fun startVideo(conn: UsbDeviceConnection): Boolean {
        stopVideo(conn)
        setInterface(conn, "start interface 1", 1, SERVICE_INTERFACE_B, 0)
        var r = setInterface(conn, "start interface 2 (wLength=2)", 1, VIDEO_INTERFACE, START_VIDEO_LENGTH)
        if (r < 0) r = setInterface(conn, "start interface 2 (wLength=0)", 1, VIDEO_INTERFACE, 0)
        return r >= 0
    }

    /** Stops the stream the same way the start sequence begins. */
    fun stopVideo(conn: UsbDeviceConnection) {
        setInterface(conn, "stop interface 2", 0, VIDEO_INTERFACE, 0)
        setInterface(conn, "stop interface 1", 0, SERVICE_INTERFACE_B, 0)
    }

    /** Sends a framed JSON command to the command endpoint: the header and the payload are two bulk transfers. */
    fun sendCommand(conn: UsbDeviceConnection, ep: UsbEndpoint, json: String): Boolean {
        val payload = FlirCommand.payload(json)
        val header = FlirCommand.header(payload.size)
        val r1 = conn.bulkTransfer(ep, header, header.size, COMMAND_TIMEOUT_MS)
        val r2 = conn.bulkTransfer(ep, payload, payload.size, COMMAND_TIMEOUT_MS)
        DiagLog.log("[command] ${payload.size} bytes: header -> $r1, payload -> $r2")
        return r1 >= 0 && r2 >= 0
    }

    private fun setInterface(conn: UsbDeviceConnection, name: String, value: Int, index: Int, length: Int): Int {
        val data = if (length > 0) ByteArray(length) else null
        val r = conn.controlTransfer(REQUEST_TYPE, REQUEST_SET_INTERFACE, value, index, data, length, CONTROL_TIMEOUT_MS)
        DiagLog.log("control [$name] value=$value index=$index len=$length -> $r")
        return r
    }
}
