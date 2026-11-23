package com.exciteddev.flironepro

import android.content.Context
import android.os.SystemClock
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * CSV with per-frame statistics (raw values, temperatures from telemetry and status) - material for calibrating
 * raw values to degrees. File: filesDir/frame_stats_v2.csv, appended between launches.
 */
class FrameStatsLog(context: Context) {
    private val file = File(context.applicationContext.filesDir, "frame_stats_v2.csv")
    private var writer: BufferedWriter? = null
    private var lastFlush = 0L

    @Synchronized
    fun log(line: String) {
        val w = writer ?: open() ?: return
        runCatching {
            w.write(line)
            w.newLine()
            val now = SystemClock.elapsedRealtime()
            if (now - lastFlush > 2000) {
                w.flush()
                lastFlush = now
            }
        }
    }

    @Synchronized
    fun close() {
        runCatching { writer?.flush(); writer?.close() }
        writer = null
    }

    private fun open(): BufferedWriter? = runCatching {
        if (file.exists() && file.length() > MAX_BYTES) file.renameTo(File(file.parentFile, "frame_stats_v2.csv.old"))
        val isNew = !file.exists() || file.length() == 0L
        BufferedWriter(FileWriter(file, true)).also {
            if (isNew) {
                it.write(HEADER)
                it.newLine()
            }
            writer = it
        }
    }.getOrNull()

    companion object {
        private const val MAX_BYTES = 8_000_000L
        const val HEADER = "wall,frame,ffc,ffcState,shutterK,rawMin,rawMax,rawMean,rawCenter," +
            "tw20,tw21,tw22,tw23,tw24,tw25,tw26,tw27,tw28,tw29,tw30,tw31,tCenterC,tMinC,tMaxC,anchorRaw"
    }
}
