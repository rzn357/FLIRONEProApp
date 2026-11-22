package com.exciteddev.flironepro

import android.content.Context
import android.content.SharedPreferences

/** Налаштування користувача, що зберігаються між запусками. Поля, які читає потік обробки, позначені @Volatile. */
class AppSettings(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences("flir_settings", Context.MODE_PRIVATE)

    @Volatile var mode: DisplayMode = DisplayMode.entries.getOrElse(prefs.getInt("mode", 0)) { DisplayMode.MSX }
    @Volatile var palette: Palette = Palette.entries.getOrElse(prefs.getInt("palette", 0)) { Palette.IRON }

    /** Поворот зображення за годинниковою стрілкою: 0, 90, 180, 270. */
    @Volatile var rotation: Int = prefs.getInt("rotation", 0)
    @Volatile var mirror: Boolean = prefs.getBoolean("mirror", false)

    /** Положення хрестика в нормалізованих координатах теплового кадру. */
    @Volatile var crossU: Float = prefs.getFloat("crossU", 0.5f)
    @Volatile var crossV: Float = prefs.getFloat("crossV", 0.5f)

    @Volatile var showMarkers: Boolean = prefs.getBoolean("showMarkers", true)
    @Volatile var showScale: Boolean = prefs.getBoolean("showScale", true)

    /** Сила накладання контурів у режимі MSX: 0 слабко, 1 середньо, 2 сильно. */
    @Volatile var msxLevel: Int = prefs.getInt("msxLevel", 1)

    @Volatile var alignment: Alignment = Alignment(
        prefs.getFloat("alignScale", Alignment.DEFAULT_SCALE),
        prefs.getFloat("alignDx", Alignment.DEFAULT_DX),
        prefs.getFloat("alignDy", Alignment.DEFAULT_DY),
    )

    @Volatile var emissivity: Double = prefs.getFloat("emissivity", 1.0f).toDouble()
    @Volatile var reflectedC: Double = prefs.getFloat("reflectedC", 20.0f).toDouble()

    /** Точки калібрування температури, введені користувачем. */
    var calibrationPoints: List<TempCalibration.Point> = TempCalibration.parse(prefs.getString("calPoints", null))

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
            .putInt("rotation", rotation)
            .putBoolean("mirror", mirror)
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
            .apply()
    }

    /** Накладає збережені параметри на модель температури. */
    fun applyTo(r: Radiometry) {
        r.emissivity = emissivity
        r.reflectedC = reflectedC
        val fit = TempCalibration.fit(calibrationPoints)
        r.countsPerPhi = fit.countsPerPhi
        r.offsetCounts = fit.offsetCounts
    }
}
