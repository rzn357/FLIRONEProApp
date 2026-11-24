package com.exciteddev.flironepro.pipeline

import android.graphics.Bitmap
import com.exciteddev.flironepro.media.VideoRecorder
import com.exciteddev.flironepro.protocol.FlirFrame
import com.exciteddev.flironepro.protocol.TestFrames
import com.exciteddev.flironepro.radiometry.Radiometry
import com.exciteddev.flironepro.render.OverlayState
import com.exciteddev.flironepro.render.UiTexts
import com.exciteddev.flironepro.settings.AppSettings
import com.exciteddev.flironepro.testing.TestContext
import com.exciteddev.flironepro.testing.awaitThat
import com.exciteddev.flironepro.testing.realClock
import com.exciteddev.flironepro.usb.FLIR_LAYOUT
import com.exciteddev.flironepro.usb.FakeUsbLink
import com.exciteddev.flironepro.usb.FlirUsb
import com.exciteddev.flironepro.usb.UsbOpener
import com.exciteddev.flironepro.usb.serviceMessage
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A frame processor that only keeps what it was given. */
private class FakeProcessing(override var overlayTexts: UiTexts) : FrameProcessing {
    val samples = CopyOnWriteArrayList<FrameSample>()

    @Volatile var started = false

    @Volatile var shutDown = false

    @Volatile var photos = 0

    override var recorder: VideoRecorder? = null
    override var onPhotoSaved: ((String?) -> Unit)? = null
    override val calibration = CalibrationRing()

    override fun start() {
        started = true
    }

    override fun submit(s: FrameSample) {
        samples.add(s)
    }

    override fun requestPhoto() {
        photos++
    }

    override fun renderSnapshot(w: Int, h: Int): Bitmap? = null

    override fun shutdown() {
        shutDown = true
    }
}

/** A screen that counts how often its picture was dropped. */
private class FakePicture : PictureSink {
    var cleared = 0

    override fun submit(bm: Bitmap, o: OverlayState) = Unit

    override fun currentFrame(): Pair<Bitmap, OverlayState>? = null

    override fun clear() {
        cleared++
    }
}

private class FakeSource(private val onFrame: (FlirFrame) -> Unit) : ReplaySource {
    var started = false
    var shutDown = false

    override fun start() {
        started = true
    }

    override fun shutdown() {
        shutDown = true
    }

    fun deliver(frame: FlirFrame) = onFrame(frame)
}

/**
 * The data side of the live screen as a whole: the camera driver on a fake USB device, the frame bookkeeping, the temperature
 * model and the statistics log are real; only the render thread (and with it every Android graphics class) is replaced.
 */
class StreamPipelineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val processors = CopyOnWriteArrayList<FakeProcessing>()
    private val textsGiven = CopyOnWriteArrayList<UiTexts>()
    private val links = CopyOnWriteArrayList<FakeUsbLink>()
    private lateinit var pipeline: StreamPipeline
    private lateinit var filesDir: File

    private fun texts(tag: String) = UiTexts(Locale.US, "wait $tag", "ffc $tag", "demo $tag", "e %1\$s $tag")

    private fun newLink(layout: Map<Int, Set<Int>> = FLIR_LAYOUT) = FakeUsbLink(layout) { Thread.sleep(IDLE_MS) }.also { links.add(it) }

    private fun create(): StreamPipeline {
        filesDir = tmp.newFolder()
        val context = TestContext(filesDir)
        val factory = ProcessorFactory { _, _, texts ->
            textsGiven.add(texts)
            FakeProcessing(texts).also { processors.add(it) }
        }
        return StreamPipeline(context, AppSettings(context), TestScope(), factory, texts("first"), realClock).also { pipeline = it }
    }

    private fun frame(ffc: Boolean = false) = TestFrames.buildFrame(TestFrames.layoutAThermal(), ByteArray(10), TestFrames.status(ffc))

    /** The frame as the parser delivers it. */
    private fun parsed(bytes: ByteArray): FlirFrame = TestFrames.collect(bytes.size, bytes).first.single()

    private fun start(link: FakeUsbLink): Boolean = pipeline.startCamera(UsbOpener { link }, "fake camera")

    @After
    fun stopPipeline() {
        if (this::pipeline.isInitialized) pipeline.stop()
    }

    @Test
    fun framesFromTheCameraArriveDecodedAtTheProcessor() {
        val link = newLink()
        create()
        assertTrue(start(link))
        link.feedChunked(FlirUsb.EP_FRAMES, frame() + frame() + frame(), chunkSize = 3000)
        awaitThat("three samples") { processors.single().samples.size == 3 }

        assertEquals(3, pipeline.frameCount)
        val sample = processors.single().samples.first()
        assertTrue(sample.valid)
        val raw = sample.raw!!
        assertEquals(161, raw[161]) // layout A: pixel (x, y) holds y * 160 + x
        assertNull(sample.fpaK) // no telemetry in this frame
        assertSame(processors.single().samples.last().frame, pipeline.lastFrame)
        assertTrue(processors.single().started)
    }

    @Test
    fun shutterFrameAndTheTwoAfterItAreNotShown() {
        val link = newLink()
        create()
        start(link)
        link.feed(FlirUsb.EP_FRAMES, frame() + frame(ffc = true) + frame() + frame() + frame())
        awaitThat("five samples") { processors.single().samples.size == 5 }

        // The frame with the closed shutter, the one that shows it opening and the one on which the sensor catches up.
        assertEquals(listOf(true, false, false, false, true), processors.single().samples.map { it.valid })
    }

    @Test
    fun batteryComesFromTheServiceMessages() {
        val link = newLink()
        create()
        start(link)
        assertNull(pipeline.battery.percentage)
        link.feed(FlirUsb.EP_SERVICE_A, serviceMessage("""{"type":"batteryVoltageUpdate","data":{"percentage":73,"voltage":3.9}}"""))
        link.feed(FlirUsb.EP_SERVICE_A, serviceMessage("""{"type":"batteryChargingStateUpdate","data":{"chargingState":"Charging"}}"""))
        awaitThat("the battery state") { pipeline.battery.percentage == 73 && pipeline.battery.charging == true }
        assertEquals(3.9, pipeline.battery.voltage!!, 1e-9)
    }

    @Test
    fun lostConnectionIsReported() {
        val link = newLink()
        create()
        var lost = 0
        pipeline.onConnectionLost = { lost++ }
        start(link)
        link.breakReads(FlirUsb.EP_FRAMES)
        awaitThat("the report") { lost == 1 }
        assertFalse(pipeline.camera!!.isRunning)
    }

    @Test
    fun cameraThatCannotStartLeavesNothingRunning() {
        val link = newLink().apply { setInterfaceResult = { alt, id, _ -> if (alt == 1 && id == 2) -1 else 0 } }
        create()
        assertFalse(start(link))

        assertNull(pipeline.camera)
        assertNull(pipeline.processor)
        assertNull(pipeline.intake)
        assertEquals(0, pipeline.frameCount)
        assertTrue(processors.single().shutDown)
        assertTrue(link.closed)
    }

    @Test
    fun deviceThatCannotBeOpenedLeavesNothingRunning() {
        create()
        assertFalse(pipeline.startCamera(UsbOpener { null }, "no permission"))
        assertNull(pipeline.camera)
        assertNull(pipeline.processor)
    }

    @Test
    fun stopShutsEverythingDownAndShowsTheEmptyStateAgain() {
        val link = newLink()
        create()
        val screen = FakePicture()
        pipeline.attach(screen, texts("screen"))
        start(link)
        link.feed(FlirUsb.EP_FRAMES, frame())
        awaitThat("a sample") { processors.single().samples.isNotEmpty() }
        val clearedBefore = screen.cleared

        pipeline.stop()

        assertTrue(processors.single().shutDown)
        assertTrue(link.closed)
        assertNull(pipeline.camera)
        assertNull(pipeline.processor)
        assertNull(pipeline.intake)
        assertEquals(0, pipeline.frameCount)
        assertTrue(screen.cleared > clearedBefore)
    }

    @Test
    fun everyStartIsANewSession() {
        create()
        val first = newLink()
        start(first)
        first.feed(FlirUsb.EP_FRAMES, frame() + frame())
        first.feed(FlirUsb.EP_SERVICE_A, serviceMessage("""{"type":"batteryVoltageUpdate","data":{"percentage":40}}"""))
        awaitThat("two frames and the battery") { pipeline.frameCount == 2 && pipeline.battery.percentage == 40 }
        val firstRadiometry = pipeline.radiometry
        pipeline.stop()

        val second = newLink()
        assertTrue(start(second))

        assertEquals(2, processors.size)
        assertNotSame(processors[0], processors[1])
        assertNotSame(firstRadiometry, pipeline.radiometry)
        assertEquals(0, pipeline.frameCount)
        assertNull(pipeline.battery.percentage)
        second.feed(FlirUsb.EP_FRAMES, frame())
        awaitThat("a frame of the new session") { processors[1].samples.size == 1 }
        assertEquals(2, processors[0].samples.size)
    }

    @Test
    fun theLanguageOfTheScreenReachesTheRunningAndTheNextProcessor() {
        val link = newLink()
        create()
        start(link)
        assertEquals("wait first", processors.single().overlayTexts.waitingForCalibration)

        pipeline.attach(FakePicture(), texts("screen"))
        assertEquals("wait screen", processors.single().overlayTexts.waitingForCalibration)

        pipeline.stop()
        start(newLink())
        assertEquals("wait screen", textsGiven.last().waitingForCalibration)
    }

    @Test
    fun liveSessionLogsOneStatisticsRowPerFrame() {
        val link = newLink()
        create()
        start(link)
        link.feed(FlirUsb.EP_FRAMES, frame() + frame() + frame())
        awaitThat("three frames") { pipeline.frameCount == 3 }
        pipeline.stop() // closes (and so flushes) the log

        val lines = File(filesDir, "frame_stats_v2.csv").readLines()
        assertEquals(1 + 3, lines.size)
        assertTrue(lines[0].startsWith("wall,frame,ffc"))
        assertEquals(listOf("1", "2", "3"), lines.drop(1).map { it.split(",")[1] })
    }

    @Test
    fun replayedFramesGoTheSamePathButDoNotFeedTheTemperatureModel() {
        create()
        val anchor = Radiometry.Anchor(raw = 7000.0, shutterK = 300.0, fpaK = 301.0)
        var source: FakeSource? = null
        val started = pipeline.startReplay(fixedFpaK = 305.0, anchor = anchor) { onFrame -> FakeSource(onFrame).also { source = it } }

        assertSame(source, started)
        assertTrue(source!!.started)
        assertSame(anchor, pipeline.radiometry.anchor)
        source!!.deliver(parsed(frame()))
        awaitThat("the replayed frame") { processors.single().samples.size == 1 }
        assertEquals(305.0, processors.single().samples.single().fpaK!!, 1e-9)
        assertSame(anchor, pipeline.radiometry.anchor)
        assertNull(pipeline.camera)
    }

    @Test
    fun aSecondReplayCannotStartWhileOneRunsAndStopStopsIt() {
        create()
        val anchor = Radiometry.Anchor(7000.0, 300.0, 301.0)
        var source: FakeSource? = null
        pipeline.startReplay(305.0, anchor) { FakeSource(it).also { s -> source = s } }
        assertNull(pipeline.startReplay(305.0, anchor) { FakeSource(it) })
        assertEquals(1, processors.size)

        pipeline.stop()

        assertTrue(source!!.shutDown)
        assertNotNull(pipeline.startReplay(305.0, anchor) { FakeSource(it) })
    }

    private companion object {
        const val IDLE_MS = 30L
    }
}
