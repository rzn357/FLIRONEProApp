package com.exciteddev.flironepro.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.exciteddev.flironepro.R
import com.exciteddev.flironepro.settings.AppSettings

/**
 * The settings screen opened by the gear icon. It covers the whole screen (so it never sits between the gear and the
 * photo / video buttons) with a semi-transparent dark background, so the camera picture stays visible behind it, and has
 * its own close icon. It contains only settings. In landscape the list is limited to a comfortable column width.
 * The frame (title bar, close icon, scrolling list) is the `settings_panel` layout; the rows themselves are in
 * [SettingsSections], built from the pieces of [SettingsWidgets].
 */
class SettingsPanel(
    private val a: AppCompatActivity,
    private val host: FrameLayout,
    private val geometry: ScreenGeometry,
    settings: AppSettings,
    actions: SettingsActions
) {
    /** Called when the screen opens or closes (the bars and the overlays under it must give way). */
    var onShownChanged: (Boolean) -> Unit = {}

    private val root = a.layoutInflater.inflate(R.layout.settings_panel, host, false) as LinearLayout
    private val scroll = root.findViewById<ScrollView>(R.id.settingsScroll)
    private val content = root.findViewById<LinearLayout>(R.id.settingsContent)
    private val widgets = SettingsWidgets(a, content, ::hide)
    private val sections = SettingsSections(a, settings, widgets, actions, ::hide, ::rebuild)

    val isShown: Boolean get() = host.visibility == View.VISIBLE

    init {
        host.setBackgroundColor(ContextCompat.getColor(a, R.color.overlay_fullscreen))
        host.isClickable = true // swallow touches, so nothing underneath reacts
        host.addView(root)
        root.findViewById<TextView>(R.id.settingsTitle).settingsShadow()
        root.findViewById<View>(R.id.settingsClose).setOnClickListener { hide() }
    }

    fun toggle() = if (isShown) hide() else show()

    fun show() {
        val (w, h) = geometry.viewSize()
        val landscape = w > h
        val i = geometry.contentInsets
        root.setPadding(i.left, i.top, i.right, i.bottom)
        // In landscape the list is a centered column, so the lines are not stretched across the whole screen.
        val columnWidth = a.resources.getDimensionPixelSize(R.dimen.settings_column_max_width)
        root.layoutParams = FrameLayout.LayoutParams(
            if (landscape) minOf(columnWidth, w) else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER_HORIZONTAL
        )
        scroll.scrollTo(0, 0)
        rebuild()
        host.visibility = View.VISIBLE
        onShownChanged(true)
    }

    fun hide() {
        host.visibility = View.GONE
        onShownChanged(false)
    }

    fun rebuild() {
        val keepScroll = scroll.scrollY
        content.removeAllViews()
        sections.build()
        if (keepScroll > 0) scroll.post { scroll.scrollTo(0, keepScroll) }
    }
}
