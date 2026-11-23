package com.exciteddev.flironepro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceMessagesTest {
    // Messages captured from a real camera.
    private val voltage = """{"type":"batteryVoltageUpdate","data":{"voltage":3.74000000953674,"percentage":39}}"""
    private val noCharge = """{"type":"batteryChargingStateUpdate","data":{"chargingState":"stateNoCharging"}}"""
    private val current0 = """{"type":"batteryChargingCurrentUpdate","data":{"chargingCurrent":0}}"""
    private val sled = """{"type":"sledInformation","data":{"serialNumberBoard":"F11I1V0053G","thermalHeight":"120"}}"""

    @Test
    fun parsesVoltageAndPercentage() {
        val b = ServiceMessages.updateBattery(BatteryState(), voltage)
        assertEquals(39, b.percentage)
        assertEquals(3.74, b.voltage!!, 0.001)
        assertNull(b.charging)
    }

    @Test
    fun noChargingStateMeansNotCharging() {
        val b = ServiceMessages.updateBattery(ServiceMessages.updateBattery(BatteryState(), voltage), noCharge)
        assertEquals(false, b.charging)
        assertEquals(39, b.percentage)
    }

    @Test
    fun chargingStateIsDetected() {
        val b = ServiceMessages.updateBattery(
            BatteryState(50),
            """{"type":"batteryChargingStateUpdate","data":{"chargingState":"stateCharging"}}"""
        )
        assertEquals(true, b.charging)
        assertTrue(b.charging == true)
    }

    @Test
    fun positiveChargingCurrentMeansCharging() {
        val b = ServiceMessages.updateBattery(
            BatteryState(50),
            """{"type":"batteryChargingCurrentUpdate","data":{"chargingCurrent":450.5}}"""
        )
        assertEquals(true, b.charging)
        val z = ServiceMessages.updateBattery(BatteryState(50, null, false), current0)
        assertEquals(false, z.charging)
    }

    @Test
    fun otherMessagesLeaveStateUnchanged() {
        val prev = BatteryState(77, 3.9, false)
        val b = ServiceMessages.updateBattery(prev, sled)
        assertEquals(77, b.percentage)
    }

    @Test
    fun unknownBatteryHasNoValues() {
        assertNull(BatteryState().charging)
        assertNull(BatteryState().percentage)
    }

    @Test
    fun percentageIsClamped() {
        val b = ServiceMessages.updateBattery(BatteryState(), """{"type":"batteryVoltageUpdate","data":{"voltage":4.2,"percentage":140}}""")
        assertEquals(100, b.percentage)
    }
}
