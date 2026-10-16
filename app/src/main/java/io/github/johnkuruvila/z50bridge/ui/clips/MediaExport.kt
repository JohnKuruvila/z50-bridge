package io.github.johnkuruvila.z50bridge.ui.clips

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Copies a staged clip into the shared gallery so it survives app uninstall.
 * Scoped storage on API 29+ needs no runtime permission for app-owned inserts.
 */
fun exportClipToGallery(context: Context, file: File): File? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
    val values = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Z50Bridge")
    }
    val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val uri = context.contentResolver.insert(collection, values) ?: return null
    context.contentResolver.openOutputStream(uri).use { output ->
        if (output == null) {
            context.contentResolver.delete(uri, null, null)
            return null
        }
        file.inputStream().use { input -> input.copyTo(output) }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
    }
    return file
}
