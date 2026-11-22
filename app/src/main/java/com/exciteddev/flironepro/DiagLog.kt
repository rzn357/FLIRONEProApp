package com.exciteddev.flironepro

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Лог у logcat і у файл filesDir/stream_log.txt (дописується, щоб його можна було забрати після перепідключення кабелю). */
object DiagLog {
    private const val TAG = "FlirDiag"
    private const val FILE = "stream_log.txt"
    private const val MAX_BYTES = 1_000_000L

    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, FILE)
        if (f.exists() && f.length() > MAX_BYTES) f.renameTo(File(f.parentFile, "$FILE.old"))
        file = f
    }

    @Synchronized
    fun log(msg: String) {
        Log.i(TAG, msg)
        runCatching { file?.appendText("${fmt.format(Date())}  $msg\n") }
    }
}
