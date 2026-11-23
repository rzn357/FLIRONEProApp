package com.exciteddev.flironepro

import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import kotlin.math.abs

/** The dialogs in which the user enters the emissivity and the reflected temperature (and a general "enter a number" prompt). */
class ParameterDialogs(private val activity: AppCompatActivity, private val settings: AppSettings, private val pipeline: StreamPipeline) {
    private val emissivityPresets = listOf(
        R.string.emis_none to 1.00,
        R.string.emis_skin to 0.98,
        R.string.emis_water to 0.96,
        R.string.emis_plastic to 0.95,
        R.string.emis_masonry to 0.93,
        R.string.emis_glass to 0.92,
        R.string.emis_wood to 0.90,
        R.string.emis_oxidized to 0.80,
        R.string.emis_shiny to 0.20
    )

    fun openEmissivity() {
        val labels = emissivityPresets.map { String.format(Locale.US, "%.2f — %s", it.second, activity.getString(it.first)) } +
            activity.getString(R.string.emis_custom)
        val preset = emissivityPresets.indexOfFirst { abs(it.second - settings.emissivity) < PRESET_TOLERANCE }
        val current = if (preset < 0) labels.lastIndex else preset
        MaterialAlertDialogBuilder(activity).setTitle(R.string.emis_title)
            .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                dialog.dismiss()
                if (which < emissivityPresets.size) {
                    setEmissivity(emissivityPresets[which].second)
                } else {
                    promptNumber(
                        activity.getString(R.string.emis_custom_title),
                        null,
                        String.format(Locale.US, "%.2f", settings.emissivity)
                    ) { setEmissivity(it) }
                }
            }.setNegativeButton(R.string.btn_cancel, null).show()
    }

    private fun setEmissivity(e: Double) {
        settings.emissivity = e.coerceIn(Radiometry.MIN_EMISSIVITY, 1.0)
        pipeline.radiometry.emissivity = settings.emissivity
        settings.save()
    }

    fun openReflected() {
        promptNumber(
            activity.getString(R.string.reflected_title),
            activity.getString(R.string.reflected_hint),
            String.format(Locale.US, "%.0f", settings.reflectedC)
        ) {
            settings.reflectedC = it.coerceIn(MIN_REFLECTED_C, MAX_REFLECTED_C)
            pipeline.radiometry.reflectedC = settings.reflectedC
            settings.save()
        }
    }

    /** Asks for a number (the decimal comma is accepted); [onOk] gets it, or an error message is shown if it is not a number. */
    fun promptNumber(title: String, hint: String?, initial: String, onOk: (Double) -> Unit) {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(initial)
            setSelection(text.length)
        }
        val box = FrameLayout(activity).apply {
            setPadding(activity.dp(BOX_SIDE_DP), activity.dp(BOX_TOP_DP), activity.dp(BOX_SIDE_DP), 0)
            addView(input)
        }
        val builder = MaterialAlertDialogBuilder(activity).setTitle(title).setView(box)
        if (hint != null) builder.setMessage(hint)
        builder.setPositiveButton(R.string.btn_ok) { _, _ ->
            val value = input.text.toString().replace(',', '.').toDoubleOrNull()
            if (value == null) activity.toast(activity.getString(R.string.err_invalid_number)) else onOk(value)
        }.setNegativeButton(R.string.btn_cancel, null).show()
    }

    private companion object {
        /** An emissivity within this of a preset counts as that preset. */
        const val PRESET_TOLERANCE = 0.005
        const val MIN_REFLECTED_C = -50.0
        const val MAX_REFLECTED_C = 200.0
        const val BOX_SIDE_DP = 24
        const val BOX_TOP_DP = 8
    }
}
