package com.exciteddev.flironepro.debug

import com.exciteddev.flironepro.pipeline.ReplaySource
import com.exciteddev.flironepro.protocol.FlirFrame
import com.exciteddev.flironepro.protocol.FlirFrameParser
import com.exciteddev.flironepro.radiometry.ThermalImage
import java.io.File

/**
 * Demo frame source: replays the raw frames saved earlier (.bin files in the frames folder) in a loop, so the whole UI
 * can be tested without a camera. Demo temperatures are not accurate: frames from different sessions have different reference levels.
 */
class DemoSource(private val dir: File, private val frameMs: Long = FRAME_MS, private val onFrame: (FlirFrame) -> Unit) :
    Thread("flir-demo"),
    ReplaySource {
    @Volatile private var running = true
    var frameCount = 0
        private set

    override fun shutdown() {
        running = false
        interrupt()
        runCatching { join(JOIN_MS) }
    }

    override fun run() {
        val frames = load()
        frameCount = frames.size
        if (frames.isEmpty()) return
        var i = 0
        while (running) {
            onFrame(frames[i % frames.size])
            i++
            try {
                sleep(frameMs)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun load(): List<FlirFrame> {
        val out = ArrayList<FlirFrame>()
        val files = dir.listFiles { f -> f.name.endsWith(".bin") }?.sortedBy { it.name } ?: return out
        for (f in files) {
            val bytes = runCatching { f.readBytes() }.getOrNull() ?: continue
            val parser = FlirFrameParser({ frame ->
                val raw = ThermalImage.decode(frame)
                if (raw != null && !frame.isFfc && ThermalImage.stats(raw).let { it.max - it.min > MIN_SPREAD }) out.add(frame)
            })
            parser.feed(bytes, bytes.size)
        }
        return out
    }

    companion object {
        const val FRAME_MS = 200L
        private const val JOIN_MS = 1000L

        /** Frames whose raw values vary less than this are flat (shutter, lens cap) and are not worth replaying. */
        private const val MIN_SPREAD = 150
    }
}
