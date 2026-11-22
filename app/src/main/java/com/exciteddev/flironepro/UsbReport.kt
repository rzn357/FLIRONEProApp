package com.exciteddev.flironepro

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/**
 * Будує текстовий опис USB-пристрою. Тільки читає: пристрій відкривається лише щоб прочитати
 * дескриптори, інтерфейси не захоплюються і нічого не надсилається.
 */
object UsbReport {

    const val FLIR_VENDOR_ID = 0x09CB

    fun describe(manager: UsbManager, device: UsbDevice): String {
        val sb = StringBuilder()
        val flir = device.vendorId == FLIR_VENDOR_ID
        sb.appendLine("=== ${device.deviceName}${if (flir) "   <-- FLIR" else ""} ===")
        sb.appendLine("VID:PID = ${UsbDescriptorParser.hex16(device.vendorId)}:${UsbDescriptorParser.hex16(device.productId)}")
        sb.appendLine("class=${UsbDescriptorParser.className(device.deviceClass)} sub=${device.deviceSubclass} proto=${device.deviceProtocol}")

        val granted = manager.hasPermission(device)
        sb.appendLine("Дозвіл на доступ: ${if (granted) "є" else "немає"}")

        // Рядки та серійник Android віддає лише за наявності дозволу.
        if (granted) {
            sb.appendLine("Виробник: ${device.manufacturerName}")
            sb.appendLine("Продукт:  ${device.productName}")
            sb.appendLine("Версія:   ${device.version}")
        }

        sb.appendLine("Конфігурацій (за Android API): ${device.configurationCount}, інтерфейсів поточної: ${device.interfaceCount}")

        if (!granted) {
            sb.appendLine("(дескриптори недоступні без дозволу)")
            return sb.toString()
        }

        val connection = manager.openDevice(device)
        if (connection == null) {
            sb.appendLine("!! openDevice() повернув null")
            return sb.toString()
        }
        try {
            sb.appendLine("Серійний номер: ${runCatching { connection.serial }.getOrNull()}")
            val raw = connection.rawDescriptors
            if (raw == null) {
                sb.appendLine("!! rawDescriptors = null")
            } else {
                sb.appendLine("--- Дескриптори (${raw.size} байт) ---")
                sb.appendLine(UsbDescriptorParser.parse(raw))
                sb.appendLine("--- Hex ---")
                sb.appendLine(UsbDescriptorParser.hex(raw, 0, raw.size))
            }
        } finally {
            connection.close()
        }
        return sb.toString()
    }
}
