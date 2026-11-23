package com.exciteddev.flironepro

import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/** The two cards that slide in over the picture: the temperature calibration and the MSX edge alignment. */
class ToolCards(
    private val activity: AppCompatActivity,
    private val views: StreamViews,
    private val settings: AppSettings,
    private val pipeline: StreamPipeline,
    private val dialogs: ParameterDialogs
) {
    val isOpen: Boolean
        get() = views.calCard.visibility == View.VISIBLE || views.alignCard.visibility == View.VISIBLE

    /** Connects the buttons of both cards. */
    fun bind() {
        button(R.id.btnCalAdd) { promptReferencePoint() }
        button(R.id.btnCalReset) { reset() }
        button(R.id.btnCalClose) { hide() }
        button(R.id.btnAlignLeft) { alignChange(dx = NUDGE) }
        button(R.id.btnAlignRight) { alignChange(dx = -NUDGE) }
        button(R.id.btnAlignUp) { alignChange(dy = NUDGE) }
        button(R.id.btnAlignDown) { alignChange(dy = -NUDGE) }
        button(R.id.btnAlignSmaller) { alignChange(ds = SCALE_STEP) }
        button(R.id.btnAlignBigger) { alignChange(ds = -SCALE_STEP) }
        button(R.id.btnAlignReset) {
            settings.alignment = Alignment()
            alignChange()
        }
        button(R.id.btnAlignDone) { hide() }
    }

    private fun button(id: Int, onClick: () -> Unit) = activity.findViewById<Button>(id).setOnClickListener { onClick() }

    fun hide() {
        views.calCard.visibility = View.GONE
        views.alignCard.visibility = View.GONE
    }

    // ---------------------------------------------------------------- edge alignment

    fun openAlignment() {
        settings.mode = DisplayMode.MSX
        settings.save()
        hide()
        views.alignCard.visibility = View.VISIBLE
        alignChange()
    }

    /** Moves / scales the visible picture under the thermal one a little, saves it and shows the new values. */
    private fun alignChange(dx: Float = 0f, dy: Float = 0f, ds: Float = 0f) {
        val a = settings.alignment
        settings.alignment = a.copy(
            scale = (a.scale + ds).coerceIn(MIN_SCALE, MAX_SCALE),
            dx = (a.dx + dx).coerceIn(-MAX_SHIFT, MAX_SHIFT),
            dy = (a.dy + dy).coerceIn(-MAX_SHIFT, MAX_SHIFT)
        )
        settings.save()
        val n = settings.alignment
        val locale = activity.appLocale()
        val shifts = { x: Float -> String.format(locale, "%+.3f", x) }
        views.alignInfo.text =
            activity.getString(R.string.align_info_fmt, shifts(n.dx), shifts(n.dy), String.format(locale, "%.3f", n.scale))
        views.thermalView.postInvalidateOnAnimation()
    }

    // ---------------------------------------------------------------- temperature calibration

    fun openCalibration() {
        hide()
        views.calCard.visibility = View.VISIBLE
        updateCalText()
    }

    fun updateCalText() {
        val locale = activity.appLocale()
        val shown = views.thermalView.currentFrame()?.second
        val live = shown?.cross?.tempC?.let { String.format(locale, "%.1f °C", it) } ?: "—"
        val points = settings.calibrationPoints
        val fit = TempCalibration.fit(points)
        val mode = when {
            points.isEmpty() -> activity.getString(R.string.cal_mode_factory)
            fit.fittedSlope -> activity.getString(R.string.cal_mode_fit_fmt, points.size)
            else -> activity.getString(R.string.cal_mode_offset_fmt, points.size)
        }
        val emissivity = String.format(locale, "%.2f", settings.emissivity)
        views.calText.text = activity.getString(R.string.cal_hint) + "\n" +
            activity.getString(R.string.cal_live_fmt, live, emissivity) + "\n" +
            activity.getString(
                R.string.cal_params_fmt,
                activity.format1(fit.countsPerPhi),
                String.format(locale, "%+.0f", fit.offsetCounts),
                mode
            )
    }

    /** Asks for the true temperature under the crosshair and adds it as a reference point. */
    private fun promptReferencePoint() {
        val sample = pipeline.processor?.calibration?.average()
        if (sample == null) {
            activity.toast(activity.getString(R.string.cal_no_data))
        } else {
            dialogs.promptNumber(
                activity.getString(R.string.cal_prompt_title),
                activity.getString(R.string.cal_prompt_hint),
                sample.tempC?.let { String.format(Locale.US, "%.1f", it) } ?: ""
            ) { addCalibrationPoint(it, sample) }
        }
    }

    /** Adds a reference point, refits the model and saves. Returns a description of the result. */
    fun addCalibrationPoint(trueC: Double, s: CalSample): String {
        val radiometry = pipeline.radiometry
        val point = radiometry.calibrationPoint(s.relative, s.phiShutter, trueC, radiometry.emissivity, radiometry.reflectedC)
        settings.calibrationPoints = (settings.calibrationPoints + point).takeLast(TempCalibration.MAX_POINTS)
        pipeline.applySettings()
        settings.save()
        val fit = TempCalibration.fit(settings.calibrationPoints)
        val text = activity.getString(
            R.string.cal_added_fmt,
            activity.format1(fit.countsPerPhi),
            String.format(activity.appLocale(), "%+.0f", fit.offsetCounts),
            activity.getString(if (fit.fittedSlope) R.string.cal_fit_full else R.string.cal_fit_offset_only)
        )
        DiagLog.log("Calibration: $text; points ${settings.calibrationPoints.size}")
        activity.toast(text)
        updateCalText()
        return text
    }

    fun reset() {
        settings.calibrationPoints = emptyList()
        pipeline.applySettings()
        settings.save()
        activity.toast(activity.getString(R.string.cal_reset_done))
        updateCalText()
    }

    private companion object {
        /** One press of an alignment arrow moves the picture by this fraction of its size... */
        const val NUDGE = 0.005f

        /** ...and one press of the size buttons changes the scale by this much. */
        const val SCALE_STEP = 0.01f
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 1.5f
        const val MAX_SHIFT = 0.4f
    }
}
