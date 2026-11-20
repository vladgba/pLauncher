package x.vladgba.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import java.util.WeakHashMap
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sign

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = dp(v).roundToInt()

/** Icon + label, drawn directly (no nested views). Also renders folders as a 2×2 preview. */
class AppIconView(context: Context) : View(context) {

    var icon: Drawable? = null
        set(value) { field = value; invalidate() }
    var label: String = ""
        set(value) { field = value; contentDescription = value; invalidate() }
    /** Non-null → draw as folder with these previews. */
    var folderIcons: List<Drawable>? = null
        set(value) { field = value; invalidate() }
    var showLabel = true
        set(value) { field = value; invalidate() }
    var labelSizeSp = 12f
        set(value) { field = value; textPaint.textSize = value * resources.displayMetrics.scaledDensity; invalidate() }
    var labelPosition = "below"
        set(value) { field = value; applyShadow(); invalidate() }
    var iconScale = 1f
        set(value) { field = value; invalidate() }
    /** "system" keeps the app's icon; other values clip it to the selected mask. */
    var shape = "system"
        set(value) { field = value; invalidate() }
    var labelColor: Int
        get() = textPaint.color
        set(value) { textPaint.color = value; invalidate() }
    var labelShadow = true
        set(value) {
            field = value
            applyShadow()
            invalidate()
        }
    var labelShadowStrength = 4
        set(value) { field = value.coerceIn(1, 5); applyShadow(); invalidate() }
    var shadowColor = 0xB0000000.toInt()
        set(value) { field = value; applyShadow(); invalidate() }

    private fun applyShadow() {
        if (labelShadow) textPaint.setShadowLayer(
            context.dp(if (labelPosition == "overlay") 2f + labelShadowStrength else 1f + labelShadowStrength),
            0f, context.dp(labelShadowStrength * 0.35f),
            if (labelPosition == "overlay") Color.BLACK else shadowColor)
        else textPaint.clearShadowLayer()
    }

    /** Home-screen look for the current day/night mode. */
    fun applyHomePalette() {
        textPaint.color = Palette.label
        folderPaint.color = Palette.folderBg
        shadowColor = Palette.labelShadow
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = context.dp(12f)
        textAlign = Paint.Align.CENTER
    }
    private val folderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
    private val folderRect = RectF()
    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipRect = RectF()
    private val clipPath = android.graphics.Path()

    init {
        isClickable = true
        isFocusable = true
        labelShadow = true
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        animate().scaleX(if (pressed) 0.9f else 1f).scaleY(if (pressed) 0.9f else 1f)
            .setDuration(100).start()
    }

    override fun onDraw(canvas: Canvas) {
        val labelH = if (showLabel && labelPosition == "below") textPaint.textSize * 1.5f else 0f
        val avail = min(width * 0.78f, (height - labelH) * 0.82f)
        val size = min(avail * iconScale, min(width.toFloat(), height - labelH))
        if (size <= 0) return
        val left = (width - size) / 2f
        val top = (height - labelH - size) / 2f

        val previews = folderIcons
        if (previews != null) {
            folderRect.set(left, top, left + size, top + size)
            val folderSave = canvas.save()
            if (shape == "system") {
                canvas.drawRoundRect(folderRect, size * 0.28f, size * 0.28f, folderPaint)
            } else {
                setShapePath(left, top, size)
                canvas.drawPath(clipPath, folderPaint)
                canvas.clipPath(clipPath)
            }
            val pad = size * 0.1f
            val cell = (size - pad * 3) / 2f
            previews.take(4).forEachIndexed { i, d ->
                val l = left + pad + (i % 2) * (cell + pad)
                val t = top + pad + (i / 2) * (cell + pad)
                drawIcon(canvas, d, l, t, cell)
            }
            canvas.restoreToCount(folderSave)
        } else {
            icon?.let { drawIcon(canvas, it, left, top, size) }
        }

        if (showLabel && label.isNotEmpty()) {
            val text = TextUtils.ellipsize(label, textPaint, width - context.dp(4f), TextUtils.TruncateAt.END)
            val oldColor = textPaint.color
            if (labelPosition == "overlay") textPaint.color = Color.WHITE
            val baseline = if (labelPosition == "overlay") top + size - context.dp(5f)
                else top + size + textPaint.textSize * 1.25f
            if (labelShadow && labelPosition == "overlay") {
                textPaint.clearShadowLayer()
                textPaint.style = Paint.Style.STROKE
                textPaint.strokeJoin = Paint.Join.ROUND
                textPaint.strokeWidth = context.dp(0.8f + labelShadowStrength * 0.45f)
                textPaint.color = Color.BLACK
                canvas.drawText(text, 0, text.length, width / 2f, baseline, textPaint)
                textPaint.style = Paint.Style.FILL
                textPaint.color = Color.WHITE
                applyShadow()
            }
            canvas.drawText(text, 0, text.length, width / 2f, baseline, textPaint)
            textPaint.color = oldColor
        }
    }

    private fun setShapePath(l: Float, t: Float, size: Float) {
        clipPath.reset()
        val rect = RectF(l, t, l + size, t + size)
        when (shape) {
            "square" -> clipPath.addRect(rect, Path.Direction.CW)
            "rounded" -> clipPath.addRoundRect(rect, size * 0.18f, size * 0.18f, Path.Direction.CW)
            "round" -> clipPath.addCircle(l + size / 2f, t + size / 2f, size / 2f, Path.Direction.CW)
            "teardrop" -> {
                clipPath.moveTo(l + size * 0.45f, t)
                clipPath.cubicTo(l + size * 0.76f, t, l + size, t + size * 0.23f,
                    l + size, t + size * 0.52f)
                clipPath.lineTo(l + size, t + size)
                clipPath.lineTo(l + size * 0.48f, t + size)
                clipPath.cubicTo(l + size * 0.2f, t + size, l, t + size * 0.78f,
                    l, t + size * 0.5f)
                clipPath.cubicTo(l, t + size * 0.2f, l + size * 0.19f, t,
                    l + size * 0.45f, t)
            }
            "vessel" -> {
                clipPath.moveTo(l + size * 0.18f, t)
                clipPath.lineTo(l + size * 0.82f, t)
                clipPath.cubicTo(l + size, t, l + size, t + size * 0.22f,
                    l + size * 0.9f, t + size * 0.33f)
                clipPath.cubicTo(l + size * 0.83f, t + size * 0.43f,
                    l + size * 0.83f, t + size * 0.57f,
                    l + size * 0.9f, t + size * 0.67f)
                clipPath.cubicTo(l + size, t + size * 0.78f, l + size, t + size,
                    l + size * 0.82f, t + size)
                clipPath.lineTo(l + size * 0.18f, t + size)
                clipPath.cubicTo(l, t + size, l, t + size * 0.78f,
                    l + size * 0.1f, t + size * 0.67f)
                clipPath.cubicTo(l + size * 0.17f, t + size * 0.57f,
                    l + size * 0.17f, t + size * 0.43f,
                    l + size * 0.1f, t + size * 0.33f)
                clipPath.cubicTo(l, t + size * 0.22f, l, t,
                    l + size * 0.18f, t)
            }
            "squircle", "flower", "pebble" -> {
                val cx = l + size / 2f; val cy = t + size / 2f
                for (i in 0..128) {
                    val a = 2.0 * PI * i / 128 - PI / 2
                    val c = cos(a); val s = sin(a)
                    val radius = when (shape) {
                        "flower" -> 0.43 + 0.07 * cos(4 * a)
                        "pebble" -> 0.46 + 0.04 * cos(3 * a + 0.5)
                        else -> 0.5
                    }
                    val x = if (shape == "squircle") cx + size * 0.5 * sign(c) * abs(c).pow(0.5)
                        else cx + size * radius * c
                    val y = if (shape == "squircle") cy + size * 0.5 * sign(s) * abs(s).pow(0.5)
                        else cy + size * radius * s
                    if (i == 0) clipPath.moveTo(x.toFloat(), y.toFloat())
                    else clipPath.lineTo(x.toFloat(), y.toFloat())
                }
            }
            "pentagon", "hexagon", "heptagon", "octagon" -> {
                val count = when (shape) {
                    "pentagon" -> 5; "hexagon" -> 6; "heptagon" -> 7; else -> 8
                }
                for (i in 0 until count) {
                    val a = 2.0 * PI * i / count - PI / 2
                    val x = l + size * (0.5 + 0.5 * cos(a))
                    val y = t + size * (0.5 + 0.5 * sin(a))
                    if (i == 0) clipPath.moveTo(x.toFloat(), y.toFloat())
                    else clipPath.lineTo(x.toFloat(), y.toFloat())
                }
            }
            else -> clipPath.addRect(rect, Path.Direction.CW)
        }
        clipPath.close()
    }

    /**
     * Draws [d] in the square ([l], [t], [size]) using the current [shape]:
     *  - adaptive icons: background + foreground layers, full-bleed, clipped to the tile
     *    (the system mask is skipped);
     *  - full-bleed bitmaps (opaque corners, e.g. cropped custom icons): fill the tile;
     *  - other icons: centred on a plate tinted from the icon's colour.
     */
    private fun drawIcon(canvas: Canvas, d: Drawable, l: Float, t: Float, size: Float) {
        if (shape == "system") {
            d.setBounds(l.roundToInt(), t.roundToInt(), (l + size).roundToInt(), (t + size).roundToInt())
            d.draw(canvas)
            return
        }
        clipRect.set(l, t, l + size, t + size)
        val save = canvas.save()
        setShapePath(l, t, size)
        canvas.clipPath(clipPath)
        if (d is AdaptiveIconDrawable) {
            // Layers are 108dp for a 72dp visible icon: extend by 1/4 of the size on each side.
            val e = size * 0.25f
            val bl = (l - e).roundToInt(); val bt = (t - e).roundToInt()
            val br = (l + size + e).roundToInt(); val bb = (t + size + e).roundToInt()
            d.background?.let { it.setBounds(bl, bt, br, bb); it.draw(canvas) }
                ?: canvas.drawColor(Color.WHITE)
            d.foreground?.let { it.setBounds(bl, bt, br, bb); it.draw(canvas) }
        } else {
            val info = tileInfo(d)
            if (!info.fullBleed) {
                platePaint.color = info.color
                canvas.drawRect(clipRect, platePaint)
                val inset = size * 0.16f
                clipRect.inset(inset, inset)
            }
            d.setBounds(clipRect.left.roundToInt(), clipRect.top.roundToInt(),
                clipRect.right.roundToInt(), clipRect.bottom.roundToInt())
            d.draw(canvas)
        }
        canvas.restoreToCount(save)
    }

    private class TileInfo(val fullBleed: Boolean, val color: Int)

    companion object {
        /** Per-drawable analysis, computed once (icons are shared between views). */
        private val tileCache = WeakHashMap<Drawable, TileInfo>()
        const val TILE_PX = 48

        fun clearCaches() {
            tileCache.clear()
        }

        private fun tileInfo(d: Drawable): TileInfo = tileCache.getOrPut(d) {
            // Keep only analysis results; drawing uses the original drawable.
            // A tiny software sample also avoids copying full-size hardware bitmaps.
            val source = (d as? BitmapDrawable)?.bitmap
            val bmp = if (source?.config == Bitmap.Config.HARDWARE) {
                val scaled = Bitmap.createScaledBitmap(source, TILE_PX, TILE_PX, true)
                try { scaled.copy(Bitmap.Config.ARGB_8888, false) }
                finally { if (scaled !== source) scaled.recycle() }
            } else run {
                val b = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
                val c = Canvas(b)
                val old = d.copyBounds()
                d.setBounds(0, 0, TILE_PX, TILE_PX)
                d.draw(c)
                d.bounds = old
                b
            }
            try { TileInfo(opaqueCorners(bmp), averageColor(bmp)) }
            finally { bmp.recycle() }
        }

        private fun opaqueCorners(b: Bitmap): Boolean {
            val w = b.width - 1; val h = b.height - 1
            val inset = (min(w, h) * 0.03f).toInt() // ignore anti-aliased edge pixels
            return listOf(inset to inset, w - inset to inset, inset to h - inset, w - inset to h - inset)
                .all { (x, y) -> Color.alpha(b.getPixel(x, y)) > 200 }
        }

        /** Plate colour for a non-full-bleed icon, derived from the average of its visible pixels. */
        private fun averageColor(b: Bitmap): Int {
            val step = (min(b.width, b.height) / 24).coerceAtLeast(1)
            var r = 0L; var g = 0L; var bl = 0L; var n = 0L
            var y = 0
            while (y < b.height) {
                var x = 0
                while (x < b.width) {
                    val p = b.getPixel(x, y)
                    if (Color.alpha(p) > 128) { r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++ }
                    x += step
                }
                y += step
            }
            if (n == 0L) return 0xFF607D8B.toInt()
            val ar = (r / n).toInt(); val ag = (g / n).toInt(); val ab = (bl / n).toInt()
            // Tint of the icon's colour that contrasts with it: dark icons get a light plate,
            // light icons a dark one, so the glyph never disappears into its background.
            val lum = (0.299f * ar + 0.587f * ag + 0.114f * ab) / 255f
            val (target, amount) = if (lum < 0.5f) 255 to 0.82f else 0 to 0.72f
            fun mix(c: Int) = (c + (target - c) * amount).toInt()
            return Color.rgb(mix(ar), mix(ag), mix(ab))
        }
    }
}
