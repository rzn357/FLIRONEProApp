package com.exciteddev.flironepro.ui

import com.exciteddev.flironepro.debug.DiagLog
import com.exciteddev.flironepro.render.DisplayMode
import com.exciteddev.flironepro.render.Palette

/**
 * Diagnostic self-test, started from adb (debug builds): replays saved frames in demo mode, renders the whole UI into PNG
 * snapshots (portrait and landscape, every mode, settings panel, tool cards, fill mode), exercises calibration, video
 * recording and touch mapping, and writes the results to files/snapshots and the log (lines starting with "SELFTEST").
 */
internal class SelfTest(private val a: StreamActivity, private val lang: String?) {

    /** Prefix of the snapshot file names; a run in a specific language only takes the UI snapshots. */
    private val kit = SelfTestKit(a, if (lang != null) "${lang}_" else "")
    private val quick = lang != null

    fun start() {
        Thread({ run() }, "flir-selftest").start()
    }

    @Suppress("TooGenericExceptionCaught") // a failure of any step is reported in the log, it must not crash the app
    private fun run() {
        try {
            DiagLog.log("SELFTEST: start")
            Thread.sleep(3500)
            kit.dir.mkdirs()
            if (!quick) kit.dir.listFiles()?.forEach { it.delete() }

            portraitModes()
            recordingButton()
            settingsScreen()
            toolCards()
            fillAndLandscape()

            if (quick) {
                // The language is deliberately not restored from here: changing it re-creates a finishing activity as a brand new one.
                restoreDefaults()
                DiagLog.log("SELFTEST: finished (language run '$lang')")
            } else {
                SelfTestChecks(a, kit).runAll()
                DiagLog.log("SELFTEST: finished")
            }
            a.runOnUiThread { a.finish() }
        } catch (e: Exception) {
            DiagLog.log("SELFTEST: error $e")
        }
    }

    /** Portrait: every display mode, with the automatic orientation (screen rotation 0). */
    private fun portraitModes() {
        a.geometry.forcedDisplayRotation = 0
        for (m in DisplayMode.entries) {
            kit.onUiAndWait {
                a.settings.mode = m
                a.geometry.updateRotation()
            }
            Thread.sleep(900)
            kit.snapshot(1080, 2160, "portrait_${m.name}.png")
        }
    }

    /**
     * The battery icon at several charge levels (the top bars of the snapshots stacked into one picture), then the video button
     * in its recording look.
     */
    private fun recordingButton() {
        kit.batterySheet()
        kit.onUiAndWait { a.recording.refreshUi(true) }
        Thread.sleep(200)
        kit.snapshot(1080, 2160, "portrait_recording_button.png")
        kit.onUiAndWait { a.recording.refreshUi() }
    }

    /** The settings screen: normal, after a tap on a palette's color strip, in full height, and with another palette. */
    private fun settingsScreen() {
        val s = a.settings
        kit.onUiAndWait {
            s.mode = DisplayMode.MSX
            a.geometry.testViewSize = Pair(1080, 2160)
            a.settingsPanel.show()
        }
        Thread.sleep(300)
        kit.snapshot(1080, 2160, "portrait_settings.png")

        // A tap on the palette's color strip (not on its radio button) must select that palette: real touch dispatch.
        var tapped = false
        kit.onUiAndWait {
            val strip = kit.findByTag(a.views.rootView, Palette.LAVA)
            if (strip != null) {
                kit.tap(strip)
                tapped = true
            }
        }
        if (tapped) {
            Thread.sleep(300)
            DiagLog.log("SELFTEST: tap on the Lava color strip -> palette ${s.palette}")
            kit.snapshot(1080, 2160, "portrait_settings_strip_tap.png")
            kit.onUiAndWait {
                s.palette = Palette.IRON
                a.onSettingsChanged()
            }
        } else {
            DiagLog.log("SELFTEST: the Lava color strip was not found")
        }
        kit.onUiAndWait {
            a.settingsPanel.hide()
            a.geometry.testViewSize = Pair(1080, 3400)
            kit.layoutRoot(1080, 3400)
            a.settingsPanel.show()
        }
        kit.snapshot(1080, 3400, "portrait_settings_full.png")
        kit.onUiAndWait { a.settingsPanel.hide() }
        kit.onUiAndWait {
            s.palette = Palette.LAVA
            a.onSettingsChanged()
        }
        Thread.sleep(900)
        kit.snapshot(1080, 2160, "portrait_settings_lava.png")
    }

    /** The calibration card and the alignment card. */
    private fun toolCards() {
        kit.onUiAndWait {
            a.settings.palette = Palette.IRON
            a.onSettingsChanged()
            a.settingsPanel.hide()
            a.cards.openCalibration()
        }
        Thread.sleep(500)
        kit.snapshot(1080, 2160, "portrait_calibration.png")
        kit.onUiAndWait { a.cards.openAlignment() }
        Thread.sleep(300)
        kit.snapshot(1080, 2160, "portrait_alignment.png")
        kit.onUiAndWait { a.cards.hide() }
    }

    /** Fill mode in portrait, then landscape: the screen is rotated 90 degrees (counter-clockwise), the picture must stay upright. */
    private fun fillAndLandscape() {
        val s = a.settings
        kit.onUiAndWait { s.fillScreen = true }
        Thread.sleep(500)
        kit.snapshot(1080, 2160, "portrait_fill.png")
        kit.onUiAndWait { s.fillScreen = false }

        a.geometry.forcedDisplayRotation = 90
        kit.onUiAndWait { a.geometry.updateRotation() }
        Thread.sleep(900)
        kit.snapshot(2160, 1080, "landscape_MSX.png")
        kit.onUiAndWait {
            a.geometry.testViewSize = Pair(2160, 1080)
            a.settingsPanel.show()
        }
        Thread.sleep(300)
        kit.snapshot(2160, 1080, "landscape_settings.png")
        kit.onUiAndWait {
            a.settingsPanel.hide()
            s.fillScreen = true
        }
        Thread.sleep(600)
        kit.snapshot(2160, 1080, "landscape_fill.png")
        kit.onUiAndWait { s.fillScreen = false }
    }

    /** Puts the app settings and the test hooks back (the language run does not touch the calibration points). */
    private fun restoreDefaults() {
        a.geometry.forcedDisplayRotation = null
        a.geometry.testViewSize = null
        kit.onUiAndWait {
            val s = a.settings
            s.mode = DisplayMode.MSX
            s.palette = Palette.IRON
            s.fillScreen = false
            s.crossU = 0.5f
            s.crossV = 0.5f
            a.onSettingsChanged()
            s.save()
        }
    }
}
