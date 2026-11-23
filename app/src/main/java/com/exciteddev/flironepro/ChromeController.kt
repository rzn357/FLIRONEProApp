package com.exciteddev.flironepro

import android.view.View
import android.widget.ImageButton
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** The frame around the picture: the system bars, the top and bottom control bars, the settings button and the back button. */
class ChromeController(
    private val activity: AppCompatActivity,
    private val views: StreamViews,
    private val cards: ToolCards,
    private val settingsPanel: SettingsPanel
) {
    /** True while the user has hidden the control bars (a tap on the picture brings them back). */
    var controlsHidden = false
        private set

    /** Connects the settings button and the back button. */
    fun bind() {
        activity.findViewById<ImageButton>(R.id.btnSettings).setOnClickListener {
            cards.hide()
            settingsPanel.toggle()
        }
        activity.onBackPressedDispatcher.addCallback(
            activity,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        settingsPanel.isShown -> settingsPanel.hide()

                        cards.isOpen -> cards.hide()

                        controlsHidden -> setControlsHidden(false)

                        else -> {
                            isEnabled = false
                            activity.onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            }
        )
    }

    fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    fun setControlsHidden(hidden: Boolean) {
        controlsHidden = hidden
        val visibility = if (hidden) View.GONE else View.VISIBLE
        views.topBar.visibility = visibility
        views.bottomBar.visibility = visibility
        if (hidden) {
            cards.hide()
            activity.toast(activity.getString(R.string.toast_controls_hidden))
        }
    }

    /** A tap on the picture brings hidden controls back. */
    fun onTap() {
        if (controlsHidden) setControlsHidden(false)
    }

    /** While the full-screen settings are open, the bars and the overlays under it are hidden, so nothing ghosts through. */
    fun onSettingsVisibility(shown: Boolean) {
        views.thermalView.overlaysSuppressed = shown
        val visibility = when {
            shown -> View.INVISIBLE
            controlsHidden -> View.GONE
            else -> View.VISIBLE
        }
        views.topBar.visibility = visibility
        views.bottomBar.visibility = visibility
        views.thermalView.postInvalidateOnAnimation()
    }
}
