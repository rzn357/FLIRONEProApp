package com.exciteddev.flironepro

import java.io.File

/**
 * Демо-джерело кадрів: по колу «програє» збережені раніше сирі кадри (файли .bin у теці frames), так що весь інтерфейс
 * можна перевіряти без камери. Температури в демо неточні: кадри з різних сеансів мають різні опорні рівні.
 */
class DemoSource(
    private val dir: File,
    private val frameMs: Long = FRAME_MS,
    private val onFrame: (FlirFrame) -> Unit,
) : Thread("flir-demo") {
    @Volatile private var running = true
    var frameCount = 0
        private set

    fun shutdown() {
        running = false
        interrupt()
        runCatching { join(1000) }
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
                if (raw != null && !frame.isFfc && ThermalImage.stats(raw).let { it.max - it.min > 150 }) out.add(frame)
            })
            parser.feed(bytes, bytes.size)
        }
        return out
    }

    companion object {
        const val FRAME_MS = 200L
    }
}
