package com.exciteddev.flironepro.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** The developer tools of debug builds (this file is in `src/debug`; `src/release` has [createDevTools] too, with no tools). */
internal fun createDevTools(activity: AppCompatActivity, model: StreamViewModel): DevTools = DebugDevTools(activity, model)

private class DebugDevTools(private val activity: AppCompatActivity, private val model: StreamViewModel) : DevTools {
    private val demo = DemoPlayer(activity.applicationContext, model.pipeline, model.runMode, model.status)
    private val commands = DebugCommands(activity, model.pipeline)
    private var plan = RunPlan(demo = false, selfTest = false, runSelfTest = false, language = null)

    override fun planRun(savedInstanceState: Bundle?, intent: Intent) {
        plan = DebugRun.plan(activity, savedInstanceState, intent)
        val runMode = model.runMode
        // A demo of an earlier launch does not go on into a normal one.
        if (runMode.demo && !plan.demo) model.session.stop()
        runMode.demo = plan.demo
        runMode.selfTest = plan.selfTest
    }

    override fun screenReady(screen: StreamScreen, intent: Intent) {
        // Diagnostic self-test (started from adb): runs in demo mode, independent of window visibility.
        if (plan.runSelfTest) {
            model.runMode.demo = true
            demo.start()
            SelfTest(screen, plan.language).start()
        }
        commands.handle(intent)
    }

    override fun startDemo() = demo.start()

    override fun newIntent(intent: Intent) = commands.handle(intent)
}
