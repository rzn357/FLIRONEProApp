package com.exciteddev.flironepro.ui

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.exciteddev.flironepro.R
import com.exciteddev.flironepro.render.Palette
import com.exciteddev.flironepro.render.Palettes
import com.exciteddev.flironepro.util.appLocale

/** How many colors the strip next to a palette's name shows. */
private const val SWATCH_STOPS = 6

/**
 * Gives the text its soft shadow (the color comes from the settings text style). The radius and the offset are dimens here
 * because a style cannot hold them in dp.
 */
internal fun <T : TextView> T.settingsShadow(): T {
    val res = resources
    setShadowLayer(res.getDimension(R.dimen.settings_shadow_radius), 0f, res.getDimension(R.dimen.settings_shadow_offset_y), shadowColor)
    return this
}

/**
 * The building blocks of the settings list (section headers, radio buttons, switches, rows that open something).
 * How each one looks (sizes, colors, shadow) is in its layout and in the settings styles; here only the texts and the
 * reactions are filled in.
 */
class SettingsWidgets(private val a: AppCompatActivity, private val content: LinearLayout, private val hide: () -> Unit) {
    private fun <V : View> inflate(@LayoutRes layout: Int): V {
        @Suppress("UNCHECKED_CAST")
        return a.layoutInflater.inflate(layout, content, false) as V
    }

    /** Section header with a generous gap above it (to separate sections) and a thin divider below. */
    fun header(res: Int) {
        val first = content.childCount == 0 // the very first title has less room above it and no divider under it
        val row = inflate<View>(if (first) R.layout.settings_header_first else R.layout.settings_header)
        row.findViewById<TextView>(R.id.settingsHeaderText).apply {
            text = a.getString(res).uppercase(a.appLocale())
            settingsShadow()
        }
        content.addView(row)
    }

    /** Radio buttons with manual mutual exclusion, so a row can also hold an extra view (e.g. a palette swatch). */
    fun <T> radios(options: List<Triple<T, String, View?>>, selected: T, onSelect: (T) -> Unit) {
        val buttons = ArrayList<RadioButton>()
        for ((value, label, extra) in options) {
            val row = inflate<LinearLayout>(R.layout.settings_radio_row)
            val rb = row.findViewById<RadioButton>(R.id.settingsRadio).apply {
                text = label
                isChecked = value == selected
                settingsShadow()
            }
            buttons.add(rb)
            rb.setOnClickListener {
                buttons.forEach { it.isChecked = false }
                rb.isChecked = true
                onSelect(value)
            }
            if (extra != null) row.addView(extra)
            // A tap anywhere on the row outside the label (e.g. on the palette's color strip) selects the option as well.
            row.setOnClickListener { rb.performClick() }
            content.addView(row)
        }
    }

    fun switch(labelRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
        val sw = inflate<SwitchCompat>(R.layout.settings_switch).apply {
            text = a.getString(labelRes)
            isChecked = checked
            setOnCheckedChangeListener { _, isOn -> onChange(isOn) }
            settingsShadow()
        }
        content.addView(sw)
    }

    /** A row that does something (usually opens a dialog) when it is tapped. */
    fun action(label: String, onClick: () -> Unit) {
        val row = inflate<TextView>(R.layout.settings_action).apply {
            text = "$label  ›"
            setOnClickListener { onClick() }
            settingsShadow()
        }
        content.addView(row)
    }

    /** A row that closes the settings screen first (it opens a dialog or a card over the picture) and then does [block]. */
    fun actionThenHide(label: String, block: () -> Unit) = action(label) {
        hide()
        block()
    }

    fun paletteLabel(p: Palette): String = a.getString(
        when (p) {
            Palette.IRON -> R.string.palette_iron
            Palette.RAINBOW -> R.string.palette_rainbow
            Palette.GRAY -> R.string.palette_white_hot
            Palette.BLACK_HOT -> R.string.palette_black_hot
            Palette.LAVA -> R.string.palette_lava
            Palette.ARCTIC -> R.string.palette_arctic
        }
    )

    /** A small gradient strip previewing the palette colors. */
    fun swatch(p: Palette): View {
        val lut = Palettes.lut(p)
        val colors = IntArray(SWATCH_STOPS) { lut[it * lut.lastIndex / (SWATCH_STOPS - 1)] }
        val strip = inflate<View>(R.layout.settings_swatch)
        strip.tag = p // the developer self-test finds the strip by it
        (strip.background.mutate() as GradientDrawable).colors = colors
        return strip
    }
}
