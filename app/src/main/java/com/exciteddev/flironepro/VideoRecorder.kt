package com.exciteddev.flironepro

import android.content.Context
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import android.view.Surface
import java.io.File
import java.io.RandomAccessFile

/**
 * Запис відео H.264 (MP4): кадри малюються на Canvas вхідної поверхні кодека, тож у файл потрапляє саме те, що видно
 * на екрані (за вибраним режимом), з мітками температури. Якщо задано debugFile, пишемо у цей файл замість галереї.
 */
class VideoRecorder(
    private val context: Context,
    val width: Int,
    val height: Int,
    private val debugFile: File? = null,
) {
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
                setInteger(MediaFormat.KEY_BIT_RATE, 3_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 10)
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
            DiagLog.log("Запис відео розпочато: ${width}x$height -> $fileName")
            true
        } catch (e: Exception) {
            DiagLog.log("Не вдалося почати запис відео: $e")
            release(keep = false)
            false
        }
    }

    /** Малює один кадр у відео. Викликати з потоку обробки. */
    @Synchronized
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
            DiagLog.log("Помилка кодування кадру відео: $e")
        }
    }

    /** Завершує запис. Повертає ім'я файлу, якщо відео збережено. */
    fun stop(): String? {
        synchronized(this) {
            if (!running) return null
            running = false
            runCatching { codec?.signalEndOfInputStream() }
        }
        drainThread?.join(4000)
        val ok = muxerStarted && framesEncoded > 0
        val name = fileName
        release(keep = ok)
        DiagLog.log("Запис відео завершено: кадрів=$framesEncoded, збережено=$ok")
        return if (ok) name else null
    }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val idx = c.dequeueOutputBuffer(info, 10_000)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val m = muxer ?: return
                        m.addTrack(c.outputFormat)
                        m.start()
                        muxerStarted = true
                    }

                    idx >= 0 -> {
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
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        } catch (e: Exception) {
            DiagLog.log("Помилка запису відео (кодек): $e")
        }
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
    }
}
