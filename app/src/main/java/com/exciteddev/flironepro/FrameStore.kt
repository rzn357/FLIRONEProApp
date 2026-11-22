package com.exciteddev.flironepro

import android.content.Context
import java.io.File

/** Зберігає сирі кадри у filesDir/frames, щоб їх можна було розібрати на ПК. Не більше MAX файлів. */
object FrameStore {
    private const val MAX = 200

    fun save(context: Context, frame: FlirFrame, reason: String): String? {
        val dir = File(context.filesDir, "frames").apply { mkdirs() }
        if ((dir.list()?.size ?: 0) >= MAX) return null
        val name = "frame_${System.currentTimeMillis()}_$reason.bin"
        return runCatching {
            File(dir, name).writeBytes(frame.data)
            name
        }.getOrNull()
    }
}
