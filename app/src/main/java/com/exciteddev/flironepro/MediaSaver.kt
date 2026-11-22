package com.exciteddev.flironepro

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Збереження знімків і відео в галерею (MediaStore, Android 10+: дозволи не потрібні). */
object MediaSaver {
    private const val PHOTO_DIR = "Pictures/FLIR ONE Pro"
    private const val VIDEO_DIR = "Movies/FLIR ONE Pro"

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Зберігає знімок JPEG. Повертає ім'я файлу або null при помилці. */
    fun savePhoto(context: Context, bitmap: Bitmap): String? {
        val name = "FLIR_${stamp()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, PHOTO_DIR)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) } ?: error("немає потоку")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            name
        } catch (e: Exception) {
            DiagLog.log("Не вдалося зберегти фото: $e")
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /** Місце для відео: запис у MediaStore (файл ще «в обробці», поки відео не завершене). */
    class VideoTarget(val uri: Uri, val pfd: ParcelFileDescriptor, val name: String)

    fun createVideo(context: Context): VideoTarget? {
        val name = "FLIR_${stamp()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, VIDEO_DIR)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val pfd = try {
            resolver.openFileDescriptor(uri, "rw") // MediaMuxer вимагає читання й запису
        } catch (e: Exception) {
            null
        }
        if (pfd == null) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        return VideoTarget(uri, pfd, name)
    }

    /** Завершує запис: робить файл видимим у галереї (keep) або видаляє його. */
    fun finishVideo(context: Context, target: VideoTarget, keep: Boolean) {
        runCatching { target.pfd.close() }
        val resolver = context.contentResolver
        if (keep) {
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            runCatching { resolver.update(target.uri, values, null, null) }
        } else {
            runCatching { resolver.delete(target.uri, null, null) }
        }
    }
}
