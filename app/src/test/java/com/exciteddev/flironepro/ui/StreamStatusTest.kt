package com.exciteddev.flironepro.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamStatusTest {
    private class Recorder : StreamStatus.Screen {
        val messages = ArrayList<Int>()
        val notices = ArrayList<Notice>()

        override fun onMessage(resId: Int) {
            messages.add(resId)
        }

        override fun onNotice(notice: Notice) {
            notices.add(notice)
        }
    }

    @Test
    fun noticesWaitForTheNextScreen() {
        val status = StreamStatus()
        status.notify(Notice.PhotoSaved("a.jpg"))
        status.notify(Notice.VideoSaved(null))
        val screen = Recorder()
        status.attach(screen)
        assertEquals(listOf(Notice.PhotoSaved("a.jpg"), Notice.VideoSaved(null)), screen.notices)
        status.attach(Recorder())
        assertEquals(2, screen.notices.size) // delivered once, not again
    }

    @Test
    fun attachedScreenGetsNoticesAndMessagesDirectly() {
        val status = StreamStatus()
        val screen = Recorder()
        status.attach(screen)
        status.say(7)
        status.notify(Notice.PhotoSaved("b.jpg"))
        assertEquals(listOf(7), screen.messages)
        assertEquals(listOf<Notice>(Notice.PhotoSaved("b.jpg")), screen.notices)
    }

    @Test
    fun newScreenSeesTheLastMessage() {
        val status = StreamStatus()
        assertNull(status.message)
        status.say(1)
        status.say(2)
        val screen = Recorder()
        status.attach(screen)
        assertEquals(2, status.message)
        assertEquals(listOf(2), screen.messages)
    }

    @Test
    fun detachedScreenGetsNothingAndAnOldScreenCannotDetachANewOne() {
        val status = StreamStatus()
        val old = Recorder()
        val fresh = Recorder()
        status.attach(old)
        status.attach(fresh)
        status.detach(old) // late call of the destroyed screen: the new one stays attached
        status.notify(Notice.VideoSaved("c.mp4"))
        assertEquals(0, old.notices.size)
        assertEquals(1, fresh.notices.size)
        status.detach(fresh)
        status.notify(Notice.VideoSaved("d.mp4"))
        assertEquals(1, fresh.notices.size)
        val next = Recorder()
        status.attach(next)
        assertEquals(listOf<Notice>(Notice.VideoSaved("d.mp4")), next.notices)
    }
}
