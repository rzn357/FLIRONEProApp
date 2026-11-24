package com.exciteddev.flironepro.usb

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

    private const val CONTROL_TIMEOUT_MS = 200
    private const val COMMAND_TIMEOUT_MS = 1000

    /** Length of the data stage of the start request for the video interface; some firmware wants 0 instead. */
    private const val START_VIDEO_LENGTH = 2

    /** True if [link] has the bulk endpoint [address] in interface [id]. */
    fun hasEndpoint(link: UsbLink, id: Int, address: Int): Boolean = link.interfaces[id]?.contains(address) == true

    /** Claims the three interfaces (those that exist) and returns the ones that were claimed. */
    fun claimAll(link: UsbLink): List<Int> {
        val claimed = ArrayList<Int>()
        for (id in intArrayOf(SERVICE_INTERFACE_A, SERVICE_INTERFACE_B, VIDEO_INTERFACE)) {
            if (id !in link.interfaces) continue
            val ok = link.claimInterface(id)
            DiagLog.log("claimInterface($id) = $ok")
            if (ok) claimed.add(id)
        }
        return claimed
    }

    /** Starts the video stream; false if the camera refused both variants of the start request. */
    fun startVideo(link: UsbLink): Boolean {
        stopVideo(link)
        setInterface(link, "start interface 1", 1, SERVICE_INTERFACE_B, 0)
        var r = setInterface(link, "start interface 2 (wLength=2)", 1, VIDEO_INTERFACE, START_VIDEO_LENGTH)
        if (r < 0) r = setInterface(link, "start interface 2 (wLength=0)", 1, VIDEO_INTERFACE, 0)
        return r >= 0
    }

    /** Stops the stream the same way the start sequence begins. */
    fun stopVideo(link: UsbLink) {
        setInterface(link, "stop interface 2", 0, VIDEO_INTERFACE, 0)
        setInterface(link, "stop interface 1", 0, SERVICE_INTERFACE_B, 0)
    }

    /** Sends a framed JSON command to the command endpoint: the header and the payload are two bulk transfers. */
    fun sendCommand(link: UsbLink, json: String): Boolean {
        val payload = FlirCommand.payload(json)
        val header = FlirCommand.header(payload.size)
        val r1 = link.bulkTransfer(EP_COMMANDS, header, header.size, COMMAND_TIMEOUT_MS)
        val r2 = link.bulkTransfer(EP_COMMANDS, payload, payload.size, COMMAND_TIMEOUT_MS)
        DiagLog.log("[command] ${payload.size} bytes: header -> $r1, payload -> $r2")
        return r1 >= 0 && r2 >= 0
    }

    private fun setInterface(link: UsbLink, name: String, alt: Int, id: Int, length: Int): Int {
        val r = link.setInterface(alt, id, length, CONTROL_TIMEOUT_MS)
        DiagLog.log("control [$name] value=$alt index=$id len=$length -> $r")
        return r
    }
}
