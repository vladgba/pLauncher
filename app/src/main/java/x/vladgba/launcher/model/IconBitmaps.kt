package x.vladgba.launcher.model

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.io.File
import kotlin.math.roundToInt

/**
 * Bounds decoding before allocation, including images imported from backups.
 *
 * RAM note: every icon held in memory is normalized once to [iconEdgePx]
 * (96 dp, capped at 384 px) by [render]. PackageManager sometimes returns
 * 512 to 1024 px bitmaps; without the cap, one of those sits in memory for
 * every installed app.
 */
object IconBitmaps {
    /**
     * Edge of a cached icon in px: 96 dp keeps enlarged icons (big grid span,
     * icon scale above 100 percent) sharp, the 384 px cap bounds memory.
     * [compression] (1, 2, 4) downscales the icon edge by 1x, 2x, or 4x,
     * drastically reducing RAM and cache footprint.
     */
    fun iconEdgePx(density: Float, compression: Int = 1): Int =
        when (compression) {
            4 -> (24 * density).roundToInt().coerceIn(24, 96)
            2 -> (48 * density).roundToInt().coerceIn(48, 192)
            else -> (96 * density).roundToInt().coerceIn(96, 384)
        }

    fun iconEdgePx(density: Float, aggressive: Boolean): Int =
        iconEdgePx(density, if (aggressive) 2 else 1)

    /** Decodes downsampled so the longest edge stays under [maxEdge]. */
    fun decodeFile(file: File, maxEdge: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                inSampleSize = iconSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
            })
        }
    } catch (_: Exception) { null }

    /**
     * Quantizes an ARGB pixel to 4-bit per channel (16 levels each of A, R, G, B).
     */
    fun quantize4Bit(pixel: Int): Int {
        val a = ((pixel ushr 28) and 0x0F) * 0x11
        val r = ((pixel ushr 20) and 0x0F) * 0x11
        val g = ((pixel ushr 12) and 0x0F) * 0x11
        val b = ((pixel ushr 4) and 0x0F) * 0x11
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Flattens any icon onto one shared edge-sized bitmap: the single copy every
     * view binds, and the same pixels that go to the disk cache. A bitmap drawable
     * already close to the target size is kept as-is (no copy).
     * [colorDepth] can be "32" (ARGB_8888), "16" (RGB_565 for opaque icons), or "4" (4-bit quantized).
     */
    fun render(res: Resources, d: Drawable, edge: Int, colorDepth: String = "32"): Drawable {
        val bmp = (d as? BitmapDrawable)?.bitmap
        val use565 = colorDepth == "16" && d is android.graphics.drawable.AdaptiveIconDrawable
        if (bmp != null && !bmp.isRecycled && maxOf(bmp.width, bmp.height) in edge / 2..edge && colorDepth == "32") return d
        val config = if (use565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        val out = Bitmap.createBitmap(edge, edge, config)
        val src = d.constantState?.newDrawable(res)?.mutate() ?: d.mutate()
        src.setBounds(0, 0, edge, edge)
        src.draw(Canvas(out))
        if (colorDepth == "4" && out.config == Bitmap.Config.ARGB_8888) {
            val pixels = IntArray(edge * edge)
            out.getPixels(pixels, 0, edge, 0, 0, edge, edge)
            for (i in pixels.indices) {
                pixels[i] = quantize4Bit(pixels[i])
            }
            out.setPixels(pixels, 0, edge, 0, 0, edge, edge)
        }
        return BitmapDrawable(res, out)
    }
}
