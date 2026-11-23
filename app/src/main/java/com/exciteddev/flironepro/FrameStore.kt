package com.exciteddev.flironepro

import android.content.Context
import java.io.File

/**
 * Saves raw frames to filesDir/frames so they can be analyzed on a PC and replayed by the debug-only demo player.
 * The folder is private to the app and excluded from cloud backup.
 */
object FrameStore {
    private const val MAX = 200

    fun dir(context: Context): File = File(context.filesDir, "frames")

    /** Saves a frame; returns the file name or null if the limit is reached or writing failed. */
    fun save(context: Context, frame: FlirFrame, reason: String): String? {
        val dir = dir(context).apply { mkdirs() }
        if ((dir.list()?.size ?: 0) >= MAX) return null
        val name = "frame_${System.currentTimeMillis()}_$reason.bin"
        return runCatching {
            File(dir, name).writeBytes(frame.data)
            name
        }.getOrNull()
    }
}
