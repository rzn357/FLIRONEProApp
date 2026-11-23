package com.exciteddev.flironepro.settings

import android.content.Context
import android.content.SharedPreferences
import com.exciteddev.flironepro.radiometry.Radiometry
import com.exciteddev.flironepro.radiometry.ShutterCalibration
import com.exciteddev.flironepro.radiometry.TempCalibration
import com.exciteddev.flironepro.render.Alignment
import com.exciteddev.flironepro.render.DisplayMode
import com.exciteddev.flironepro.render.Orientation
import com.exciteddev.flironepro.render.Palette

/** User settings persisted between launches. Fields read by the render thread are @Volatile. */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile var mode: DisplayMode = DisplayMode.entries.getOrElse(prefs.getInt(MODE, 0)) { DisplayMode.MSX }

    @Volatile var palette: Palette = Palette.entries.getOrElse(prefs.getInt(PALETTE, 0)) { Palette.IRON }

    /** Compensate for the screen rotation automatically so the picture stays upright. */
    @Volatile var autoRotate: Boolean = prefs.getBoolean(AUTO_ROTATE, true)

    /** Manual correction added to the automatic orientation, clockwise degrees: 0, 90, 180, 270. */
    @Volatile var rotationOffset: Int = prefs.getInt(ROTATION_OFFSET, 0)

    /** Effective clockwise rotation currently applied to the image (computed by the activity, not persisted). */
    @Volatile var rotation: Int = Orientation.SENSOR_BASE

    @Volatile var mirror: Boolean = prefs.getBoolean(MIRROR, false)

    /** Fill the whole screen with the picture (edges are cropped) instead of showing it entirely. */
    @Volatile var fillScreen: Boolean = prefs.getBoolean(FILL_SCREEN, false)

    /** Crosshair position in normalized thermal-frame coordinates. */
    @Volatile var crossU: Float = prefs.getFloat(CROSS_U, 0.5f)

    @Volatile var crossV: Float = prefs.getFloat(CROSS_V, 0.5f)

    @Volatile var showMarkers: Boolean = prefs.getBoolean(SHOW_MARKERS, true)

    @Volatile var showScale: Boolean = prefs.getBoolean(SHOW_SCALE, true)

    /** Strength of the edge overlay in MSX mode: 0 weak, 1 medium, 2 strong. */
    @Volatile var msxLevel: Int = prefs.getInt(MSX_LEVEL, 1)

    @Volatile var alignment: Alignment = Alignment(
        prefs.getFloat(ALIGN_SCALE, Alignment.DEFAULT_SCALE),
        prefs.getFloat(ALIGN_DX, Alignment.DEFAULT_DX),
        prefs.getFloat(ALIGN_DY, Alignment.DEFAULT_DY)
    )

    @Volatile var emissivity: Double = prefs.getFloat(EMISSIVITY, 1.0f).toDouble()

    @Volatile var reflectedC: Double = prefs.getFloat(REFLECTED_C, 20.0f).toDouble()

    /** Temperature calibration points entered by the user. */
    var calibrationPoints: List<TempCalibration.Point> = TempCalibration.parse(prefs.getString(CAL_POINTS, null))

    /** The camera's shutter-calibration command found by [ShutterCalibration] (null until it was found). */
    var ffcCommand: String? = prefs.getString(FFC_COMMAND, null)

    val msxGain: Float
        get() = when (msxLevel) {
            0 -> 1.0f
            2 -> 3.0f
            else -> 1.8f
        }

    fun save() {
        prefs.edit()
            .putInt(MODE, mode.ordinal)
            .putInt(PALETTE, palette.ordinal)
            .putBoolean(AUTO_ROTATE, autoRotate)
            .putInt(ROTATION_OFFSET, rotationOffset)
            .putBoolean(MIRROR, mirror)
            .putBoolean(FILL_SCREEN, fillScreen)
            .putFloat(CROSS_U, crossU)
            .putFloat(CROSS_V, crossV)
            .putBoolean(SHOW_MARKERS, showMarkers)
            .putBoolean(SHOW_SCALE, showScale)
            .putInt(MSX_LEVEL, msxLevel)
            .putFloat(ALIGN_SCALE, alignment.scale)
            .putFloat(ALIGN_DX, alignment.dx)
            .putFloat(ALIGN_DY, alignment.dy)
            .putFloat(EMISSIVITY, emissivity.toFloat())
            .putFloat(REFLECTED_C, reflectedC.toFloat())
            .putString(CAL_POINTS, TempCalibration.serialize(calibrationPoints))
            .putString(FFC_COMMAND, ffcCommand)
            .apply()
    }

    /** Applies the saved parameters to the temperature model. */
    fun applyTo(r: Radiometry) {
        r.emissivity = emissivity
        r.reflectedC = reflectedC
        val fit = TempCalibration.fit(calibrationPoints)
        r.countsPerPhi = fit.countsPerPhi
        r.offsetCounts = fit.offsetCounts
    }

    private companion object {
        const val PREFS_NAME = "flir_settings"

        const val MODE = "mode"
        const val PALETTE = "palette"
        const val AUTO_ROTATE = "autoRotate"
        const val ROTATION_OFFSET = "rotationOffset"
        const val MIRROR = "mirror"
        const val FILL_SCREEN = "fillScreen"
        const val CROSS_U = "crossU"
        const val CROSS_V = "crossV"
        const val SHOW_MARKERS = "showMarkers"
        const val SHOW_SCALE = "showScale"
        const val MSX_LEVEL = "msxLevel"
        const val ALIGN_SCALE = "alignScale"
        const val ALIGN_DX = "alignDx"
        const val ALIGN_DY = "alignDy"
        const val EMISSIVITY = "emissivity"
        const val REFLECTED_C = "reflectedC"
        const val CAL_POINTS = "calPoints"
        const val FFC_COMMAND = "ffcCommand"
    }
}
