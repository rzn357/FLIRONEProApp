package com.exciteddev.flironepro.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** A release build has no developer tools: none of them is compiled in (they are in `src/debug`). */
@Suppress("UNUSED_PARAMETER")
internal fun createDevTools(activity: AppCompatActivity, model: StreamViewModel): DevTools = NoDevTools

private object NoDevTools : DevTools {
    override fun planRun(savedInstanceState: Bundle?, intent: Intent) = Unit

    override fun screenReady(screen: StreamScreen, intent: Intent) = Unit

    override fun startDemo() = Unit

    override fun newIntent(intent: Intent) = Unit
}
