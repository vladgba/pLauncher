package x.vladgba.launcher.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference

/**
 * Keeps app icons + labels so the home screen is never drawn with empty icons.
 *
 *  - Memory: weak references reuse live icons during activity recreation,
 *    without keeping every icon alive after the activity is released.
 *  - Disk: icons are stored as PNGs in files/icon_cache with an index
 *    (label + package update time). After a cold start / reboot they are
 *    shown immediately, and the live icons from PackageManager replace them
 *    once the background reload finishes.
 *
 * Only icons whose package changed (or that are missing) are re-encoded.
 */
class IconCache(context: Context) {

    private val res = context.resources
    private val dir = File(context.filesDir, "icon_cache").apply { mkdirs() }
    private val index = AtomicFile(File(dir, "index.json"))
    /** Bitmap edge: allows enlarged icons on dense displays. */
    private fun sizePx(compression: Int = 1) = IconBitmaps.iconEdgePx(res.displayMetrics.density, compression)

    /** Instant: last list loaded in this process, or empty. */
    fun memory(): Map<String, AppInfo> = memoryCache.mapNotNull { (key, value) ->
        value.get()?.let { key to it }
    }.toMap()

    /** Reads the disk cache. Call on the main thread before the first bind (fast: small PNGs). */
    fun loadDisk(compression: Int = 1): Map<String, AppInfo> {
        val idx = readIndex() ?: return emptyMap()
        val out = HashMap<String, AppInfo>()
        val edge = sizePx(compression)
        for (component in idx.keys()) {
            val e = idx.optJSONObject(component) ?: continue
            val f = File(dir, e.optString("file"))
            val bmp = IconBitmaps.decodeFile(f, edge) ?: continue
            out[component] = AppInfo(component, e.optString("label"), BitmapDrawable(res, bmp), e.optLong("updated"))
        }
        return out
    }

    /** Fast label index: reads cached labels and timestamps without decoding any PNGs. */
    fun loadLabels(): Map<String, Pair<Long, String>> {
        val idx = readIndex() ?: return emptyMap()
        val out = HashMap<String, Pair<Long, String>>()
        for (component in idx.keys()) {
            val e = idx.optJSONObject(component) ?: continue
            val l = e.optString("label")
            if (l.isNotEmpty()) {
                out[component] = Pair(e.optLong("updated"), l)
            }
        }
        return out
    }

    fun loadDisk(aggressive: Boolean): Map<String, AppInfo> = loadDisk(if (aggressive) 2 else 1)

    /** Remembers the fresh list and writes changed icons to disk. Call off the main thread. */
    fun store(apps: List<AppInfo>, compression: Int = 1) {
        memoryCache = apps.associate { it.component to WeakReference(it) }
        val old = readIndex() ?: JSONObject()
        val idx = JSONObject()
        val keep = HashSet<String>()
        val edge = sizePx(compression)
        for (a in apps) {
            val name = fileName(a.component)
            keep += name
            val prev = old.optJSONObject(a.component)
            val file = File(dir, name)
            val fresh = prev != null && prev.optLong("updated") == a.updated && a.updated != 0L && file.exists()
            if (!fresh && !writePng(a, file, edge)) continue
            idx.put(a.component, JSONObject()
                .put("label", a.label).put("file", name).put("updated", a.updated))
        }
        // Drop icons of uninstalled apps.
        dir.listFiles()?.forEach { if (it.name.endsWith(".png") && it.name !in keep) it.delete() }
        val out = try { index.startWrite() } catch (e: Exception) { return }
        try {
            out.write(idx.toString().toByteArray(Charsets.UTF_8))
            index.finishWrite(out)
        } catch (e: Exception) {
            index.failWrite(out)
        }
    }

    fun store(apps: List<AppInfo>, aggressive: Boolean) = store(apps, if (aggressive) 2 else 1)

    private fun writePng(a: AppInfo, file: File, edge: Int): Boolean = try {
        // Icons from AppRepository are already normalized to sizePx: compress that
        // shared bitmap directly instead of drawing a second full-size copy.
        val live = (a.icon as? BitmapDrawable)?.bitmap?.takeIf { !it.isRecycled }
        val bmp = when {
            live != null && live.width == edge && live.height == edge -> live
            live != null -> Bitmap.createScaledBitmap(live, edge, edge, true)
            else -> {
                val b = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
                val d = a.icon.constantState?.newDrawable(res)?.mutate() ?: a.icon
                d.setBounds(0, 0, edge, edge)
                d.draw(Canvas(b))
                b
            }
        }
        val tmp = File(dir, file.name + ".tmp")
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (bmp !== live) bmp.recycle()
        tmp.renameTo(file)
    } catch (e: Exception) {
        Log.w("pLauncher", "icon cache write failed for ${a.component}", e)
        false
    }

    private fun readIndex(): JSONObject? = try {
        JSONObject(String(index.readFully(), Charsets.UTF_8))
    } catch (_: Exception) { null }

    private fun fileName(component: String) =
        component.replace(Regex("[^A-Za-z0-9._-]"), "_").take(180) + "_" +
            Integer.toHexString(component.hashCode()) + ".png"

    private companion object {
        @Volatile var memoryCache: Map<String, WeakReference<AppInfo>> = emptyMap()
    }
}
