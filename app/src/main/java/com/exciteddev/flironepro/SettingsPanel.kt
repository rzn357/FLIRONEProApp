package com.exciteddev.flironepro

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.ContextCompat

/**
 * The settings screen opened by the gear icon. It covers the whole screen (so it never sits between the gear and the
 * photo / video buttons) with a semi-transparent dark background, so the camera picture stays visible behind it, and has
 * its own close icon. It contains only settings. In landscape the list is limited to a comfortable column width.
 * The rows themselves are in [SettingsSections], built from the pieces of [SettingsWidgets].
 */
class SettingsPanel(private val a: StreamActivity, private val host: FrameLayout) {
    private val root = LinearLayout(a)
    private val scroll = ScrollView(a)
    private val content = LinearLayout(a)
    private val widgets = SettingsWidgets(a, content, ::hide)
    private val sections = SettingsSections(a, widgets, ::hide, ::rebuild)

    val isShown: Boolean get() = host.visibility == View.VISIBLE

    init {
        host.setBackgroundColor(ContextCompat.getColor(a, R.color.overlay_fullscreen))
        host.isClickable = true // swallow touches, so nothing underneath reacts
        root.orientation = LinearLayout.VERTICAL
        host.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(header(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // The scrolling list of settings (nothing else: no picture, no extra window).
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(a.dp(20), a.dp(2), a.dp(20), a.dp(28))
        scroll.addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** The title and the close icon. */
    private fun header(): LinearLayout {
        val header = LinearLayout(a)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(a.dp(20), a.dp(8), a.dp(8), a.dp(4))
        val title = widgets.title(a.getString(R.string.settings_title))
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = ImageButton(a)
        close.setImageResource(R.drawable.ic_close)
        close.scaleType = ImageView.ScaleType.FIT_CENTER
        close.setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10))
        close.contentDescription = a.getString(R.string.btn_close)
        val out = TypedValue()
        a.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)
        close.setBackgroundResource(out.resourceId)
        close.setOnClickListener { hide() }
        header.addView(close, LinearLayout.LayoutParams(a.dp(48), a.dp(48)))
        return header
    }

    fun toggle() = if (isShown) hide() else show()

    fun show() {
        val (w, h) = a.geometry.viewSize()
        val landscape = w > h
        val i = a.geometry.contentInsets
        root.setPadding(i.left, i.top, i.right, i.bottom)
        // In landscape the list is a centered column, so the lines are not stretched across the whole screen.
        root.layoutParams = FrameLayout.LayoutParams(
            if (landscape) minOf(a.dp(560), w) else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER_HORIZONTAL
        )
        scroll.scrollTo(0, 0)
        rebuild()
        host.visibility = View.VISIBLE
        a.chrome.onSettingsVisibility(true)
    }

    fun hide() {
        host.visibility = View.GONE
        a.chrome.onSettingsVisibility(false)
    }

    fun rebuild() {
        val keepScroll = scroll.scrollY
        content.removeAllViews()
        sections.build()
        if (keepScroll > 0) scroll.post { scroll.scrollTo(0, keepScroll) }
    }
}
