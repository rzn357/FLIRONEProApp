package com.exciteddev.flironepro.ui

import android.content.Intent
import android.os.Bundle

/**
 * The developer tools of the live screen: the demo player, the self-test and the sender of raw camera commands, all started
 * from adb. They exist only in debug builds. The screen gets its tools from [createDevTools], which is not in this source set:
 * `src/debug` has the real one, `src/release` one that does nothing. A release build therefore contains none of their code.
 */
internal interface DevTools {
    /** Decides from the launch [intent] how this run uses the screen and sets the [StreamViewModel.runMode] to match. */
    fun planRun(savedInstanceState: Bundle?, intent: Intent)

    /** The screen is built: starts what the run plan asked for (the self-test) and handles the commands in [intent]. */
    fun screenReady(screen: StreamScreen, intent: Intent)

    /** Starts the demo; called when the run plan asked for one. */
    fun startDemo()

    /** The screen was started again with a new [intent]. */
    fun newIntent(intent: Intent)
}
