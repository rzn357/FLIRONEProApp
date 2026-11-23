package com.exciteddev.flironepro

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

/** A soft dark shadow keeps the text readable over a bright picture shining through the translucent background. */
private fun <T : TextView> T.shadowed(density: Float): T {
    setShadowLayer(3f * density, 0f, density, 0xE6000000.toInt())
    return this
}

/** The building blocks of the settings list (section headers, radio buttons, switches, rows that open something). */
class SettingsWidgets(private val a: AppCompatActivity, private val content: LinearLayout, private val hide: () -> Unit) {
    private val density = a.resources.displayMetrics.density
    private val white = ContextCompat.getColor(a, R.color.text_primary_on_dark)
    private val amber = ContextCompat.getColor(a, R.color.accent_amber)
    private val grey = ContextCompat.getColor(a, R.color.text_secondary_on_dark)

    fun textView(text: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView = TextView(a).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        shadowed(density)
    }

    fun title(text: String): TextView = textView(text, 22f, white, bold = true)

    /** Section header with a generous gap above it (to separate sections) and a thin divider below. */
    fun header(res: Int) {
        val first = content.childCount == 0
        val t = textView(a.getString(res).uppercase(a.appLocale()), 12f, amber, bold = true)
        t.letterSpacing = 0.08f
        t.setPadding(0, if (first) a.dp(8) else a.dp(30), 0, if (first) a.dp(6) else a.dp(2))
        content.addView(t)
        if (!first) { // the very first title has no divider under it
            val line = View(a)
            line.setBackgroundColor(ContextCompat.getColor(a, R.color.divider_light))
            content.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { bottomMargin = a.dp(1) })
        }
    }

    private fun tint(): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(amber, grey)
    )

    /** Radio buttons with manual mutual exclusion, so a row can also hold an extra view (e.g. a palette swatch). */
    fun <T> radios(options: List<Triple<T, String, View?>>, selected: T, onSelect: (T) -> Unit) {
        val buttons = ArrayList<RadioButton>()
        for ((value, label, extra) in options) {
            val row = LinearLayout(a)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val rb = RadioButton(a).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(white)
                buttonTintList = tint()
                isChecked = value == selected
                minHeight = a.dp(40)
                shadowed(density)
            }
            buttons.add(rb)
            rb.setOnClickListener {
                buttons.forEach { it.isChecked = false }
                rb.isChecked = true
                onSelect(value)
            }
            row.addView(rb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (extra != null) row.addView(extra)
            // A tap anywhere on the row outside the label (e.g. on the palette's color strip) selects the option as well.
            row.setOnClickListener { rb.performClick() }
            content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    fun switch(labelRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
        val sw = SwitchCompat(a).apply {
            text = a.getString(labelRes)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(white)
            isChecked = checked
            minHeight = a.dp(44)
            thumbTintList = tint()
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0x66FFB74D, 0x44FFFFFF)
            )
            setOnCheckedChangeListener { _, isOn -> onChange(isOn) }
            shadowed(density)
        }
        content.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** A row that does something (usually opens a dialog) when it is tapped. */
    fun action(label: String, onClick: () -> Unit) {
        val tv = textView("$label  ›", 15f, white)
        tv.minHeight = a.dp(44)
        tv.gravity = Gravity.CENTER_VERTICAL
        val background = TypedValue()
        a.theme.resolveAttribute(android.R.attr.selectableItemBackground, background, true)
        tv.setBackgroundResource(background.resourceId)
        tv.setOnClickListener { onClick() }
        content.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
        val colors = IntArray(6) { lut[it * 255 / 5] }
        val v = View(a)
        v.tag = p
        v.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
            cornerRadius = a.dp(4).toFloat()
            setStroke(a.dp(1), 0x66FFFFFF)
        }
        v.layoutParams = LinearLayout.LayoutParams(a.dp(72), a.dp(14)).apply { marginStart = a.dp(8) }
        return v
    }
}
