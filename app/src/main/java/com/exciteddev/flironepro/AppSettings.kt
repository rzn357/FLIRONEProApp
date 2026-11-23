package com.exciteddev.flironepro

import android.content.Context
import android.content.SharedPreferences

/** User settings persisted between launches. Fields read by the render thread are @Volatile. */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("flir_settings", Context.MODE_PRIVATE)

    @Volatile var mode: DisplayMode = DisplayMode.entries.getOrElse(prefs.getInt("mode", 0)) { DisplayMode.MSX }

    @Volatile var palette: Palette = Palette.entries.getOrElse(prefs.getInt("palette", 0)) { Palette.IRON }

    /** Compensate for the screen rotation automatically so the picture stays upright. */
    @Volatile var autoRotate: Boolean = prefs.getBoolean("autoRotate", true)

    /** Manual correction added to the automatic orientation, clockwise degrees: 0, 90, 180, 270. */
    @Volatile var rotationOffset: Int = prefs.getInt("rotationOffset", 0)

    /** Effective clockwise rotation currently applied to the image (computed by the activity, not persisted). */
    @Volatile var rotation: Int = Orientation.SENSOR_BASE

    @Volatile var mirror: Boolean = prefs.getBoolean("mirror", false)

    /** Fill the whole screen with the picture (edges are cropped) instead of showing it entirely. */
    @Volatile var fillScreen: Boolean = prefs.getBoolean("fillScreen", false)

    /** Crosshair position in normalized thermal-frame coordinates. */
    @Volatile var crossU: Float = prefs.getFloat("crossU", 0.5f)

    @Volatile var crossV: Float = prefs.getFloat("crossV", 0.5f)

    @Volatile var showMarkers: Boolean = prefs.getBoolean("showMarkers", true)

    @Volatile var showScale: Boolean = prefs.getBoolean("showScale", true)

    /** Strength of the edge overlay in MSX mode: 0 weak, 1 medium, 2 strong. */
    @Volatile var msxLevel: Int = prefs.getInt("msxLevel", 1)

    @Volatile var alignment: Alignment = Alignment(
        prefs.getFloat("alignScale", Alignment.DEFAULT_SCALE),
        prefs.getFloat("alignDx", Alignment.DEFAULT_DX),
        prefs.getFloat("alignDy", Alignment.DEFAULT_DY)
    )

    @Volatile var emissivity: Double = prefs.getFloat("emissivity", 1.0f).toDouble()

    @Volatile var reflectedC: Double = prefs.getFloat("reflectedC", 20.0f).toDouble()

    /** Temperature calibration points entered by the user. */
    var calibrationPoints: List<TempCalibration.Point> = TempCalibration.parse(prefs.getString("calPoints", null))

    /** The camera's shutter-calibration command found by [ShutterCalibration] (null until it was found). */
    var ffcCommand: String? = prefs.getString("ffcCommand", null)

    val msxGain: Float
        get() = when (msxLevel) {
            0 -> 1.0f
            2 -> 3.0f
            else -> 1.8f
        }

    fun save() {
        prefs.edit()
            .putInt("mode", mode.ordinal)
            .putInt("palette", palette.ordinal)
            .putBoolean("autoRotate", autoRotate)
            .putInt("rotationOffset", rotationOffset)
            .putBoolean("mirror", mirror)
            .putBoolean("fillScreen", fillScreen)
            .putFloat("crossU", crossU)
            .putFloat("crossV", crossV)
            .putBoolean("showMarkers", showMarkers)
            .putBoolean("showScale", showScale)
            .putInt("msxLevel", msxLevel)
            .putFloat("alignScale", alignment.scale)
            .putFloat("alignDx", alignment.dx)
            .putFloat("alignDy", alignment.dy)
            .putFloat("emissivity", emissivity.toFloat())
            .putFloat("reflectedC", reflectedC.toFloat())
            .putString("calPoints", TempCalibration.serialize(calibrationPoints))
            .putString("ffcCommand", ffcCommand)
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
}
