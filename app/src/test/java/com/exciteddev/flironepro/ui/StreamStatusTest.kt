package com.exciteddev.flironepro.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamStatusTest {
    @Test
    fun noticesWaitForTheNextScreen() = runTest {
        val status = StreamStatus()
        status.notify(Notice.PhotoSaved("a.jpg"))
        status.notify(Notice.VideoSaved(null))
        assertEquals(listOf(Notice.PhotoSaved("a.jpg"), Notice.VideoSaved(null)), status.notices.take(2).toList())
        assertNull(withTimeoutOrNull(1) { status.notices.first() }) // delivered once, not again
    }

    @Test
    fun collectingScreenGetsNoticesAndMessagesDirectly() = runTest {
        val status = StreamStatus()
        val messages = ArrayList<Int?>()
        val notices = ArrayList<Notice>()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val readMessages = launch(dispatcher) { status.message.collect { messages.add(it) } }
        val readNotices = launch(dispatcher) { status.notices.collect { notices.add(it) } }
        status.say(7)
        status.notify(Notice.PhotoSaved("b.jpg"))
        assertEquals(listOf(null, 7), messages)
        assertEquals(listOf<Notice>(Notice.PhotoSaved("b.jpg")), notices)
        readMessages.cancel()
        readNotices.cancel()
    }

    @Test
    fun newScreenSeesTheLastMessage() = runTest {
        val status = StreamStatus()
        assertNull(status.message.value)
        status.say(1)
        status.say(2)
        assertEquals(2, status.message.value)
        assertEquals(2, status.message.first())
    }

    @Test
    fun noticeMissedByAGoneScreenIsDeliveredToTheNextOne() = runTest {
        val status = StreamStatus()
        val old = ArrayList<Notice>()
        val readOld = launch(UnconfinedTestDispatcher(testScheduler)) { status.notices.collect { old.add(it) } }
        status.notify(Notice.VideoSaved("c.mp4"))
        readOld.cancel() // the screen is destroyed
        status.notify(Notice.VideoSaved("d.mp4"))
        assertEquals(listOf<Notice>(Notice.VideoSaved("c.mp4")), old)
        assertEquals(Notice.VideoSaved("d.mp4"), status.notices.first())
    }
}
