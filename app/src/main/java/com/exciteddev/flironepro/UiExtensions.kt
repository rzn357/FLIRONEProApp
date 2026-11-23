package com.exciteddev.flironepro

import android.content.Context
import android.content.pm.ApplicationInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** Pixels for a size in dp on this screen. */
internal fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** The language the app is currently shown in. */
internal fun Context.appLocale(): Locale = resources.configuration.locales[0]

internal fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

/** The language tag of the app's current language: the app offers only Ukrainian and English. */
internal fun Context.currentLanguageTag(): String = if (appLocale().language == "uk") "uk" else "en"

internal fun setAppLanguage(tag: String) {
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
}

/** True in debuggable builds: only they offer the demo player, the self-test and the raw-command sender. */
internal val Context.isDebuggable: Boolean
    get() = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

/** The texts the overlays draw over the picture, in the current language. */
internal fun Context.overlayTexts() = UiTexts(
    locale = appLocale(),
    waitingForCalibration = getString(R.string.note_waiting_ffc),
    shutterCalibration = getString(R.string.note_ffc),
    demoNote = getString(R.string.note_demo),
    emissivityFormat = getString(R.string.note_emissivity_fmt)
)

/** A number with one decimal place, in the app's language. */
internal fun Context.format1(x: Double): String = String.format(appLocale(), "%.1f", x)
