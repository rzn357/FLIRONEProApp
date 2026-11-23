package com.exciteddev.flironepro

/** State of the camera's battery (arrives in service JSON messages on EP 0x81). */
class BatteryState(val percentage: Int? = null, val voltage: Double? = null, val charging: Boolean? = null)

object ServiceMessages {
    private val percentageRegex = Regex("\"percentage\"\\s*:\\s*(-?\\d+)")
    private val voltageRegex = Regex("\"voltage\"\\s*:\\s*(-?[0-9.]+)")
    private val chargingStateRegex = Regex("\"chargingState\"\\s*:\\s*\"([A-Za-z]+)\"")
    private val chargingCurrentRegex = Regex("\"chargingCurrent\"\\s*:\\s*(-?[0-9.]+)")

    /** Updates the battery state from a JSON message. Messages that are not about the battery return the previous state unchanged. */
    fun updateBattery(prev: BatteryState, json: String): BatteryState = when {
        "batteryVoltageUpdate" in json -> {
            val pct = percentageRegex.find(json)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
            val v = voltageRegex.find(json)?.groupValues?.get(1)?.toDoubleOrNull()
            BatteryState(pct ?: prev.percentage, v ?: prev.voltage, prev.charging)
        }

        "batteryChargingStateUpdate" in json -> {
            val st = chargingStateRegex.find(json)?.groupValues?.get(1)
            val charging = st?.let { it.contains("Charging") && !it.contains("NoCharging") }
            BatteryState(prev.percentage, prev.voltage, charging ?: prev.charging)
        }

        "batteryChargingCurrentUpdate" in json -> {
            val cur = chargingCurrentRegex.find(json)?.groupValues?.get(1)?.toDoubleOrNull()
            BatteryState(prev.percentage, prev.voltage, if (cur != null && cur > 0.0) true else prev.charging)
        }

        else -> prev
    }
}
