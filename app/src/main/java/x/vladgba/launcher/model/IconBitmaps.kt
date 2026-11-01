package x.vladgba.launcher.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Bounds decoding before allocation, including images imported from backups. */
object IconBitmaps {
    fun decodeFile(file: File, maxEdge: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                inSampleSize = iconSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            })
        }
    } catch (_: Exception) { null }
}
