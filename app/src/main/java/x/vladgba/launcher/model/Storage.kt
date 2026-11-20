package x.vladgba.launcher.model

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.AtomicFile
import android.util.Log
import org.json.JSONObject
import java.io.File

/** Persists the workspace as JSON. AtomicFile keeps the old copy if a write is interrupted. */
class WorkspaceStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "workspace.json"))

    /** True until the workspace has been saved once (first start of the launcher). */
    val isFirstRun: Boolean get() = !file.baseFile.exists()

    fun load(): WorkspaceModel = try {
        WorkspaceModel.fromJson(JSONObject(String(file.readFully(), Charsets.UTF_8)))
    } catch (e: Exception) {
        WorkspaceModel()
    }

    fun save(model: WorkspaceModel) {
        val out = try { file.startWrite() } catch (e: Exception) { Log.e(TAG, "save", e); return }
        try {
            out.write(model.toJson().toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.e(TAG, "save", e)
        }
    }

    private companion object { const val TAG = "pLauncher" }
}

/** [updated] = package lastUpdateTime, used to tell whether a cached icon is stale. */
class AppInfo(val component: String, val label: String, var icon: Drawable, val updated: Long = 0L) {
    val componentName: ComponentName get() = ComponentName.unflattenFromString(component)!!
    val packageName: String get() = componentName.packageName
}

/**
 * Queries launchable activities. Safe to call off the main thread.
 *
 * Every returned icon is normalized to the shared icon edge (see IconBitmaps),
 * so memory holds one small bitmap per app instead of whatever size each app
 * shipped. The store is process-wide: a second repository (e.g. the search
 * overlay) reuses the icons instead of loading them all again.
 */
class AppRepository(private val context: Context) {
    fun query(
        compression: Int = 1,
        colorDepth: String = "32",
        neededComponents: Set<String>? = null
    ): List<AppInfo> {
        val pm = context.packageManager
        val res = context.resources
        val edge = IconBitmaps.iconEdgePx(res.displayMetrics.density, compression)
        val cacheKey = "$edge-$colorDepth"
        if (previousKey != cacheKey) {
            previous = emptyMap()
            previousKey = cacheKey
        }
        if (labels.isEmpty()) {
            try {
                labels.putAll(IconCache(context).loadLabels())
            } catch (_: Exception) {}
        }
        val diskIcons = if (neededComponents != null && previous.isEmpty()) {
            try { IconCache(context).loadDisk(compression) } catch (_: Exception) { emptyMap() }
        } else emptyMap()

        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val updated = HashMap<String, Long>()
        val apps = pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map {
                val ai = it.activityInfo
                val time = updated.getOrPut(ai.packageName) {
                    try { pm.getPackageInfo(ai.packageName, 0).lastUpdateTime } catch (_: Exception) { 0L }
                }
                val component = ComponentName(ai.packageName, ai.name).flattenToString()
                val isNeeded = neededComponents == null || component in neededComponents
                val cached = previous[component]?.takeIf { time != 0L && it.updated == time && it.icon !== placeholder }
                val icon = if (isNeeded) {
                    cached?.icon
                        ?: diskIcons[component]?.takeIf { it.updated == time && it.icon !== placeholder }?.icon
                        ?: IconBitmaps.render(res, it.loadIcon(pm), edge, colorDepth)
                } else {
                    cached?.icon ?: placeholder
                }
                val cachedLabel = labels[component]?.takeIf { it.first == time && it.second.isNotEmpty() }?.second
                val label = cachedLabel ?: (ai.nonLocalizedLabel?.toString() ?: it.loadLabel(pm).toString()).also { str ->
                    labels[component] = Pair(time, str)
                }
                AppInfo(
                    component,
                    label,
                    icon,
                    time
                )
            }
            .sortedBy { it.label.lowercase() }
        previous = apps.filter { it.icon !== placeholder }.associateBy { it.component }
        return apps
    }

    fun loadIcon(component: String, compression: Int = 1, colorDepth: String = "32"): Drawable? = try {
        val cn = ComponentName.unflattenFromString(component) ?: return null
        val pm = context.packageManager
        val res = context.resources
        val edge = IconBitmaps.iconEdgePx(res.displayMetrics.density, compression)
        val raw = pm.getActivityIcon(cn)
        val rendered = IconBitmaps.render(res, raw, edge, colorDepth)
        val current = previous.toMutableMap()
        current[component]?.let { it.icon = rendered }
        previous = current
        rendered
    } catch (_: Exception) { null }

    fun query(compression: Int): List<AppInfo> = query(compression, "32", null)
    fun query(aggressive: Boolean): List<AppInfo> = query(if (aggressive) 2 else 1, "32", null)

    companion object {
        private var previous: Map<String, AppInfo> = emptyMap()
        private var previousKey: String = ""
        private val labels = HashMap<String, Pair<Long, String>>()

        val placeholder: Drawable = ColorDrawable(Color.TRANSPARENT)

        /** Drops every cached icon so the next query re-renders them. Called on low memory. */
        fun releaseIcons() {
            previous = emptyMap()
            previousKey = ""
        }
    }
}
