package com.exciteddev.flironepro.debug

import android.content.Context

/** The frame statistics are a debug tool: the same API as in `src/debug`, and nothing is written in a release build. */
@Suppress("UNUSED_PARAMETER")
class FrameStatsLog(context: Context) {
    fun log(line: String) = Unit

    fun close() = Unit
}
