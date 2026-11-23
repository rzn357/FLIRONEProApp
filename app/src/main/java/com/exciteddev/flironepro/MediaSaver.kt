package com.exciteddev.flironepro

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saving photos and videos to the gallery (MediaStore; on Android 10+ no permissions are needed). */
object MediaSaver {
    private const val PHOTO_DIR = "Pictures/FLIR ONE Pro"
    private const val VIDEO_DIR = "Movies/FLIR ONE Pro"
    private const val JPEG_QUALITY = 95

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** Runs a MediaStore call; the failures that this storage can cause are passed to [onFailure] instead of crashing the app. */
    private inline fun <T> mediaStoreCall(onFailure: (Exception) -> T, block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        onFailure(e)
    } catch (e: SecurityException) {
        onFailure(e)
    } catch (e: IllegalArgumentException) {
        onFailure(e)
    }

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
        return mediaStoreCall(onFailure = { discard(resolver, uri, "photo", it) }) {
            val out = resolver.openOutputStream(uri) ?: throw IOException("no output stream")
            out.use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            name
        }
    }

    /** Logs the failure and removes the half-written entry; always null. */
    private fun discard(resolver: ContentResolver, uri: Uri, what: String, error: Exception): String? {
        DiagLog.log("Could not save the $what: $error")
        runCatching { resolver.delete(uri, null, null) }
        return null
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
        // MediaMuxer needs read and write access.
        val pfd = mediaStoreCall(onFailure = { openFailed(it) }) {
            resolver.openFileDescriptor(uri, "rw")
        }
        if (pfd == null) runCatching { resolver.delete(uri, null, null) }
        return pfd?.let { VideoTarget(uri, it, name) }
    }

    private fun openFailed(error: Exception): ParcelFileDescriptor? {
        DiagLog.log("Could not open the video file: $error")
        return null
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
