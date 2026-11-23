package com.exciteddev.flironepro

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

/**
 * The settings screen opened by the gear icon. It covers the whole screen (so it never sits between the gear and the
 * photo / video buttons) with a semi-transparent dark background, so the camera picture stays visible behind it, and has
 * its own close icon. It contains only settings. In landscape the list is limited to a comfortable column width.
 */
class SettingsPanel(private val a: StreamActivity, private val host: FrameLayout) {
    private val density = a.resources.displayMetrics.density
    private val white = ContextCompat.getColor(a, R.color.text_primary_on_dark)
    private val amber = ContextCompat.getColor(a, R.color.accent_amber)
    private val grey = ContextCompat.getColor(a, R.color.text_secondary_on_dark)

    private val root = LinearLayout(a)
    private val scroll = ScrollView(a)
    private val content = LinearLayout(a)
    private var firstHeader = true

    val isShown: Boolean get() = host.visibility == View.VISIBLE

    init {
        host.setBackgroundColor(ContextCompat.getColor(a, R.color.overlay_fullscreen))
        host.isClickable = true // swallow touches, so nothing underneath reacts
        root.orientation = LinearLayout.VERTICAL
        host.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Header: title and the close icon.
        val header = LinearLayout(a)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(20), dp(8), dp(8), dp(4))
        val title = textView(a.getString(R.string.settings_title), 22f, white, bold = true)
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = ImageButton(a)
        close.setImageResource(R.drawable.ic_close)
        close.scaleType = ImageView.ScaleType.FIT_CENTER
        close.setPadding(dp(10), dp(10), dp(10), dp(10))
        close.contentDescription = a.getString(R.string.btn_close)
        val out = TypedValue()
        a.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)
        close.setBackgroundResource(out.resourceId)
        close.setOnClickListener { hide() }
        header.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // The scrolling list of settings (nothing else: no picture, no extra window).
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(20), dp(2), dp(20), dp(28))
        scroll.addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun toggle() = if (isShown) hide() else show()

    fun show() {
        val (w, h) = a.viewSize()
        val landscape = w > h
        val i = a.contentInsets
        root.setPadding(i.left, i.top, i.right, i.bottom)
        // In landscape the list is a centered column, so the lines are not stretched across the whole screen.
        root.layoutParams = FrameLayout.LayoutParams(
            if (landscape) minOf(dp(560), w) else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER_HORIZONTAL
        )
        scroll.scrollTo(0, 0)
        rebuild()
        host.visibility = View.VISIBLE
        a.onSettingsVisibility(true)
    }

    fun hide() {
        host.visibility = View.GONE
        a.onSettingsVisibility(false)
    }

    // ---------------------------------------------------------------- content

    private fun changed() {
        a.settings.save()
        a.onSettingsChanged()
    }

    fun rebuild() {
        val keepScroll = scroll.scrollY
        content.removeAllViews()
        firstHeader = true
        val s = a.settings

        header(R.string.sec_display)
        radios(
            listOf(
                Triple(DisplayMode.MSX, a.getString(R.string.mode_msx), null),
                Triple(DisplayMode.THERMAL, a.getString(R.string.mode_thermal), null),
                Triple(DisplayMode.VISIBLE, a.getString(R.string.mode_visible), null)
            ),
            s.mode
        ) {
            s.mode = it
            changed()
        }

        header(R.string.sec_palette)
        radios(Palette.entries.map { Triple(it, paletteLabel(it), swatch(it)) }, s.palette) {
            s.palette = it
            changed()
        }

        header(R.string.sec_temperature)
        action(a.getString(R.string.row_emissivity_fmt, String.format(a.appLocale(), "%.2f", s.emissivity))) {
            hide()
            a.openEmissivityDialog()
        }
        action(a.getString(R.string.row_reflected_fmt, String.format(a.appLocale(), "%.0f", s.reflectedC))) {
            hide()
            a.openReflectedDialog()
        }
        action(a.getString(R.string.row_calibration)) {
            hide()
            a.openCalibrationCard()
        }
        action(a.getString(R.string.row_ffc)) {
            hide()
            a.requestShutterCalibration()
        }

        header(R.string.sec_msx)
        radios(
            listOf(
                Triple(0, a.getString(R.string.msx_weak), null),
                Triple(1, a.getString(R.string.msx_medium), null),
                Triple(2, a.getString(R.string.msx_strong), null)
            ),
            s.msxLevel.coerceIn(0, 2)
        ) {
            s.msxLevel = it
            changed()
        }
        action(a.getString(R.string.row_msx_alignment)) {
            hide()
            a.openAlignmentCard()
        }

        header(R.string.sec_image)
        switch(R.string.sw_auto_orientation, s.autoRotate) {
            s.autoRotate = it
            changed()
        }
        action(a.getString(R.string.row_rotate_fmt, s.rotationOffset)) {
            s.rotationOffset = (s.rotationOffset + 90) % 360
            changed()
            rebuild()
        }
        switch(R.string.sw_mirror, s.mirror) {
            s.mirror = it
            changed()
        }
        switch(R.string.sw_fill, s.fillScreen) {
            s.fillScreen = it
            changed()
        }
        switch(R.string.sw_hide_controls, false) {
            if (it) {
                hide()
                a.setControlsHidden(true)
            }
        }

        header(R.string.sec_overlays)
        switch(R.string.sw_markers, s.showMarkers) {
            s.showMarkers = it
            changed()
        }
        switch(R.string.sw_scale, s.showScale) {
            s.showScale = it
            changed()
        }

        header(R.string.sec_language)
        radios(
            listOf(
                Triple("uk", a.getString(R.string.lang_uk), null),
                Triple("en", a.getString(R.string.lang_en), null)
            ),
            a.currentLanguageTag()
        ) { if (it != a.currentLanguageTag()) a.setLanguage(it) }

        header(R.string.sec_developer)
        action(a.getString(R.string.row_usb_diag)) {
            hide()
            a.openDiagnostics()
        }
        action(a.getString(R.string.row_save_raw)) {
            a.saveRawFrame()
            rebuild()
        }

        if (keepScroll > 0) scroll.post { scroll.scrollTo(0, keepScroll) }
    }

    private fun paletteLabel(p: Palette): String = a.getString(
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
    private fun swatch(p: Palette): View {
        val lut = Palettes.lut(p)
        val colors = IntArray(6) { lut[it * 255 / 5] }
        val v = View(a)
        v.tag = p
        v.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
            cornerRadius = dp(4).toFloat()
            setStroke(dp(1), 0x66FFFFFF)
        }
        v.layoutParams = LinearLayout.LayoutParams(dp(72), dp(14)).apply { marginStart = dp(8) }
        return v
    }

    // ---------------------------------------------------------------- building blocks

    private fun dp(v: Int) = (v * density).toInt()

    /** A soft dark shadow keeps the text readable over a bright picture shining through the translucent background. */
    private fun <T : TextView> T.shadowed(): T {
        setShadowLayer(3f * density, 0f, density, 0xE6000000.toInt())
        return this
    }

    private fun textView(text: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView = TextView(a).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        shadowed()
    }

    /** Section header with a generous gap above it (to separate sections) and a thin divider below. */
    private fun header(res: Int) {
        val first = firstHeader
        firstHeader = false
        val t = textView(a.getString(res).uppercase(a.appLocale()), 12f, amber, bold = true)
        t.letterSpacing = 0.08f
        t.setPadding(0, if (first) dp(8) else dp(30), 0, if (first) dp(6) else dp(2))
        content.addView(t)
        if (first) return // the very first title has no divider under it
        val line = View(a)
        line.setBackgroundColor(ContextCompat.getColor(a, R.color.divider_light))
        content.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { bottomMargin = dp(1) })
    }

    private fun tint(): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(amber, grey)
    )

    /** Radio buttons with manual mutual exclusion, so a row can also hold an extra view (e.g. a palette swatch). */
    private fun <T> radios(options: List<Triple<T, String, View?>>, selected: T, onSelect: (T) -> Unit) {
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
                minHeight = dp(40)
                shadowed()
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

    private fun switch(labelRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
        val sw = SwitchCompat(a).apply {
            text = a.getString(labelRes)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(white)
            isChecked = checked
            minHeight = dp(44)
            thumbTintList = tint()
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0x66FFB74D, 0x44FFFFFF)
            )
            setOnCheckedChangeListener { _, isOn -> onChange(isOn) }
            shadowed()
        }
        content.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun action(label: String, onClick: () -> Unit) {
        val tv = textView("$label  ›", 15f, white)
        tv.minHeight = dp(44)
        tv.gravity = Gravity.CENTER_VERTICAL
        val tvOut = TypedValue()
        a.theme.resolveAttribute(android.R.attr.selectableItemBackground, tvOut, true)
        tv.setBackgroundResource(tvOut.resourceId)
        tv.setOnClickListener { onClick() }
        content.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
