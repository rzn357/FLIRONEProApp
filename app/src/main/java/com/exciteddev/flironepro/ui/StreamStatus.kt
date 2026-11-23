package com.exciteddev.flironepro.ui

/** A one-off notice for the user that comes from the stream and not from a tap on the screen. */
sealed interface Notice {
    /** A photo was saved ([name] is null if saving failed). */
    data class PhotoSaved(val name: String?) : Notice

    /** A video was finished ([name] is null if saving failed). */
    data class VideoSaved(val name: String?) : Notice
}

/**
 * What the stream has to tell the screen. The stream outlives a screen (see [StreamViewModel]), so it cannot call the screen
 * directly: the message for the empty picture is kept as a state that a new screen reads when it attaches, and notices that
 * arrive while no screen is attached wait until the next one comes.
 */
class StreamStatus {
    /** Gets what the stream says; the calls can come from any thread. */
    interface Screen {
        fun onMessage(resId: Int)

        fun onNotice(notice: Notice)
    }

    private val lock = Any()
    private val waiting = ArrayDeque<Notice>()
    private var screen: Screen? = null

    /** The last message for the empty picture (a string resource), or null if there was none yet. */
    @Volatile var message: Int? = null
        private set

    fun say(resId: Int) {
        message = resId
        synchronized(lock) { screen }?.onMessage(resId)
    }

    fun notify(notice: Notice) {
        val target = synchronized(lock) {
            screen.also { if (it == null) waiting.addLast(notice) }
        }
        target?.onNotice(notice)
    }

    /** Makes [target] the screen: it gets the current message and everything that has been waiting. */
    fun attach(target: Screen) {
        val missed = synchronized(lock) {
            screen = target
            waiting.toList().also { waiting.clear() }
        }
        message?.let { target.onMessage(it) }
        missed.forEach { target.onNotice(it) }
    }

    /** Lets go of [target]; does nothing if another screen has taken over in the meantime. */
    fun detach(target: Screen) {
        synchronized(lock) {
            if (screen === target) screen = null
        }
    }
}
