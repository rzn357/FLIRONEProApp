package com.exciteddev.flironepro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Only the logic that does not need a canvas: level clamping and the color choice. */
class BatteryDrawableTest {
    @Test
    fun levelIsClampedToTheValidRange() {
        assertEquals(100, BatteryDrawable.clamp(150))
        assertEquals(0, BatteryDrawable.clamp(-5))
        assertEquals(42, BatteryDrawable.clamp(42))
    }

    @Test
    fun lowBatteryIsRedAndChargingIsGreen() {
        val normal = BatteryDrawable.colorFor(80, false)
        val low = BatteryDrawable.colorFor(BatteryDrawable.LOW_PERCENT, false)
        val charging = BatteryDrawable.colorFor(BatteryDrawable.LOW_PERCENT, true)
        assertNotEquals(normal, low)
        assertNotEquals(low, charging)
        assertNotEquals(normal, charging)
    }

    @Test
    fun justAboveTheLowLevelStaysNormal() {
        assertEquals(BatteryDrawable.colorFor(80, false), BatteryDrawable.colorFor(BatteryDrawable.LOW_PERCENT + 1, false))
    }
}
