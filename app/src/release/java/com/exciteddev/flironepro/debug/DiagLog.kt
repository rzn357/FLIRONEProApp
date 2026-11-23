package com.exciteddev.flironepro.debug

import android.content.Context

/**
 * The diagnostic log has no place in a release build: the same API as in `src/debug`, and nothing happens. (The release
 * build has no log file and no logcat output.)
 */
@Suppress("UNUSED_PARAMETER")
object DiagLog {
    fun init(context: Context) = Unit

    fun log(msg: String) = Unit
}

/** Nothing to throttle in a release build, see [DiagLog]. */
@Suppress("UNUSED_PARAMETER")
class ThrottledLog(prefix: String, gapMs: Long) {
    fun log(msg: String) = Unit
}
