package com.exciteddev.flironepro.ui

/** What the rows of the settings list can do to the rest of the screen; the settings do not need to know who does it. */
interface SettingsActions {
    /** A setting that the picture shows was changed (and saved): apply it. */
    fun onChanged()

    fun openEmissivity()

    fun openReflected()

    fun openCalibration()

    fun openAlignment()

    fun requestFfc()

    /** Hides the control bars until the next tap on the picture. */
    fun hideControls()

    fun saveRawFrame()
}
