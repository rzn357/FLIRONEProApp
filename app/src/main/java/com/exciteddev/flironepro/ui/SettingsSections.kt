package com.exciteddev.flironepro.ui

import android.content.Intent
import com.exciteddev.flironepro.R
import com.exciteddev.flironepro.render.DisplayMode
import com.exciteddev.flironepro.render.Orientation
import com.exciteddev.flironepro.render.Palette
import com.exciteddev.flironepro.settings.AppSettings
import com.exciteddev.flironepro.util.appLocale
import com.exciteddev.flironepro.util.currentLanguageTag
import com.exciteddev.flironepro.util.setAppLanguage

/**
 * The sections of the settings list and what each setting does. [hide] closes the settings screen (for the rows that open a
 * dialog or a card over the picture), [rebuild] draws the list again (for the rows that change what the list shows).
 */
class SettingsSections(
    private val a: StreamActivity,
    private val w: SettingsWidgets,
    private val hide: () -> Unit,
    private val rebuild: () -> Unit
) {
    private val s: AppSettings get() = a.settings

    fun build() {
        displaySection()
        paletteSection()
        temperatureSection()
        msxSection()
        imageSection()
        overlaysSection()
        languageSection()
        developerSection()
    }

    /** Saves the settings and lets the screen react to them. */
    private fun changed() {
        s.save()
        a.onSettingsChanged()
    }

    private fun displaySection() {
        w.header(R.string.sec_display)
        w.radios(
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
    }

    private fun paletteSection() {
        w.header(R.string.sec_palette)
        w.radios(Palette.entries.map { Triple(it, w.paletteLabel(it), w.swatch(it)) }, s.palette) {
            s.palette = it
            changed()
        }
    }

    private fun temperatureSection() {
        w.header(R.string.sec_temperature)
        w.actionThenHide(a.getString(R.string.row_emissivity_fmt, String.format(a.appLocale(), "%.2f", s.emissivity))) {
            a.dialogs.openEmissivity()
        }
        w.actionThenHide(a.getString(R.string.row_reflected_fmt, String.format(a.appLocale(), "%.0f", s.reflectedC))) {
            a.dialogs.openReflected()
        }
        w.actionThenHide(a.getString(R.string.row_calibration)) { a.cards.openCalibration() }
        w.actionThenHide(a.getString(R.string.row_ffc)) { a.ffc.request() }
    }

    private fun msxSection() {
        w.header(R.string.sec_msx)
        w.radios(
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
        w.actionThenHide(a.getString(R.string.row_msx_alignment)) { a.cards.openAlignment() }
    }

    private fun imageSection() {
        w.header(R.string.sec_image)
        w.switch(R.string.sw_auto_orientation, s.autoRotate) {
            s.autoRotate = it
            changed()
        }
        w.action(a.getString(R.string.row_rotate_fmt, s.rotationOffset)) {
            s.rotationOffset = (s.rotationOffset + Orientation.QUARTER_TURN) % Orientation.FULL_TURN
            changed()
            rebuild()
        }
        w.switch(R.string.sw_mirror, s.mirror) {
            s.mirror = it
            changed()
        }
        w.switch(R.string.sw_fill, s.fillScreen) {
            s.fillScreen = it
            changed()
        }
        w.switch(R.string.sw_hide_controls, false) {
            if (it) {
                hide()
                a.chrome.setControlsHidden(true)
            }
        }
    }

    private fun overlaysSection() {
        w.header(R.string.sec_overlays)
        w.switch(R.string.sw_markers, s.showMarkers) {
            s.showMarkers = it
            changed()
        }
        w.switch(R.string.sw_scale, s.showScale) {
            s.showScale = it
            changed()
        }
    }

    private fun languageSection() {
        w.header(R.string.sec_language)
        w.radios(
            listOf(
                Triple("uk", a.getString(R.string.lang_uk), null),
                Triple("en", a.getString(R.string.lang_en), null)
            ),
            a.currentLanguageTag()
        ) { if (it != a.currentLanguageTag()) setAppLanguage(it) }
    }

    private fun developerSection() {
        w.header(R.string.sec_developer)
        w.actionThenHide(a.getString(R.string.row_usb_diag)) { a.startActivity(Intent(a, DiagnosticsActivity::class.java)) }
        w.action(a.getString(R.string.row_save_raw)) {
            a.recording.saveRawFrame()
            rebuild()
        }
    }
}
