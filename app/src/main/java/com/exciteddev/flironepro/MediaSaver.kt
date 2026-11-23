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

/** Saving photos and videos to the gallery (MediaStore; on Android 10+ no permissions are needed). */
object MediaSaver {
    private const val PHOTO_DIR = "Pictures/FLIR ONE Pro"
    private const val VIDEO_DIR = "Movies/FLIR ONE Pro"

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Saves a JPEG photo. Returns the file name, or null on failure. */
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
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) } ?: error("no output stream")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            name
        } catch (e: Exception) {
            DiagLog.log("Could not save the photo: $e")
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /** Destination for a video: an entry in MediaStore (the file stays "pending" until the video is finished). */
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
            resolver.openFileDescriptor(uri, "rw") // MediaMuxer needs read and write access
        } catch (e: Exception) {
            null
        }
        if (pfd == null) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        return VideoTarget(uri, pfd, name)
    }

    /** Finishes the recording: makes the file visible in the gallery (keep) or deletes it. */
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
