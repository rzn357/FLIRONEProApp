package com.exciteddev.flironepro

import kotlin.math.exp
import kotlin.math.ln

/**
 * Conversion of raw sensor values to temperature.
 *
 * The model (derived on a real camera, see the tests):
 *   Φ(T)    = R / (exp(B/T) - F)                      - Planck function with the constants from the factory calibration (R, B, F)
 *   S'      = S - S_ffc - K_FPA*(T_fpa - T_fpa_ffc)   - raw value relative to the anchor, corrected for sensor drift
 *   Φ_meas  = Φ(T_shutter) + (S' - offset) / b        - "apparent" radiation
 *   Φ_obj   = (Φ_meas - (1-ε)*Φ(T_reflected)) / ε     - emissivity correction
 *   T_obj   = B / ln(R/Φ_obj + F)
 * The anchor (S_ffc, T_shutter, T_fpa_ffc) comes from every shutter calibration (FFC): while the shutter is closed,
 * the whole frame is uniform and the camera reports its temperature itself.
 *
 * b and the offset default to the factory values (fitted against a thermocouple); the user can refine them with the in-app calibration.
 */
class Radiometry {
    class Anchor(val raw: Double, val shutterK: Double, val fpaK: Double)

    @Volatile
    var anchor: Anchor? = null
        private set

    /** Emissivity of the object (1.0 = no correction). */
    @Volatile var emissivity: Double = 1.0

    /** Temperature of the surrounding objects, °C (for the reflection correction when ε < 1). */
    @Volatile var reflectedC: Double = 20.0

    /** How many raw counts correspond to one unit of Φ. */
    @Volatile var countsPerPhi: Double = DEFAULT_COUNTS_PER_PHI

    /** Additional raw-count offset (result of the in-app calibration). */
    @Volatile var offsetCounts: Double = 0.0

    /**
     * The anchor from an earlier session. It is used until this session gets its own calibration, but only while the
     * sensor temperature is still close to the one at that calibration (the drift correction covers small differences).
     */
    @Volatile
    var fallbackAnchor: Anchor? = null

    private var n = 0
    private var sumRaw = 0.0
    private var sumShutter = 0.0
    private var sumFpa = 0.0

    /** Sets the anchor directly (demo mode and tests). */
    fun setAnchor(a: Anchor) {
        anchor = a
    }

    /**
     * Call for every frame. During FFC it collects the "plateau" (the uniform frame of the closed shutter);
     * when the FFC ends it stores the anchor.
     */
    fun onFrame(ffc: Boolean, stats: ThermalStats, shutterK: Double?, fpaK: Double?) {
        if (ffc) {
            // Transitional frames (the shutter is moving) have a large spread of values - skip them.
            if (shutterK != null && fpaK != null && stats.max - stats.min < PLATEAU_RANGE) {
                n++
                sumRaw += stats.mean
                sumShutter += shutterK
                sumFpa += fpaK
            }
        } else {
            if (n >= MIN_PLATEAU_FRAMES) {
                val a = Anchor(sumRaw / n, sumShutter / n, sumFpa / n)
                anchor = a
                remember(a, System.currentTimeMillis())
            }
            n = 0
            sumRaw = 0.0
            sumShutter = 0.0
            sumFpa = 0.0
        }
    }

    /** The anchor in use: this session's own, else the previous session's if the sensor temperature is still close to it. */
    fun effectiveAnchor(fpaK: Double): Anchor? = anchor ?: fallbackAnchor?.takeIf { Math.abs(fpaK - it.fpaK) <= MAX_FALLBACK_FPA_GAP_K }

    /** S' (without the user offset): the raw value relative to the anchor, corrected for sensor drift. */
    fun relative(raw: Double, fpaK: Double): Double? {
        val a = effectiveAnchor(fpaK) ?: return null
        return raw - a.raw - FPA_DRIFT * (fpaK - a.fpaK)
    }

    /** Φ of the shutter at the anchor in use (the real anchor only, unless the sensor temperature is given). */
    fun shutterPhi(fpaK: Double? = null): Double? = (if (fpaK != null) effectiveAnchor(fpaK) else anchor)?.let { planck(it.shutterK) }

    fun celsius(raw: Int, fpaK: Double): Double? = celsius(raw.toDouble(), fpaK)

    /** Temperature in degrees Celsius for a raw value, or null if there is no anchor yet or the value is outside the model. */
    fun celsius(raw: Double, fpaK: Double): Double? {
        val rel = relative(raw, fpaK) ?: return null
        val phiMeas = shutterPhi(fpaK)!! + (rel - offsetCounts) / countsPerPhi
        val e = emissivity.coerceIn(MIN_EMISSIVITY, 1.0)
        val phiRefl = planck(reflectedC + 273.15)
        val phiObj = (phiMeas - (1.0 - e) * phiRefl) / e
        if (phiObj <= 0.0) return null
        val t = B / ln(R / phiObj + F) - 273.15
        return if (t.isNaN() || t.isInfinite()) null else t
    }

    /**
     * A calibration point: what the sensor showed (y = S') and what was true (x = Φ_meas - Φ(T_shutter)),
     * taking into account the emissivity and the reflected temperature at the time of the measurement.
     */
    fun calibrationPoint(
        relative: Double,
        phiShutter: Double,
        trueC: Double,
        emissivity: Double,
        reflectedC: Double
    ): TempCalibration.Point {
        val e = emissivity.coerceIn(MIN_EMISSIVITY, 1.0)
        val phiMeas = e * planck(trueC + 273.15) + (1.0 - e) * planck(reflectedC + 273.15)
        return TempCalibration.Point(y = relative, x = phiMeas - phiShutter, trueC = trueC)
    }

    companion object {
        /** Constants from calib.rsc (section extRBF, fixed lens leExFOL2). */
        const val R = 14331.136
        const val B = 1384.7
        const val F = 1.0

        /** Factory value: counts per unit of Φ (fitted against a thermocouple: hot water 74-75 °C, freezer -12 °C, room 19 °C). */
        const val DEFAULT_COUNTS_PER_PHI = 22.4
        const val COUNTS_PER_PHI = DEFAULT_COUNTS_PER_PHI

        /** Drift of the raw values with the sensor temperature, counts per kelvin (estimated from three FFCs). */
        const val FPA_DRIFT = 100.0

        const val MIN_EMISSIVITY = 0.05

        private const val PLATEAU_RANGE = 150
        private const val MIN_PLATEAU_FRAMES = 3

        /** A previous anchor is reused only if it is not older than this and the sensor temperature differs by at most this much. */
        const val MAX_FALLBACK_AGE_MS = 20 * 60 * 1000L
        const val MAX_FALLBACK_FPA_GAP_K = 2.5

        @Volatile private var lastAnchor: Anchor? = null

        @Volatile private var lastAnchorAtMs = 0L

        /** Remembers the newest anchor of this process, so a restarted pipeline does not have to wait for a new calibration. */
        fun remember(a: Anchor, nowMs: Long) {
            lastAnchor = a
            lastAnchorAtMs = nowMs
        }

        /** The remembered anchor if it is recent enough, else null. */
        fun recentAnchor(nowMs: Long): Anchor? = lastAnchor?.takeIf { nowMs - lastAnchorAtMs in 0..MAX_FALLBACK_AGE_MS }

        /** Forgets the remembered anchor (tests). */
        fun forgetRemembered() {
            lastAnchor = null
            lastAnchorAtMs = 0L
        }

        fun planck(tK: Double): Double = R / (exp(B / tK) - F)
    }
}
