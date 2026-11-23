package com.exciteddev.flironepro

import android.content.Context
import android.view.Surface
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/** The size of the screen, the display cutout, and the rotation of the picture that follows the phone's orientation. */
class ScreenGeometry(private val activity: AppCompatActivity, private val views: StreamViews, private val settings: AppSettings) {
    /** Display-cutout / system-bar insets (used to keep the settings screen clear of the notch). */
    var contentInsets: Insets = Insets.NONE
        private set

    /** Test hook: pretend the view has this size in pixels (width, height); null = use the real one. */
    var testViewSize: Pair<Int, Int>? = null

    /** Test hook: pretend the screen has this rotation (degrees); null = use the real one. */
    var forcedDisplayRotation: Int? = null

    /** Keeps the controls clear of the display cutout. */
    fun bindInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(views.rootView) { _, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars())
            contentInsets = i
            val side = activity.dp(SIDE_PADDING_DP)
            views.topBar.updatePadding(left = side + i.left, top = side + i.top, right = side + i.right)
            views.bottomBar.updatePadding(bottom = activity.dp(BOTTOM_PADDING_DP) + i.bottom)
            views.cardHost.updatePadding(left = side + i.left, right = side + i.right)
            insets
        }
    }

    /** Size of the root view in pixels (or the test override). */
    fun viewSize(): Pair<Int, Int> {
        val dm = activity.resources.displayMetrics
        val root = views.rootView
        return testViewSize ?: Pair(root.width.takeIf { it > 0 } ?: dm.widthPixels, root.height.takeIf { it > 0 } ?: dm.heightPixels)
    }

    /** Recomputes the effective image rotation from the screen orientation and the user's settings. */
    fun updateRotation() {
        settings.rotation = Orientation.effective(settings.autoRotate, settings.rotationOffset, displayRotationDegrees())
        views.thermalView.postInvalidateOnAnimation()
    }

    @Suppress("DEPRECATION")
    private fun displayRotationDegrees(): Int = forcedDisplayRotation
        ?: when ((activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation) {
            Surface.ROTATION_90 -> Orientation.QUARTER_TURN
            Surface.ROTATION_180 -> Orientation.HALF_TURN
            Surface.ROTATION_270 -> Orientation.THREE_QUARTER_TURN
            else -> 0
        }

    private companion object {
        const val SIDE_PADDING_DP = 8
        const val BOTTOM_PADDING_DP = 16
    }
}
