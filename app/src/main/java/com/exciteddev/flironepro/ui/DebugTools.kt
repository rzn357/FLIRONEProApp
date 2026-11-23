package com.exciteddev.flironepro.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.exciteddev.flironepro.debug.DiagLog
import com.exciteddev.flironepro.pipeline.StreamPipeline
import com.exciteddev.flironepro.util.isDebuggable
import com.exciteddev.flironepro.util.setAppLanguage

/**
 * How this launch of the screen is to run. The demo player and the self-test are developer tools of debuggable builds only;
 * they are started from adb.
 */
class RunPlan(val demo: Boolean, val selfTest: Boolean, val runSelfTest: Boolean, val language: String?)

/** Decides from the launch intent whether this is a normal run, a demo or a self-test. */
object DebugRun {
    const val EXTRA_DEMO = "demo"
    const val EXTRA_SELFTEST = "selftest"
    const val EXTRA_SELFTEST_LANG = "selftest_lang"

    private const val PREFS = "selftest"
    private const val PREF_PENDING = "pending"
    private const val PREF_PREVIOUS_LANGUAGE = "prevLang"

    fun plan(activity: AppCompatActivity, savedInstanceState: Bundle?, intent: Intent): RunPlan {
        val debuggable = activity.isDebuggable
        val demo = debuggable && intent.getBooleanExtra(EXTRA_DEMO, false)
        val selfTest = debuggable && intent.getBooleanExtra(EXTRA_SELFTEST, false)
        val language = intent.getStringExtra(EXTRA_SELFTEST_LANG)
        return if (selfTest) planSelfTest(activity, savedInstanceState, demo, language) else RunPlan(demo, false, false, language)
    }

    /**
     * Self-test only: optionally switches the app language first. A fresh launch from adb switches the language and the activity
     * is recreated; the recreated instance runs the test; any later recreation (e.g. when the language is restored) behaves as a
     * normal screen, so the test can never loop.
     */
    private fun planSelfTest(activity: AppCompatActivity, savedInstanceState: Bundle?, demo: Boolean, language: String?): RunPlan {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        return when {
            savedInstanceState == null && language != null && current != language -> {
                prefs.edit().putString(PREF_PREVIOUS_LANGUAGE, current).putBoolean(PREF_PENDING, true).apply()
                setAppLanguage(language)
                RunPlan(demo, selfTest = true, runSelfTest = false, language = language)
            }

            savedInstanceState == null -> RunPlan(demo, selfTest = true, runSelfTest = true, language = language)

            prefs.getBoolean(PREF_PENDING, false) -> {
                prefs.edit().putBoolean(PREF_PENDING, false).apply()
                RunPlan(demo, selfTest = true, runSelfTest = true, language = language)
            }

            else -> RunPlan(demo = false, selfTest = false, runSelfTest = false, language = language)
        }
    }
}

/**
 * Developer tool (debuggable builds only): sends raw JSON commands to the camera once frames are flowing, so that the
 * camera's reactions can be studied from adb. Commands are separated by "@@"; the gap between them is in milliseconds.
 */
class DebugCommands(private val activity: AppCompatActivity, private val pipeline: StreamPipeline) {
    fun handle(intent: Intent) {
        val raw = intent.getStringExtra(EXTRA_SEND_JSON)
        if (activity.isDebuggable && raw != null) {
            intent.removeExtra(EXTRA_SEND_JSON)
            val gap = intent.getIntExtra(EXTRA_SEND_GAP, DEFAULT_GAP_MS).toLong()
            val commands = raw.split("@@").map { it.trim() }.filter { it.isNotEmpty() }
            Thread({ sendWhenStreaming(commands, gap) }, "flir-debug-send").start()
        }
    }

    private fun sendWhenStreaming(commands: List<String>, gapMs: Long) {
        val end = SystemClock.elapsedRealtime() + WAIT_FOR_FRAMES_MS
        while (SystemClock.elapsedRealtime() < end && !isStreaming()) Thread.sleep(POLL_MS)
        val cam = pipeline.camera
        if (cam == null) {
            DiagLog.log("[debug] no camera to send to")
        } else {
            for (command in commands) {
                DiagLog.log("[debug] sent $command -> ${cam.sendJson(command)}")
                Thread.sleep(gapMs)
            }
        }
    }

    private fun isStreaming() = pipeline.camera?.isRunning == true && pipeline.frameCount > 0

    companion object {
        const val EXTRA_SEND_JSON = "send_json"
        const val EXTRA_SEND_GAP = "send_gap_ms"
        private const val DEFAULT_GAP_MS = 3000
        private const val WAIT_FOR_FRAMES_MS = 20_000L
        private const val POLL_MS = 200L
    }
}
