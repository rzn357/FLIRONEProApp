package com.exciteddev.flironepro.ui

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** A one-off notice for the user that comes from the stream and not from a tap on the screen. */
sealed interface Notice {
    /** A photo was saved ([name] is null if saving failed). */
    data class PhotoSaved(val name: String?) : Notice

    /** A video was finished ([name] is null if saving failed). */
    data class VideoSaved(val name: String?) : Notice
}

/**
 * What the stream has to tell the screen. The stream outlives a screen (see [StreamViewModel]), so it cannot call the screen
 * directly: the screen collects what the stream says. The message for the empty picture is a state, so a new screen starts with
 * the current one; notices that arrive while nobody collects wait in a queue until the next screen comes, and each is delivered
 * once. Both can be written from any thread.
 */
class StreamStatus {
    private val currentMessage = MutableStateFlow<Int?>(null)
    private val queue = Channel<Notice>(Channel.UNLIMITED)

    /** The last message for the empty picture (a string resource), or null if there was none yet. */
    val message: StateFlow<Int?> get() = currentMessage

    /** The notices, each once; only one screen is meant to collect them at a time. */
    val notices: Flow<Notice> = queue.receiveAsFlow()

    fun say(resId: Int) {
        currentMessage.value = resId
    }

    fun notify(notice: Notice) {
        queue.trySend(notice)
    }
}
