package com.exciteddev.flironepro.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/**
 * Builds a text description of a USB device. Read-only: the device is opened only to read the
 * descriptors, no interfaces are claimed and nothing is sent.
 */
object UsbReport {

    const val FLIR_VENDOR_ID = 0x09CB

    fun describe(manager: UsbManager, device: UsbDevice): String {
        val sb = StringBuilder()
        val granted = manager.hasPermission(device)
        appendSummary(sb, device, granted)
        if (granted) appendDescriptors(sb, manager, device) else sb.appendLine("(descriptors are unavailable without permission)")
        return sb.toString()
    }

    private fun appendSummary(sb: StringBuilder, device: UsbDevice, granted: Boolean) {
        val flir = device.vendorId == FLIR_VENDOR_ID
        sb.appendLine("=== ${device.deviceName}${if (flir) "   <-- FLIR" else ""} ===")
        sb.appendLine("VID:PID = ${UsbDescriptorParser.hex16(device.vendorId)}:${UsbDescriptorParser.hex16(device.productId)}")
        sb.appendLine(
            "class=${UsbDescriptorParser.className(device.deviceClass)} sub=${device.deviceSubclass} proto=${device.deviceProtocol}"
        )
        sb.appendLine("Access permission: ${if (granted) "yes" else "no"}")

        // Android provides the strings and the serial number only when permission is granted.
        if (granted) {
            sb.appendLine("Manufacturer: ${device.manufacturerName}")
            sb.appendLine("Product:      ${device.productName}")
            sb.appendLine("Version:      ${device.version}")
        }
        sb.appendLine("Configurations (Android API): ${device.configurationCount}, interfaces of the current one: ${device.interfaceCount}")
    }

    private fun appendDescriptors(sb: StringBuilder, manager: UsbManager, device: UsbDevice) {
        val connection = manager.openDevice(device)
        if (connection == null) {
            sb.appendLine("!! openDevice() returned null")
            return
        }
        try {
            sb.appendLine("Serial number: ${runCatching { connection.serial }.getOrNull()}")
            val raw = connection.rawDescriptors
            if (raw == null) {
                sb.appendLine("!! rawDescriptors = null")
            } else {
                sb.appendLine("--- Descriptors (${raw.size} bytes) ---")
                sb.appendLine(UsbDescriptorParser.parse(raw))
                sb.appendLine("--- Hex ---")
                sb.appendLine(UsbDescriptorParser.hex(raw, 0, raw.size))
            }
        } finally {
            connection.close()
        }
    }
}
