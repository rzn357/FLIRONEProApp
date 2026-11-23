package com.exciteddev.flironepro.render

import android.content.Context
import com.exciteddev.flironepro.R
import com.exciteddev.flironepro.util.appLocale

/** The texts the overlays draw over the picture, in the current language. */
internal fun Context.overlayTexts() = UiTexts(
    locale = appLocale(),
    waitingForCalibration = getString(R.string.note_waiting_ffc),
    shutterCalibration = getString(R.string.note_ffc),
    demoNote = getString(R.string.note_demo),
    emissivityFormat = getString(R.string.note_emissivity_fmt)
)
