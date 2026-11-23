package com.exciteddev.flironepro.media

import android.content.Context
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.view.Surface
import com.exciteddev.flironepro.debug.DiagLog
import java.io.File
import java.io.RandomAccessFile

/**
 * H.264 (MP4) video recording: frames are drawn on the Canvas of the codec's input surface, so the file gets exactly what is shown
 * on the screen (for the selected mode), with the temperature marks. If debugFile is set, the video is written to that file instead of the gallery.
 */
class VideoRecorder(private val context: Context, val width: Int, val height: Int, private val debugFile: File? = null) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var surface: Surface? = null
    private var target: MediaSaver.VideoTarget? = null
    private var raf: RandomAccessFile? = null
    private var drainThread: Thread? = null

    @Volatile private var running = false
    private var muxerStarted = false
    private var firstPtsUs = -1L
    private var startedAt = 0L

    @Volatile var framesEncoded = 0
        private set

    @Volatile var fileName: String? = null
        private set

    val isRecording: Boolean get() = running
    fun elapsedMs(): Long = if (running) SystemClock.elapsedRealtime() - startedAt else 0L

    @Suppress("TooGenericExceptionCaught") // MediaCodec / MediaMuxer fail with many unchecked exceptions; a failed start must not crash
    fun start(): Boolean {
        return try {
            val fd = if (debugFile != null) {
                debugFile.parentFile?.mkdirs()
                debugFile.delete()
                fileName = debugFile.name
                RandomAccessFile(debugFile, "rw").also { raf = it }.fd
            } else {
                val t = MediaSaver.createVideo(context) ?: return false
                target = t
                fileName = t.name
                t.pfd.fileDescriptor
            }
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val c = MediaCodec.createEncoderByType(MIME)
            codec = c
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = c.createInputSurface()
            c.start()
            muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            running = true
            startedAt = SystemClock.elapsedRealtime()
            drainThread = Thread({ drain(c) }, "flir-encoder").also { it.start() }
            DiagLog.log("Video recording started: ${width}x$height -> $fileName")
            true
        } catch (e: Exception) {
            DiagLog.log("Could not start video recording: $e")
            release(keep = false)
            false
        }
    }

    /** Draws one frame into the video. Call from the processing thread. */
    @Synchronized
    @Suppress("TooGenericExceptionCaught") // a drawing or surface error in one frame must not stop the recording thread
    fun encode(draw: (Canvas) -> Unit) {
        if (!running) return
        val s = surface ?: return
        try {
            val canvas = s.lockCanvas(null)
            try {
                draw(canvas)
            } finally {
                s.unlockCanvasAndPost(canvas)
            }
            framesEncoded++
        } catch (e: Exception) {
            DiagLog.log("Video frame encoding error: $e")
        }
    }

    /** Finishes the recording. Returns the file name if the video was saved. */
    fun stop(): String? {
        synchronized(this) {
            if (!running) return null
            running = false
            runCatching { codec?.signalEndOfInputStream() }
        }
        drainThread?.join(DRAIN_JOIN_MS)
        val ok = muxerStarted && framesEncoded > 0
        val name = fileName
        release(keep = ok)
        DiagLog.log("Video recording finished: frames=$framesEncoded, saved=$ok")
        return if (ok) name else null
    }

    /** Moves the encoded data from the codec to the muxer until the end of the stream. */
    @Suppress("TooGenericExceptionCaught") // the codecs of different phones fail with a variety of unchecked exceptions
    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            var finished = false
            while (!finished) finished = drainOnce(c, info)
        } catch (e: Exception) {
            DiagLog.log("Video recording error (codec): $e")
        }
    }

    /** Handles one output event of the codec; true when the end of the stream was reached (or there is no muxer any more). */
    private fun drainOnce(c: MediaCodec, info: MediaCodec.BufferInfo): Boolean {
        val idx = c.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
        return when {
            idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> !startMuxer(c)
            idx >= 0 -> writeOutput(c, idx, info)
            else -> false
        }
    }

    private fun startMuxer(c: MediaCodec): Boolean {
        val m = muxer ?: return false
        m.addTrack(c.outputFormat)
        m.start()
        muxerStarted = true
        return true
    }

    /** Passes one encoded buffer to the muxer; true if it was the last one. */
    private fun writeOutput(c: MediaCodec, idx: Int, info: MediaCodec.BufferInfo): Boolean {
        val buf = c.getOutputBuffer(idx)
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
        if (info.size > 0 && muxerStarted && buf != null) {
            if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
            info.presentationTimeUs -= firstPtsUs
            buf.position(info.offset)
            buf.limit(info.offset + info.size)
            muxer?.writeSampleData(0, buf, info)
        }
        c.releaseOutputBuffer(idx, false)
        return info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
    }

    private fun release(keep: Boolean) {
        var finished = keep
        runCatching { if (muxerStarted) muxer?.stop() }.onFailure { finished = false }
        runCatching { muxer?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { surface?.release() }
        muxer = null
        codec = null
        surface = null
        runCatching { raf?.close() }
        raf = null
        target?.let { MediaSaver.finishVideo(context, it, finished) }
        target = null
        if (!finished) debugFile?.delete()
    }

    private companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val BIT_RATE = 3_000_000
        const val FRAME_RATE = 10
        const val DRAIN_JOIN_MS = 4000L
        const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
