package x.vladgba.launcher

import x.vladgba.launcher.pui.PAboutInfo
import x.vladgba.launcher.pui.showPAbout

import android.app.Activity
import android.app.ActivityOptions
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.SizeF
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import x.vladgba.launcher.model.AppInfo
import x.vladgba.launcher.model.AppItem
import x.vladgba.launcher.model.AppRepository
import x.vladgba.launcher.model.FolderItem
import x.vladgba.launcher.model.groupByPackage
import x.vladgba.launcher.model.GridItem
import x.vladgba.launcher.model.HomeItem
import x.vladgba.launcher.model.IconCache
import x.vladgba.launcher.model.IconBitmaps
import x.vladgba.launcher.model.WidgetItem
import x.vladgba.launcher.model.WorkspaceModel
import x.vladgba.launcher.model.WorkspaceStore
import x.vladgba.launcher.ui.AppIconView
import x.vladgba.launcher.ui.CropView
import x.vladgba.launcher.ui.DragSourcePanel
import x.vladgba.launcher.ui.Palette
import x.vladgba.launcher.ui.PageIndicator
import x.vladgba.launcher.ui.PageView
import x.vladgba.launcher.ui.ResizeFrame
import x.vladgba.launcher.ui.WorkspaceView
import x.vladgba.launcher.ui.dp
import x.vladgba.launcher.ui.dpi
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.hypot

class LauncherActivity : Activity(), WorkspaceView.Callbacks {

    private lateinit var store: WorkspaceStore
    private lateinit var model: WorkspaceModel
    val config get() = model.config
    private lateinit var repo: AppRepository
    private lateinit var iconCache: IconCache
    private lateinit var widgetManager: AppWidgetManager
    private lateinit var widgetHost: LauncherWidgetHost
    private lateinit var launcherApps: LauncherApps

    private lateinit var root: FrameLayout
    private lateinit var workspace: WorkspaceView
    private lateinit var dragLayer: FrameLayout
    private lateinit var indicator: PageIndicator
    private lateinit var dockPanel: LinearLayout
    private lateinit var dockScroll: HorizontalScrollView
    private lateinit var dockPages: LinearLayout
    private var dockPageWidth = 0

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val reloadRunnable = Runnable { reloadApps() }

    /** Installed apps by flattened component name. */
    var apps: Map<String, AppInfo> = emptyMap(); private set
    private val unmaskedIcons = HashMap<String, Drawable?>()

    private var pendingWidgetId = -1
    private var pendingWidgetInfo: AppWidgetProviderInfo? = null

    /** Status/navigation bar + cutout insets, for overlays. */
    val systemInsets = Rect()
    lateinit var shortcuts: Shortcuts; private set

    /** Open folder / widget panel, if any. */
    private var openPanel: DragSourcePanel? = null
    /** Folder an app is being dragged out of. */
    private var dragSourceFolder: FolderItem? = null
    private var dragSourceDock: String? = null
    private var dragSourceDrawer: String? = null
    private var dockEdgeDirection = 0
    private val dockEdgeFlip = object : Runnable {
        override fun run() {
            if (dockPageWidth <= 0 || dockEdgeDirection == 0) return
            val next = (dockScroll.scrollX / dockPageWidth) + dockEdgeDirection
            if (next in 0 until dockPages.childCount) {
                dockScroll.smoothScrollTo(next * dockPageWidth, 0)
                main.postDelayed(this, 650)
            }
        }
    }
    /** Widget being dragged in from the picker. */
    private var dragWidgetInfo: AppWidgetProviderInfo? = null
    /** Where a dropped widget goes once bound/configured (null → page centre). */
    private var pendingPlacement: WidgetItem? = null
    /** Placeholder widget (from a backup) currently being re-created, or -1. */
    private var pendingRestoreId = -1L
    /** Placeholders the user declined this session: not offered again until tapped. */
    private val skippedRestores = HashSet<Long>()

    private var resizeFrame: ResizeFrame? = null
    private var resizeItem: WidgetItem? = null
    /** True while the home screen is visible and focused (no app or dialog over it). */
    private var hasFocus = false
    /** First start: apps wait for the "group by package" dialog before being placed. */
    private var firstRun = false
    private var groupDialogShown = false

    // ================================================================= lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindow()

        store = WorkspaceStore(this)
        firstRun = store.isFirstRun
        model = store.load()
        repo = AppRepository(this)
        iconCache = IconCache(this)
        // Show cached icons right away; live icons replace them after reloadApps().
        apps = iconCache.memory().ifEmpty { iconCache.loadDisk(model.config.effectiveCompression) }
        widgetManager = AppWidgetManager.getInstance(this)
        widgetHost = LauncherWidgetHost(this, WIDGET_HOST_ID) { main.post { reloadBrokenWidgets() } }
        launcherApps = getSystemService(LauncherApps::class.java)
        shortcuts = Shortcuts(this)

        root = FrameLayout(this)
        dragLayer = FrameLayout(this)
        workspace = WorkspaceView(this, dragLayer).also {
            it.model = model
            it.callbacks = this
        }
        indicator = PageIndicator(this)
        dockPages = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dockScroll = x.vladgba.launcher.ui.DockPager(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(dockPages)
            addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
                val newWidth = r - l
                if (newWidth > 0 && newWidth != dockPageWidth) {
                    dockPageWidth = newWidth
                    rebuildDock()
                }
            }
        }
        dockPanel = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x66000000)
            addView(dockScroll, LinearLayout.LayoutParams(0, -1, 1f))
        }
        root.addView(workspace, FrameLayout.LayoutParams(-1, -1))
        root.addView(indicator, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        root.addView(dockPanel, FrameLayout.LayoutParams(-1, dpi(model.config.dockHeight.toFloat()), Gravity.BOTTOM))
        root.addView(dragLayer, FrameLayout.LayoutParams(-1, -1))
        if (model.config.softwareRendering) {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            root.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
        }
        setContentView(root)

        root.setOnApplyWindowInsetsListener { _, insets -> applyInsets(insets); insets }

        savedInstanceState?.let {
            pendingWidgetId = it.getInt(STATE_PENDING_WIDGET, -1)
            if (pendingWidgetId != -1) pendingWidgetInfo = widgetManager.getAppWidgetInfo(pendingWidgetId)
            pendingIconComponent = it.getString(STATE_ICON_COMPONENT)
            pendingRestoreId = it.getLong(STATE_RESTORE, -1L)
            it.getFloatArray(STATE_PLACEMENT)?.takeIf { f -> f.size == 6 }?.let { f ->
                pendingPlacement = WidgetItem(-1, -1, "").apply {
                    pageX = f[0].toInt(); pageY = f[1].toInt(); x = f[2]; y = f[3]; w = f[4]; h = f[5]
                }
            }
        }

        cleanupOrphanWidgets()
        applyColorMode()
        workspace.sync()
        updateHomeChrome()
        if (savedInstanceState == null) workspace.snapTo(model.config.homeX, model.config.homeY, animate = false)
        reloadApps()
        launcherApps.registerCallback(packageCallback, main)
    }

    /** Low memory: drop caches that re-create themselves on the next bind/query. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (model.config.aggressiveMemory || model.config.iconCompression > 1 || level >= TRIM_MEMORY_RUNNING_LOW) {
            trimMemoryAggressively()
        }
    }

    fun trimMemoryAggressively() {
        customIconCache.clear() // decoded lazily again from files/custom_icons
        unmaskedIcons.clear()
        AppIconView.clearCaches()
        AppRepository.releaseIcons() // next reloadApps() re-renders at the icon edge
        if (model.config.aggressiveMemory || model.config.iconCompression > 1) {
            System.gc()
        }
        logMemoryStats("afterTrim")
    }

    fun logMemoryStats(tag: String) {
        val rt = Runtime.getRuntime()
        val heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val heapAllocMb = rt.totalMemory() / (1024 * 1024)
        try {
            val smaps = java.io.File("/proc/self/smaps_rollup")
            if (smaps.exists()) {
                val lines = smaps.readLines().joinToString(" | ") { it.trim() }
                android.util.Log.e("pLauncherRAM", "[$tag] smaps_rollup: $lines")
            }
        } catch (_: Exception) {}
        try {
            val am = getSystemService(android.app.ActivityManager::class.java)
            val mi = am?.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))?.firstOrNull()
            if (mi != null) {
                android.util.Log.e("pLauncherRAM", "[$tag] Java Heap: ${heapUsedMb}MB used (${heapAllocMb}MB alloc) | Android PSS: ${mi.totalPss / 1024}MB | Dalvik PSS: ${mi.dalvikPss / 1024}MB | Native PSS: ${mi.nativePss / 1024}MB | Graphics/Shared: ${mi.otherPss / 1024}MB | Private Dirty: ${mi.totalPrivateDirty / 1024}MB")
            } else {
                android.util.Log.e("pLauncherRAM", "[$tag] Java Heap: ${heapUsedMb}MB used (${heapAllocMb}MB alloc)")
            }
        } catch (e: Exception) {
            android.util.Log.e("pLauncherRAM", "[$tag] Java Heap: ${heapUsedMb}MB used", e)
        }
    }

    override fun onStart() {
        super.onStart()
        try { widgetHost.startListening() } catch (_: Exception) { }
        // Coming back: retry anything that failed while we weren't listening.
        if (::workspace.isInitialized) reloadBrokenWidgets()
        logMemoryStats("onStart")
    }

    override fun onStop() {
        super.onStop()
        try { widgetHost.stopListening() } catch (_: Exception) { }
        if (model.config.aggressiveMemory || model.config.iconCompression > 1) {
            trimMemoryAggressively()
        }
    }

    override fun onWindowFocusChanged(focus: Boolean) {
        super.onWindowFocusChanged(focus)
        hasFocus = focus
    }

    override fun onDestroy() {
        stopDockEdge()
        launcherApps.unregisterCallback(packageCallback)
        io.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PENDING_WIDGET, pendingWidgetId)
        outState.putLong(STATE_RESTORE, pendingRestoreId)
        pendingIconComponent?.let { outState.putString(STATE_ICON_COMPONENT, it) }
        pendingPlacement?.let {
            outState.putFloatArray(STATE_PLACEMENT,
                floatArrayOf(it.pageX.toFloat(), it.pageY.toFloat(), it.x, it.y, it.w, it.h))
        }
    }

    /**
     * Home key → default page.
     * Android pauses the activity before delivering this intent, so "already on home"
     * is detected via window focus (same approach as AOSP Launcher3), not onResume.
     *  - already on home screen → animated scroll to the default page
     *  - returning from an app  → jump there instantly (no visible animation)
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Intent.ACTION_MAIN != intent.action) return
        val alreadyOnHome = hasFocus &&
                (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0
        val c = model.config
        // Already resting on the default page → run the configured Home action.
        val onDefault = alreadyOnHome && resizeFrame == null && openPanel == null &&
                workspace.isSettledOn(c.homeX, c.homeY)
        stopDockEdge()
        openPanel?.cancel()
        finishResize()
        workspace.cancelDrag()
        rebuildDock()
        if (onDefault) {
            HomeActions.perform(this, HomeAction.of(c.homeAction))
        } else {
            workspace.snapTo(c.homeX, c.homeY, animate = alreadyOnHome)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // A launcher never finishes on Back; just leave edit modes / close panels.
        if (cropScreen != null) { closeCropScreen(); return }
        openPanel?.let { it.cancel(); return }
        finishResize()
    }

    private fun setupWindow() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    private fun applyInsets(insets: WindowInsets) {
        val r = if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            Rect(i.left, i.top, i.right, i.bottom)
        } else {
            @Suppress("DEPRECATION")
            Rect(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        systemInsets.set(r)
        updateHomeChrome()
    }

    private fun updateHomeChrome() {
        val c = model.config
        val panelHeight = dpi((if (c.dockEnabled) c.dockHeight else 0).toFloat())
        dockPanel.visibility = if (c.dockEnabled) View.VISIBLE else View.GONE
        (dockPanel.layoutParams as FrameLayout.LayoutParams).also {
            it.height = panelHeight
            it.bottomMargin = systemInsets.bottom
            dockPanel.layoutParams = it
        }
        dockScroll.visibility = if (c.dockEnabled) View.VISIBLE else View.GONE
        indicator.style = c.indicatorStyle
        indicator.sizeDp = c.indicatorSize
        (indicator.layoutParams as FrameLayout.LayoutParams).also {
            it.bottomMargin = systemInsets.bottom + panelHeight + dpi(4f)
            indicator.layoutParams = it
        }
        val pad = dpi(c.edgePadding.toFloat())
        val indicatorHeight = when (c.indicatorStyle) {
            "hidden" -> 0
            "numbers" -> dpi(((c.indicatorSize + 7) * 1.6f + 4f))
            else -> dpi((c.pagesY * c.indicatorSize + (c.pagesY + 1) * 5 + 4).toFloat())
        }
        workspace.setContentInsets(systemInsets.left + pad, systemInsets.top + pad,
            systemInsets.right + pad, systemInsets.bottom + panelHeight + indicatorHeight + pad)
        rebuildDock()
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun rebuildDock() {
        if (!::dockPages.isInitialized || dockPageWidth <= 0) return
        val c = model.config
        val oldPage = dockScroll.scrollX / dockPageWidth
        dockPages.removeAllViews()
        val pageCount = max(c.dockPages, (model.dock.size + c.dockColumns - 1) / c.dockColumns)
        for (pageIndex in 0 until pageCount) {
            val start = pageIndex * c.dockColumns
            val group = if (start < model.dock.size)
                model.dock.subList(start, min(start + c.dockColumns, model.dock.size))
            else emptyList()
            val page = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            dockPages.addView(page, LinearLayout.LayoutParams(dockPageWidth, -1))
            for (component in group) {
                if (component.isEmpty()) {
                    page.addView(View(this), LinearLayout.LayoutParams(0, -1, 1f))
                    continue
                }
                val folder = model.dockFolder(component)
                if (component.startsWith("folder:") && folder == null) {
                    page.addView(View(this), LinearLayout.LayoutParams(0, -1, 1f))
                    continue
                }
                var lastRawX = 0f
                var lastRawY = 0f
                var dragStartX = 0f
                var dragStartY = 0f
                var dragging = false
                var moved = false
                val icon: View = if (folder != null) {
                    createItemView(folder).also { v ->
                        bindItemView(folder, v)
                        v.setOnClickListener { Panels.openFolder(this@LauncherActivity, folder) }
                    }
                } else {
                    AppIconView(this).apply {
                        this.icon = iconFor(component)
                        label = labelFor(component)
                        showLabel = c.showLabels
                        labelSizeSp = c.labelSize.toFloat()
                        labelPosition = c.labelPosition
                        iconScale = c.iconScale / 100f
                        shape = c.iconShape
                        applyHomePalette()
                        labelShadow = c.labelShadow
                        labelShadowStrength = c.labelShadowStrength
                        setOnClickListener { launch(component, this) }
                    }
                }
                icon.setOnLongClickListener {
                    val started = if (lastRawX == 0f) false else if (folder != null) {
                        startDockFolderDrag(folder, component, lastRawX, lastRawY)
                    } else {
                        startDockAppDrag(component, lastRawX, lastRawY)
                    }
                    if (!started) {
                        if (folder != null) onItemLongPressReleased(folder, icon) else showAppMenu(component, icon, null)
                    } else {
                        icon.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        icon.isPressed = false
                        icon.alpha = 0.35f
                        dragging = true
                        moved = false
                        dragStartX = lastRawX; dragStartY = lastRawY
                        icon.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    true
                }
                icon.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        lastRawX = event.rawX; lastRawY = event.rawY
                    }
                    if (!dragging) return@setOnTouchListener false
                    when (event.actionMasked) {
                        MotionEvent.ACTION_MOVE -> {
                            if (hypot(event.rawX - dragStartX, event.rawY - dragStartY) >
                                ViewConfiguration.get(this).scaledTouchSlop) moved = true
                            externalDragMove(event.rawX, event.rawY)
                            updateDockEdge(event.rawX, event.rawY)
                        }
                        MotionEvent.ACTION_UP -> {
                            stopDockEdge()
                            icon.alpha = 1f
                            if (!moved) {
                                externalDragEnd(true)
                                icon.post {
                                    if (folder != null) onItemLongPressReleased(folder, icon)
                                    else showAppMenu(component, icon, null)
                                }
                            } else if (overDock(event.rawX, event.rawY)) {
                                externalDragEnd(true)
                                reorderDock(component, event.rawX)
                            } else externalDragEnd(false)
                            dragging = false
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            stopDockEdge()
                            icon.alpha = 1f
                            externalDragEnd(true)
                            dragging = false
                        }
                    }
                    true
                }
                page.addView(icon, LinearLayout.LayoutParams(0, -1, 1f))
            }
            repeat(c.dockColumns - group.size) {
                page.addView(View(this), LinearLayout.LayoutParams(0, -1, 1f))
            }
        }
        dockScroll.post { dockScroll.scrollTo(oldPage.coerceAtMost((dockPages.childCount - 1).coerceAtLeast(0)) * dockPageWidth, 0) }
    }

    private fun overDock(rawX: Float, rawY: Float): Boolean {
        if (!model.config.dockEnabled || dockPanel.visibility != View.VISIBLE) return false
        val p = IntArray(2)
        dockPanel.getLocationOnScreen(p)
        return rawX >= p[0] && rawX < p[0] + dockPanel.width &&
            rawY >= p[1] && rawY < p[1] + dockPanel.height
    }

    private fun dockIndexAt(rawX: Float): Int {
        if (dockPageWidth <= 0) return model.dock.size
        val p = IntArray(2)
        dockScroll.getLocationOnScreen(p)
        val x = (rawX - p[0]).coerceIn(0f, dockPageWidth.toFloat())
        val maxSlots = dockPages.childCount.coerceAtLeast(1) * model.config.dockColumns
        return ((dockScroll.scrollX + x) / (dockPageWidth.toFloat() / model.config.dockColumns))
            .toInt().coerceIn(0, maxSlots - 1)
    }

    private fun trimDock() {
        while (model.dock.isNotEmpty() && model.dock.last().isEmpty()) {
            model.dock.removeAt(model.dock.lastIndex)
        }
    }

    private fun updateDockEdge(rawX: Float, rawY: Float) {
        if (dockPageWidth <= 0) return
        val p = IntArray(2)
        dockScroll.getLocationOnScreen(p)
        val margin = dpi(28f)
        val direction = if (!overDock(rawX, rawY)) 0 else when {
            rawX < p[0] + margin && dockScroll.scrollX > 0 -> -1
            rawX > p[0] + dockScroll.width - margin &&
                dockScroll.scrollX / dockPageWidth < dockPages.childCount - 1 -> 1
            else -> 0
        }
        if (direction != dockEdgeDirection) {
            main.removeCallbacks(dockEdgeFlip)
            dockEdgeDirection = direction
            if (direction != 0) main.postDelayed(dockEdgeFlip, 500)
        }
    }

    private fun stopDockEdge() {
        main.removeCallbacks(dockEdgeFlip)
        dockEdgeDirection = 0
    }

    private fun startDockFolderDrag(folder: FolderItem, entry: String, rawX: Float, rawY: Float): Boolean {
        if (isLayoutLocked()) return false
        val page = workspace.currentPage ?: return false
        val ghost = createItemView(folder)
        ghost.tag = folder
        bindItemView(folder, ghost)
        dragSourceDock = entry
        val ok = workspace.beginExternalDrag(folder, ghost,
            page.cellW.toInt().coerceAtLeast(dpi(48f)),
            page.cellH.toInt().coerceAtLeast(dpi(48f)), rawX, rawY)
        if (!ok) dragSourceDock = null
        return ok
    }

    private fun moveFolderToDock(folder: FolderItem, targetIndex: Int = -1) {
        if (!model.config.dockEnabled) {
            model.config.dockEnabled = true
            updateHomeChrome()
        }
        model.items.remove(folder)
        if (model.dockFolders.none { it.id == folder.id }) model.dockFolders += folder
        val entry = "folder:${folder.id}"
        val prev = model.dock.indexOf(entry)
        if (prev >= 0) model.dock[prev] = ""
        if (targetIndex < 0) {
            val emptyIdx = model.dock.indexOf("")
            if (emptyIdx >= 0) model.dock[emptyIdx] = entry else model.dock.add(entry)
        } else {
            while (model.dock.size <= targetIndex) model.dock.add("")
            if (model.dock[targetIndex].isEmpty()) {
                model.dock[targetIndex] = entry
            } else {
                model.dock.add(targetIndex, entry)
            }
        }
        trimDock()
        saveAndSync()
        rebuildDock()
    }

    private fun startDockAppDrag(component: String, rawX: Float, rawY: Float): Boolean {
        if (isLayoutLocked()) return false
        val page = workspace.currentPage ?: return false
        val item = AppItem(model.newId(), component)
        val ghost = createItemView(item)
        ghost.tag = item
        bindItemView(item, ghost)
        dragSourceDock = component
        val ok = workspace.beginExternalDrag(item, ghost,
            page.cellW.toInt().coerceAtLeast(dpi(48f)),
            page.cellH.toInt().coerceAtLeast(dpi(48f)), rawX, rawY)
        if (!ok) dragSourceDock = null
        return ok
    }

    private fun reorderDock(component: String, rawX: Float) {
        val target = dockIndexAt(rawX)
        val srcIdx = model.dock.indexOf(component)
        if (srcIdx < 0) return
        while (model.dock.size <= target) model.dock.add("")
        if (target == srcIdx) return
        val displaced = model.dock[target]
        model.dock[target] = component
        model.dock[srcIdx] = displaced
        trimDock()
        save()
        rebuildDock()
    }

    fun openAppDrawer() { Panels.openAppDrawer(this) }

    private fun moveToDock(component: String, targetIndex: Int = -1) {
        if (component !in apps) return
        if (!model.config.dockEnabled) {
            model.config.dockEnabled = true
            updateHomeChrome()
        }
        model.hidden.remove(component)
        model.drawerOnly.remove(component)
        model.items.removeAll { it is AppItem && it.component == component }
        for (folder in model.folders()) {
            folder.apps.remove(component)
            if (folder.apps.isEmpty()) model.items.remove(folder)
        }
        val prev = model.dock.indexOf(component)
        if (prev >= 0) model.dock[prev] = ""
        if (targetIndex < 0) {
            val emptyIdx = model.dock.indexOf("")
            if (emptyIdx >= 0) model.dock[emptyIdx] = component else model.dock.add(component)
        } else {
            while (model.dock.size <= targetIndex) model.dock.add("")
            if (model.dock[targetIndex].isEmpty()) {
                model.dock[targetIndex] = component
            } else {
                model.dock.add(targetIndex, component)
            }
        }
        trimDock()
        saveAndSync()
        rebuildDock()
        dockScroll.post {
            if (dockPageWidth > 0) dockScroll.smoothScrollTo(
                (model.dock.indexOf(component) / model.config.dockColumns) * dockPageWidth, 0)
        }
    }

    private fun removeAppPlacement(component: String) {
        model.items.removeAll { it is AppItem && it.component == component }
        for (folder in model.folders()) {
            folder.apps.remove(component)
            if (folder.apps.isEmpty()) model.items.remove(folder)
        }
        for (i in model.dock.indices) if (model.dock[i] == component) model.dock[i] = ""
        trimDock()
    }

    private fun moveToDrawer(component: String) {
        removeAppPlacement(component)
        model.hidden.remove(component)
        model.drawerOnly.add(component)
        saveAndSync()
        rebuildDock()
    }

    private fun moveToHome(component: String) {
        removeAppPlacement(component)
        model.hidden.remove(component)
        model.drawerOnly.remove(component)
        if (component in apps) {
            val item = AppItem(model.newId(), component)
            model.items.add(item)
            val (w, h) = workspace.contentSizeDp()
            model.placeItems(listOf(item), w, h, workspace.curX, workspace.curY)
        }
        saveAndSync()
        rebuildDock()
    }

    private fun moveFromDock(component: String) {
        val idx = model.dock.indexOf(component)
        if (idx < 0) return
        model.dock[idx] = ""
        trimDock()
        model.hidden.remove(component)
        if (component in apps) {
            val item = AppItem(model.newId(), component)
            model.items += item
            val (w, h) = workspace.contentSizeDp()
            model.placeItems(listOf(item), w, h, workspace.curX, workspace.curY)
        }
        saveAndSync()
        rebuildDock()
    }

    // ================================================================= apps

    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackageAdded(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = scheduleReload()
        override fun onPackagesAvailable(p: Array<out String>, user: UserHandle, replacing: Boolean) = scheduleReload()
        override fun onPackagesUnavailable(p: Array<out String>, user: UserHandle, replacing: Boolean) = scheduleReload()
    }

    private fun scheduleReload() {
        main.removeCallbacks(reloadRunnable)
        main.postDelayed(reloadRunnable, 400)
    }

    fun reloadApps() {
        val compression = model.config.effectiveCompression
        val colorDepth = model.config.colorDepth
        val needed = if (model.config.lazyIcons && !firstRun) model.allPlacedComponents() else null
        io.execute {
            val list = repo.query(compression, colorDepth, needed)
            main.post {
                if (isDestroyed) return@post
                unmaskedIcons.clear()
                apps = list.associateBy { it.component }
                for (f in model.dockFolders) f.apps.retainAll(apps.keys)
                model.dockFolders.removeAll { it.apps.isEmpty() }
                for (i in model.dock.indices) {
                    val entry = model.dock[i]
                    if (entry.startsWith("folder:")) {
                        if (model.dockFolder(entry) == null) model.dock[i] = ""
                    } else if (entry.isNotEmpty() && entry !in apps) {
                        model.dock[i] = ""
                    }
                }
                trimDock()
                if (firstRun && list.isNotEmpty()) {
                    // Nothing is placed until the user chose package groups (or skipped).
                    if (!groupDialogShown) {
                        groupDialogShown = true
                        Dialogs.groupByPackage(this, list.map { it.component }) { groups ->
                            firstRun = false
                            val n = model.groupByPackage(
                                apps.values.sortedBy { it.label.lowercase() }.map { it.component }, groups)
                            if (n > 0) toast(if (n == 1) "Created 1 folder" else "Created $n folders")
                            reloadApps()
                        }
                    }
                    return@post
                }
                removeDeadWidgets()
                val (w, h) = workspace.contentSizeDp()
                val pagesBefore = model.config.pagesX
                // Hidden apps are left out, so syncApps removes them from pages and folders.
                if (model.config.newAppLocation == "drawer") {
                    val placed = model.items.filterIsInstance<AppItem>().map { it.component }.toSet() +
                        model.folders().flatMap { it.apps } + model.dock
                    list.asSequence().map { it.component }
                        .filter { it !in placed && it !in model.hidden }
                        .forEach { model.drawerOnly += it }
                }
                model.syncApps(list.map { it.component }.filter {
                    it !in model.hidden && it !in model.dock && it !in model.drawerOnly
                }, w, h)
                if (model.config.pagesX > pagesBefore) {
                    toast("Added pages to fit all apps")
                }
                saveAndSync()
                rebuildDock()
                logMemoryStats("onAppsLoaded")
                io.execute { iconCache.store(list.filter { it.icon !== AppRepository.placeholder }, compression) }
                // Widgets from a backup whose app is (now) installed get re-created.
                restoreNextWidget()
            }
        }
    }

    fun launch(component: String, from: View) {
        val cn = ComponentName.unflattenFromString(component) ?: return
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height).toBundle()
        try {
            startActivity(intent, opts)
        } catch (e: Exception) {
            toast("Can't open app")
        }
    }

    /** Icon shown for an app: custom icon if set, else the app's own. */
    fun iconFor(component: String): Drawable? {
        val custom = customIcon(component)
        if (custom != null) return custom
        val app = apps[component] ?: return null
        // Cached bitmaps already contain Android's original adaptive mask. Use the
        // package's layers for a selected launcher mask so no old outline remains.
        if (model.config.iconShape != "system") {
            if (!unmaskedIcons.containsKey(component)) {
                unmaskedIcons[component] = try {
                    val cn = ComponentName.unflattenFromString(component)
                    (cn?.let { packageManager.getActivityIcon(it) } as? AdaptiveIconDrawable)
                } catch (_: Exception) { null }
            }
            unmaskedIcons[component]?.let { return it }
        }
        if (app.icon === AppRepository.placeholder) {
            val loaded = repo.loadIcon(component, model.config.effectiveCompression, model.config.colorDepth)
            if (loaded != null) app.icon = loaded
        }
        return app.icon
    }

    fun originalIcon(component: String): Drawable? {
        val app = apps[component] ?: return null
        if (app.icon === AppRepository.placeholder) {
            val loaded = repo.loadIcon(component, model.config.effectiveCompression, model.config.colorDepth)
            if (loaded != null) app.icon = loaded
        }
        return app.icon
    }
    /** Label shown for an app: custom name if set, else the app's own. */
    fun labelFor(component: String): String = model.customLabels[component] ?: originalLabel(component)
    fun originalLabel(component: String): String = apps[component]?.label
        ?: if (apps.isEmpty()) "" else component.substringAfterLast('.')

    // ================================================================= custom icons & names

    private val customIconDir by lazy { File(filesDir, "custom_icons").apply { mkdirs() } }
    private val customIconCache = HashMap<String, Drawable>()
    /** App whose icon is being picked from the gallery (survives the picker activity). */
    private var pendingIconComponent: String? = null

    fun hasCustomIcon(component: String) = model.customIcons.containsKey(component)
    fun customLabelOf(component: String) = model.customLabels[component]

    private fun customIcon(component: String): Drawable? {
        val spec = model.customIcons[component] ?: return null
        return when {
            spec.startsWith("app:") -> apps[spec.removePrefix("app:")]?.icon
            spec.startsWith("file:") -> customIconCache[spec] ?: run {
                val edge = IconBitmaps.iconEdgePx(resources.displayMetrics.density, model.config.effectiveCompression)
                val bmp = IconBitmaps.decodeFile(File(customIconDir, spec.removePrefix("file:")), edge)
                bmp?.let { BitmapDrawable(resources, it).also { d -> customIconCache[spec] = d } }
            }
            else -> null
        }
    }

    /** null → back to the app's own name. */
    fun setCustomLabel(component: String, label: String?) {
        if (label.isNullOrBlank() || label == originalLabel(component)) model.customLabels.remove(component)
        else model.customLabels[component] = label
        saveAndSync()
    }

    /** null → back to the app's own icon. Deletes the previous image file, if any. */
    fun setCustomIcon(component: String, spec: String?) {
        val old = model.customIcons[component]
        if (old != null && old != spec && old.startsWith("file:")) {
            customIconCache.remove(old)
            File(customIconDir, old.removePrefix("file:")).delete()
        }
        if (spec == null) model.customIcons.remove(component) else model.customIcons[component] = spec
        saveAndSync()
    }

    /** Opens the system image picker; the result becomes [component]'s icon. */
    fun pickIconImage(component: String) {
        pendingIconComponent = component
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_ICON_IMAGE)
        } catch (e: ActivityNotFoundException) {
            pendingIconComponent = null
            toast("No image picker available")
        }
    }

    /** Decodes the picked image (downsampled, EXIF-rotated) and opens the crop screen. */
    private fun onIconImagePicked(uri: Uri) {
        val component = pendingIconComponent ?: return
        pendingIconComponent = null
        io.execute {
            val bmp = try { decodeForCrop(uri) } catch (e: Exception) { null }
            main.post {
                if (isDestroyed) return@post
                if (bmp == null) { toast("Couldn't read that image"); return@post }
                showCropScreen(component, bmp)
            }
        }
    }

    private fun decodeForCrop(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= CROP_MAX_PX) sample *= 2
        val src = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        // Camera photos are often stored sideways with an EXIF rotation tag.
        val deg = try {
            contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (_: Exception) { 0 }
        return if (deg == 0) src else rotate(src, deg)
    }

    private fun rotate(b: Bitmap, deg: Int): Bitmap =
        Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)

    private var cropScreen: View? = null

    /** Full-screen square crop: move/resize the frame, rotate, then Done saves the icon. */
    private fun showCropScreen(component: String, source: Bitmap) {
        closeCropScreen()
        openPanel?.cancel()
        val crop = CropView(this).apply { bitmap = source }
        val screen = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            setPadding(systemInsets.left, systemInsets.top, systemInsets.right, systemInsets.bottom)
        }
        val barH = dpi(56f)
        screen.addView(crop, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = barH })
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun button(text: String, onClick: () -> Unit) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setOnClickListener { onClick() }
        }
        bar.addView(button("Cancel") { closeCropScreen(); reopenEditor(component) },
            LinearLayout.LayoutParams(0, -1, 1f))
        bar.addView(button("Rotate") { crop.bitmap?.let { crop.bitmap = rotate(it, 90) } },
            LinearLayout.LayoutParams(0, -1, 1f))
        bar.addView(button("Done") {
            val b = crop.bitmap
            val r = crop.cropRect()
            closeCropScreen()
            if (b != null && r != null) saveCroppedIcon(component, b, r)
        }, LinearLayout.LayoutParams(0, -1, 1f))
        screen.addView(bar, FrameLayout.LayoutParams(-1, barH, Gravity.BOTTOM))
        cropScreen = screen
        root.addView(screen, FrameLayout.LayoutParams(-1, -1))
    }

    private fun closeCropScreen() {
        cropScreen?.let { root.removeView(it) }
        cropScreen = null
    }

    /** Cuts [r] out of [src], scales it to [ICON_PX]², stores it as PNG and applies it. */
    private fun saveCroppedIcon(component: String, src: Bitmap, r: Rect) {
        io.execute {
            val name = try {
                val cut = Bitmap.createBitmap(src, r.left, r.top, r.width(), r.height())
                val side = min(ICON_PX, max(r.width(), r.height()))
                val icon = Bitmap.createScaledBitmap(cut, side, side, true)
                val n = Integer.toHexString(component.hashCode()) + "_" + System.currentTimeMillis() + ".png"
                FileOutputStream(File(customIconDir, n)).use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) }
                n
            } catch (e: Exception) { null }
            main.post {
                if (isDestroyed) return@post
                if (name == null) { toast("Couldn't save the icon"); return@post }
                setCustomIcon(component, "file:$name")
                reopenEditor(component)
            }
        }
    }

    // ================================================================= views for items

    override fun createItemView(item: HomeItem): View = when (item) {
        is AppItem -> AppIconView(this).apply { setOnClickListener { launch(item.component, it) } }
        is FolderItem -> AppIconView(this).apply {
            setOnClickListener { Panels.openFolder(this@LauncherActivity, tag as FolderItem) }
        }
        is WidgetItem -> createWidgetView(item)
    }

    override fun bindItemView(item: HomeItem, view: View) {
        val icon = view as? AppIconView ?: return
        icon.applyHomePalette()
        icon.labelShadow = model.config.labelShadow
        icon.labelShadowStrength = model.config.labelShadowStrength
        icon.showLabel = model.config.showLabels
        icon.labelSizeSp = model.config.labelSize.toFloat()
        icon.labelPosition = model.config.labelPosition
        icon.iconScale = model.config.iconScale / 100f
        icon.shape = model.config.iconShape
        when (item) {
            is AppItem -> { icon.icon = iconFor(item.component); icon.label = labelFor(item.component) }
            is FolderItem -> { bindFolderIcon(item, icon); icon.label = item.title }
            else -> {}
        }
    }

    private fun createWidgetView(item: WidgetItem): View {
        if (item.needsRestore) return restoreView(item)
        val info = widgetManager.getAppWidgetInfo(item.appWidgetId)
            ?: return unavailableView(item)
        val v = try {
            widgetHost.createView(this, item.appWidgetId, info)
        } catch (e: Exception) {
            return unavailableView(item)
        }
        (v as? LauncherWidgetHostView)?.onRetry = { reloadWidget(item) }
        (v as? LauncherWidgetHostView)?.cornerRadiusDp = model.config.widgetCornerRadius
        // Give the provider its real size right away; many widgets only render after this.
        updateWidgetSize(item, v)
        v.post { updateWidgetSize(item, v) }
        return v
    }

    private fun unavailableView(item: WidgetItem) = TextView(this).apply {
        text = "Widget unavailable\nTap to retry · long-press to remove"
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setBackgroundColor(0x44000000)
        setOnClickListener { reloadWidget(item) }
    }

    /** Placeholder for a widget from a backup that hasn't been re-created yet. */
    private fun restoreView(item: WidgetItem) = TextView(this).apply {
        val info = providerInfo(item.provider)
        val name = info?.loadLabel(packageManager)
            ?: ComponentName.unflattenFromString(item.provider)?.packageName?.let { pkg ->
                apps.values.firstOrNull { it.packageName == pkg }?.label ?: pkg
            } ?: "Widget"
        text = if (info != null) "$name\n\nTap to restore\nLong-press to remove"
               else "$name\n\nApp not installed\nLong-press to remove"
        setTextColor(Palette.cardText)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dpi(8f), dpi(8f), dpi(8f), dpi(8f))
        // Opaque card, so icons under the widget's area don't show through the text.
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Palette.card); cornerRadius = dp(20f)
        }
        setOnClickListener {
            skippedRestores.remove(item.id)
            if (providerInfo(item.provider) == null) toast("Install the app first") else restoreWidget(item)
        }
    }

    private fun providerInfo(provider: String): AppWidgetProviderInfo? =
        widgetManager.installedProviders.firstOrNull { it.provider.flattenToString() == provider }

    /** Re-creates the next placeholder whose app is installed (one at a time: binding may need a dialog). */
    private fun restoreNextWidget() {
        if (isDestroyed || pendingWidgetId != -1 || pendingRestoreId != -1L) return
        val next = model.widgets().firstOrNull {
            it.needsRestore && it.id !in skippedRestores && providerInfo(it.provider) != null
        } ?: return
        restoreWidget(next)
    }

    private fun restoreWidget(item: WidgetItem) {
        if (pendingWidgetId != -1 || pendingRestoreId != -1L) return
        val info = providerInfo(item.provider) ?: return
        pendingRestoreId = item.id
        pendingPlacement = null
        addWidget(info)
    }

    /** Throws away a widget's view and creates it again. */
    private fun reloadWidget(item: WidgetItem) {
        workspace.itemViews.remove(item.id)?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        workspace.sync()
    }

    /** Retries widgets that failed to inflate or whose provider was missing (e.g. after an app update). */
    private fun reloadBrokenWidgets() {
        if (isDestroyed) return
        var any = false
        for (w in model.widgets()) {
            val v = workspace.itemViews[w.id]
            val broken = (v is LauncherWidgetHostView && v.failed) || (v != null && v !is LauncherWidgetHostView)
            if (broken) { workspace.itemViews.remove(w.id)?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }; any = true }
        }
        if (any) workspace.sync()
    }

    /** Tells the widget its real size so it can pick a layout. */
    private fun updateWidgetSize(item: WidgetItem, view: View? = workspace.itemViews[item.id]) {
        if (view is LauncherWidgetHostView) {
            view.removePadding = item.removePadding
            view.updateWorkspaceSize(item.w, item.h)
            return
        }
        val v = view as? AppWidgetHostView ?: return
        if (Build.VERSION.SDK_INT >= 31) {
            v.updateAppWidgetSize(Bundle(), listOf(SizeF(item.w, item.h)))
        } else {
            @Suppress("DEPRECATION")
            v.updateAppWidgetSize(null, item.w.toInt(), item.h.toInt(), item.w.toInt(), item.h.toInt())
        }
    }

    // ================================================================= workspace callbacks

    override fun onPageChanged(px: Int, py: Int) {
        val c = model.config
        indicator.update(c.pagesX, c.pagesY, px, py, c.homeX, c.homeY)
    }

    override fun onItemDropped(item: HomeItem, intoFolder: FolderItem?) {
        stopDockEdge()
        if (intoFolder != null && item is AppItem) {
            intoFolder.apps += item.component
            model.items.remove(item)
        }
        saveAndSync()
    }

    override fun onDockDrop(item: HomeItem, rawX: Float, rawY: Float): Boolean {
        stopDockEdge()
        if (!overDock(rawX, rawY)) return false
        dragSourceFolder = null
        dragSourceDrawer = null
        when (item) {
            is AppItem -> moveToDock(item.component, dockIndexAt(rawX))
            is FolderItem -> moveFolderToDock(item, dockIndexAt(rawX))
            else -> return false
        }
        return true
    }

    override fun isDockHover(rawX: Float, rawY: Float): Boolean {
        updateDockEdge(rawX, rawY)
        return overDock(rawX, rawY)
    }

    override fun onEmptyLongPress(x: Float, y: Float) {
        val anchor = View(this)
        dragLayer.addView(anchor, FrameLayout.LayoutParams(1, 1).apply {
            leftMargin = x.toInt(); topMargin = y.toInt()
        })
        anchor.post {
            val pm = PopupMenu(Palette.menuContext(this), anchor)
            val locked = model.config.layoutLocked
            if (!locked) pm.menu.add(0, 1, 0, "Add widget")
            pm.menu.add(0, 2, 1, "Wallpaper")
            pm.menu.add(0, 3, 2, "Home screen settings")
            val c = model.config
            if (workspace.curX == c.homeX && workspace.curY == c.homeY) {
                pm.menu.add(0, 4, 3, "Default page ✓").isEnabled = false
            } else {
                pm.menu.add(0, 4, 3, "Set as default page")
            }
            pm.menu.add(0, 5, 4, if (locked) "Unlock layout" else "Lock layout")
            pm.menu.add(0, 6, 5, "About pLauncher")
            pm.menu.add(0, 7, 6, "App drawer")
            pm.setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> Panels.pickWidget(this)
                    2 -> try {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper"))
                    } catch (_: ActivityNotFoundException) { toast("No wallpaper picker") }
                    3 -> Dialogs.settings(this, model.config) { applySettings() }
                    4 -> setDefaultPage(workspace.curX, workspace.curY)
                    6 -> showPAbout(PAboutInfo(repo = "pLauncher"))
                    7 -> openAppDrawer()
                    5 -> {
                        model.config.layoutLocked = !model.config.layoutLocked
                        save()
                        toast(if (model.config.layoutLocked) "Layout locked" else "Layout unlocked")
                    }
                }
                true
            }
            pm.setOnDismissListener { dragLayer.removeView(anchor) }
            pm.show()
        }
    }

    override fun onItemLongPressReleased(item: HomeItem, view: View) {
        if (item is AppItem) { showAppMenu(item.component, view, item); return }
        val pm = PopupMenu(Palette.menuContext(this), view)
        val m = pm.menu
        when (item) {
            is AppItem -> {}
            is FolderItem -> {
                m.add(0, M_OPEN, 0, "Open"); m.add(0, M_RENAME, 1, "Edit icon & name…")
                if (!isLayoutLocked()) {
                    addIconSizeMenu(m, item, 2)
                    m.add(0, M_FRONT, 2, "Bring to front"); m.add(0, M_BACK, 3, "Send to back")
                    m.add(0, M_UNGROUP, 4, "Ungroup")
                }
            }
            is WidgetItem -> {
                val unlocked = !isLayoutLocked()
                if (unlocked) {
                    m.add(0, M_RESIZE, 0, "Resize / move")
                    m.add(0, M_SIZE, 1, "Set exact size…")
                    m.add(0, M_SNAP, 1, "Fix to grid").apply { isCheckable = true; isChecked = item.snap }
                    m.add(0, M_REMOVE_PADDING, 1, "Remove padding").apply {
                        isCheckable = true; isChecked = item.removePadding
                    }
                    m.add(0, M_FRONT, 2, "Bring to front"); m.add(0, M_BACK, 3, "Send to back")
                }
                val info = widgetManager.getAppWidgetInfo(item.appWidgetId)
                if (info?.configure != null) m.add(0, M_RECONFIGURE, 4, "Widget settings")
                if (unlocked) m.add(0, M_REMOVE, 5, "Remove")
                if (m.size() == 0) { toast("Layout is locked"); return }
            }
        }
        pm.setOnMenuItemClickListener {
            if (!it.hasSubMenu()) onItemMenu(item, view, it.itemId) // header just opens the submenu
            true
        }
        pm.show()
    }

    /** "Icon size" submenu (1× / 1.5× / 2×) for apps and folders on the home screen. */
    private fun addIconSizeMenu(m: android.view.Menu, item: GridItem, order: Int, group: Int = 0) {
        val sub = m.addSubMenu(group, M_SPAN_MENU, order, "Icon size")
        GridItem.SPANS.forEachIndexed { i, span ->
            val name = if (span == 1.5f) "1.5×" else "${span.toInt()}×"
            sub.add(1, M_SPAN_BASE + i, i, name).isChecked = item.span == span
        }
        sub.setGroupCheckable(1, true, true)
    }

    private fun onItemMenu(item: HomeItem, view: View, id: Int) {
        if (id in M_SPAN_BASE until M_SPAN_BASE + GridItem.SPANS.size) {
            (item as? GridItem)?.let { setSpan(it, GridItem.SPANS[id - M_SPAN_BASE]) }
            return
        }
        when (id) {
            M_FRONT -> { model.bringToFront(item); workspace.refreshOrder(item); save() }
            M_BACK -> { model.sendToBack(item); workspace.refreshOrder(item); save() }
            M_FOLDER -> Dialogs.chooseFolder(this, model.folders()) { folder -> moveToFolder(item as AppItem, folder) }
            M_INFO -> openAppInfo((item as AppItem).component)
            M_UNINSTALL -> uninstall((item as AppItem).component)
            M_OPEN -> Panels.openFolder(this, item as FolderItem)
            M_RENAME -> Dialogs.editFolder(this, item as FolderItem)
            M_UNGROUP -> ungroup(item as FolderItem)
            M_RESIZE -> startResize(item as WidgetItem)
            M_SIZE -> {
                val w = item as WidgetItem
                Dialogs.exactSize(this, w.w, w.h) { nw, nh ->
                    val (pw, ph) = workspace.contentSizeDp()
                    w.w = nw.coerceIn(MIN_WIDGET_DP, pw); w.h = nh.coerceIn(MIN_WIDGET_DP, ph)
                    w.x = w.x.coerceAtMost(pw - w.w); w.y = w.y.coerceAtMost(ph - w.h)
                    if (w.snap) w.snapToGrid(pw, ph, config.columns, config.rows)
                    saveAndSync(); updateWidgetSize(w)
                }
            }
            M_SNAP -> {
                val w = item as WidgetItem
                w.snap = !w.snap
                if (w.snap) {
                    val (pw, ph) = workspace.contentSizeDp()
                    w.snapToGrid(pw, ph, config.columns, config.rows)
                }
                saveAndSync(); updateWidgetSize(w)
                toast(if (w.snap) "Widget fixed to grid" else "Widget moves freely")
            }
            M_REMOVE_PADDING -> {
                if (isLayoutLocked()) return
                val w = item as WidgetItem
                w.removePadding = !w.removePadding
                save()
                updateWidgetSize(w)
            }
            M_RECONFIGURE -> try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, (item as WidgetItem).appWidgetId, 0, REQ_RECONFIGURE, null)
            } catch (e: Exception) { toast("Can't open widget settings") }
            M_REMOVE -> {
                val w = item as WidgetItem
                model.items.remove(w)
                if (!w.needsRestore) widgetHost.deleteAppWidgetId(w.appWidgetId)
                saveAndSync()
            }
        }
    }

    /** Changes an app's / folder's icon size; keeps it on the page by moving it up/left if needed. */
    private fun setSpan(item: GridItem, span: Float) {
        val grew = span > item.span
        item.span = span
        if (grew) model.bringToFront(item) // a bigger icon shouldn't hide under its neighbours
        val n = item.cellsCovered
        item.cellX = item.cellX.coerceAtMost((config.columns - n).coerceAtLeast(0))
        item.cellY = item.cellY.coerceAtMost((config.rows - n).coerceAtLeast(0))
        saveAndSync()
    }

    // ================================================================= folders

    /** [folder] null → create a new folder in the app's cell. */
    private fun moveToFolder(app: AppItem, folder: FolderItem?) {
        if (folder == null) {
            Dialogs.rename(this, "Folder", title = "New folder") { name ->
                val f = FolderItem(model.newId(), name, mutableListOf(app.component))
                f.pageX = app.pageX; f.pageY = app.pageY; f.cellX = app.cellX; f.cellY = app.cellY; f.z = app.z
                model.items.remove(app)
                model.items += f
                saveAndSync()
            }
        } else {
            folder.apps += app.component
            model.items.remove(app)
            saveAndSync()
        }
    }

    private fun ungroup(folder: FolderItem) {
        model.items.remove(folder)
        val apps = folder.apps.map { AppItem(model.newId(), it) }
        model.items += apps
        val (w, h) = workspace.contentSizeDp()
        model.placeItems(apps, w, h, workspace.curX, workspace.curY)
        saveAndSync()
    }

    // ================================================================= widgets

    private fun addWidget(info: AppWidgetProviderInfo) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        pendingWidgetInfo = info
        if (widgetManager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)) {
            configureOrAdd()
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_BIND)
            } catch (e: ActivityNotFoundException) {
                abortPendingWidget(); toast("Widget binding not supported")
            }
        }
    }

    private fun configureOrAdd() {
        val info = pendingWidgetInfo ?: return
        val optional = Build.VERSION.SDK_INT >= 31 &&
                (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) != 0
        if (info.configure != null && !optional) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, pendingWidgetId, 0, REQ_CONFIGURE, null)
                return
            } catch (e: Exception) { /* fall through and add unconfigured */ }
        }
        completeAddWidget()
    }

    private fun completeAddWidget() {
        val info = pendingWidgetInfo ?: widgetManager.getAppWidgetInfo(pendingWidgetId)
        val id = pendingWidgetId
        pendingWidgetId = -1; pendingWidgetInfo = null
        val restoreId = pendingRestoreId
        pendingRestoreId = -1L
        if (info == null || id == -1) return
        if (restoreId != -1L) {
            // Restoring a backup placeholder: same item, place and size, new widget id.
            val target = model.widgets().firstOrNull { it.id == restoreId && it.needsRestore }
            if (target == null) {
                widgetHost.deleteAppWidgetId(id)
            } else {
                target.appWidgetId = id
                workspace.itemViews.remove(target.id)?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
                saveAndSync()
            }
            main.post { restoreNextWidget() }
            return
        }
        val d = resources.displayMetrics.density
        val (pw, ph) = workspace.contentSizeDp()
        val item = WidgetItem(model.newId(), id, info.provider.flattenToString())
        item.snap = config.snapNewWidgets
        item.w = (info.minWidth / d).coerceAtLeast(110f).coerceAtMost(pw)
        item.h = (info.minHeight / d).coerceAtLeast(MIN_WIDGET_DP).coerceAtMost(ph)
        val place = pendingPlacement
        pendingPlacement = null
        if (place != null) {
            // Dropped from the widget picker: use the drop position and size.
            item.w = place.w.coerceIn(MIN_WIDGET_DP, pw); item.h = place.h.coerceIn(MIN_WIDGET_DP, ph)
            item.x = place.x.coerceIn(0f, pw - item.w); item.y = place.y.coerceIn(0f, ph - item.h)
            item.pageX = place.pageX; item.pageY = place.pageY
        } else {
            item.x = (pw - item.w) / 2; item.y = (ph - item.h) / 2
            item.pageX = workspace.curX; item.pageY = workspace.curY
        }
        if (item.snap) item.snapToGrid(pw, ph, config.columns, config.rows)
        model.bringToFront(item)
        model.items += item
        saveAndSync()
    }

    private fun abortPendingWidget() {
        if (pendingWidgetId != -1) widgetHost.deleteAppWidgetId(pendingWidgetId)
        pendingWidgetId = -1; pendingWidgetInfo = null; pendingPlacement = null
        if (pendingRestoreId != -1L) {
            // Declined/cancelled: keep the placeholder (tap it to retry), go on with the others.
            skippedRestores += pendingRestoreId
            pendingRestoreId = -1L
            main.post { restoreNextWidget() }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_BIND -> if (resultCode == RESULT_OK) configureOrAdd() else abortPendingWidget()
            REQ_CONFIGURE -> if (resultCode == RESULT_OK) completeAddWidget() else abortPendingWidget()
            REQ_ICON_IMAGE -> {
                val uri = data?.data
                if (resultCode == RESULT_OK && uri != null) onIconImagePicked(uri) else pendingIconComponent = null
            }
            REQ_EXPORT -> data?.data?.takeIf { resultCode == RESULT_OK }?.let { writeBackup(it) }
            REQ_IMPORT -> data?.data?.takeIf { resultCode == RESULT_OK }?.let { confirmImport(it) }
        }
    }

    private fun cleanupOrphanWidgets() {
        val known = model.widgets().mapTo(HashSet()) { it.appWidgetId }
        for (id in widgetHost.appWidgetIds) {
            if (id !in known && id != pendingWidgetId) widgetHost.deleteAppWidgetId(id)
        }
    }

    private fun removeDeadWidgets() {
        // Placeholders stay until restored or removed by the user (their app may be installed later).
        val dead = model.widgets().filter { !it.needsRestore && widgetManager.getAppWidgetInfo(it.appWidgetId) == null }
        for (w in dead) { model.items.remove(w); widgetHost.deleteAppWidgetId(w.appWidgetId) }
    }

    // ---------------------------------------------------------------- free resize

    private fun startResize(item: WidgetItem) {
        finishResize()
        val v = workspace.itemViews[item.id] ?: return
        val page = v.parent as? PageView ?: return
        workspace.snapTo(item.pageX, item.pageY)
        val d = resources.displayMetrics.density
        val initial = Rect(v.left - page.paddingLeft, v.top - page.paddingTop,
            v.right - page.paddingLeft, v.bottom - page.paddingTop)
        val frame = ResizeFrame(this, initial,
            onChange = { r ->
                item.x = r.left / d; item.y = r.top / d
                item.w = r.width() / d; item.h = r.height() / d
                page.requestLayout()
            },
            onDone = { finishResize() },
            cellW = if (item.snap) page.cellW else 0f,
            cellH = if (item.snap) page.cellH else 0f)
        resizeFrame = frame
        resizeItem = item
        workspace.locked = true
        page.addView(frame)
    }

    private fun finishResize() {
        val frame = resizeFrame ?: return
        (frame.parent as? PageView)?.removeView(frame)
        resizeFrame = null
        workspace.locked = false
        resizeItem?.let {
            if (it.snap) {
                // Clean up px → dp rounding so the widget sits exactly on cell lines.
                val (pw, ph) = workspace.contentSizeDp()
                it.snapToGrid(pw, ph, config.columns, config.rows)
                workspace.pageAt(it.pageX, it.pageY)?.requestLayout()
            }
            updateWidgetSize(it)
        }
        resizeItem = null
        save()
    }

    // ================================================================= misc actions

    private fun applySettings() {
        applyColorMode()
        updateHomeChrome()
        for (wi in model.widgets()) {
            (workspace.itemViews[wi.id] as? LauncherWidgetHostView)?.cornerRadiusDp = model.config.widgetCornerRadius
        }
        val (w, h) = workspace.contentSizeDp()
        model.normalize(w, h)
        // Grid-fixed widgets follow the new grid.
        if (model.snapWidgets(w, h)) for (wi in model.widgets()) if (wi.snap) updateWidgetSize(wi)
        root.requestApplyInsets()
        saveAndSync()
    }

    /** Day/night: launcher colours, dialogs/menus and status/navigation bar icon colour. */
    private fun applyColorMode() {
        Palette.night = Palette.resolve(this, model.config.colorMode)
        val lightBars = !Palette.night
        if (Build.VERSION.SDK_INT >= 30) {
            val flags = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (lightBars) flags else 0, flags)
        } else {
            @Suppress("DEPRECATION")
            var v = window.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            v = if (lightBars) v or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                else v and (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = v
        }
        indicator.invalidate()
        rebuildDock()
        x.vladgba.launcher.search.SearchWidget.refreshAll(this, Palette.night)
    }

    /** uiMode is in configChanges, so a system day/night switch lands here instead of recreating. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (model.config.colorMode == "system") {
            val was = Palette.night
            applyColorMode()
            if (was != Palette.night) { openPanel?.cancel(); workspace.sync() }
        }
    }

    /** Page that Home returns to (and that pLauncher opens on). */
    private fun setDefaultPage(px: Int, py: Int) {
        model.config.homeX = px
        model.config.homeY = py
        save()
        onPageChanged(workspace.curX, workspace.curY)
        toast("Default page set (${px + 1}, ${py + 1})")
    }

    // ================================================================= panels & external drags

    fun pageContentSizeDp() = workspace.contentSizeDp()

    fun showPanel(panel: DragSourcePanel) {
        openPanel?.cancel()
        val prev = panel.onClosed
        panel.onClosed = { prev(); if (openPanel === panel) openPanel = null }
        openPanel = panel
        root.addView(panel, root.indexOfChild(dragLayer), FrameLayout.LayoutParams(-1, -1))
    }

    /** Long-press-drag of an app out of an open folder panel. */
    fun startFolderAppDrag(folder: FolderItem, component: String, rawX: Float, rawY: Float): Boolean {
        if (isLayoutLocked()) { toast("Layout is locked"); return false }
        val page = workspace.currentPage ?: return false
        val item = AppItem(model.newId(), component)
        val v = createItemView(item)
        v.tag = item
        bindItemView(item, v)
        dragSourceFolder = folder
        val ok = workspace.beginExternalDrag(item, v, page.cellW.toInt(), page.cellH.toInt(), rawX, rawY)
        if (!ok) dragSourceFolder = null
        return ok
    }

    fun startDrawerAppDrag(component: String, rawX: Float, rawY: Float): Boolean {
        if (isLayoutLocked()) { toast("Layout is locked"); return false }
        val page = workspace.currentPage ?: return false
        val item = AppItem(model.newId(), component)
        val view = createItemView(item)
        view.tag = item
        bindItemView(item, view)
        dragSourceDrawer = component
        val ok = workspace.beginExternalDrag(item, view, page.cellW.toInt().coerceAtLeast(1),
            page.cellH.toInt().coerceAtLeast(1), rawX, rawY)
        if (!ok) dragSourceDrawer = null
        return ok
    }

    /** Long-press-drag of a widget preview out of the widget picker. */
    fun startWidgetDrag(info: AppWidgetProviderInfo, preview: View, rawX: Float, rawY: Float): Boolean {
        val (w, h) = Panels.defaultWidgetSizeDp(this, info)
        val d = resources.displayMetrics.density
        val item = WidgetItem(-1, -1, info.provider.flattenToString()).apply { this.w = w; this.h = h }
        preview.tag = item
        dragWidgetInfo = info
        val ok = workspace.beginExternalDrag(item, preview, (w * d).toInt(), (h * d).toInt(), rawX, rawY)
        if (!ok) dragWidgetInfo = null
        return ok
    }

    fun externalDragMove(rawX: Float, rawY: Float) = workspace.externalDragMove(rawX, rawY)
    fun externalDragEnd(cancelled: Boolean) = workspace.externalDragEnd(cancelled)

    override fun onExternalDrop(item: HomeItem, intoFolder: FolderItem?, cancelled: Boolean) {
        stopDockEdge()
        val source = dragSourceFolder
        val dockSource = dragSourceDock
        val drawerSource = dragSourceDrawer
        val widgetInfo = dragWidgetInfo
        dragSourceFolder = null; dragSourceDock = null; dragSourceDrawer = null; dragWidgetInfo = null

        if (item is AppItem && drawerSource != null) {
            if (cancelled) return
            removeAppPlacement(drawerSource)
            model.hidden.remove(drawerSource)
            model.drawerOnly.remove(drawerSource)
            if (intoFolder != null && intoFolder in model.items) intoFolder.apps += drawerSource
            else { model.bringToFront(item); model.items += item }
            saveAndSync()
            rebuildDock()
            return
        }

        if (item is FolderItem && dockSource != null) {
            if (cancelled) return
            val idx = model.dock.indexOf(dockSource)
            if (idx >= 0) model.dock[idx] = ""
            model.dockFolders.removeAll { it.id == item.id }
            trimDock()
            model.bringToFront(item); model.items += item
            saveAndSync()
            rebuildDock()
            return
        }

        if (item is AppItem && dockSource != null) {
            if (cancelled) return
            val idx = model.dock.indexOf(dockSource)
            if (idx >= 0) model.dock[idx] = ""
            trimDock()
            if (intoFolder != null) intoFolder.apps += dockSource
            else { model.bringToFront(item); model.items += item }
            saveAndSync()
            rebuildDock()
            return
        }

        if (item is AppItem && source != null) {
            if (cancelled || intoFolder === source) return // stays in its folder
            source.apps.remove(item.component)
            if (intoFolder != null) {
                intoFolder.apps += item.component
            } else {
                model.bringToFront(item)
                model.items += item
            }
            if (source.apps.isEmpty()) model.items.remove(source)
            saveAndSync()
        } else if (item is WidgetItem && widgetInfo != null) {
            if (cancelled) return
            pendingPlacement = item
            addWidget(widgetInfo)
        }
    }

    // ================================================================= app menu & shortcuts

    /**
     * Long-press menu for an app: its shortcuts first, then actions.
     * [homeItem] is set for icons on the home screen (adds z-order / folder / gestures).
     */
    fun showAppMenu(component: String, anchor: View, homeItem: AppItem?, onAction: () -> Unit = {}) {
        val pkg = ComponentName.unflattenFromString(component)?.packageName ?: return
        val pm = PopupMenu(Palette.menuContext(this), anchor)
        val m = pm.menu
        val list = shortcuts.forPackage(pkg).take(MAX_MENU_SHORTCUTS)
        list.forEachIndexed { i, sc ->
            m.add(1, M_SHORTCUT_BASE + i, i, shortcuts.label(sc)).icon = shortcuts.menuIcon(sc)
        }
        if (Build.VERSION.SDK_INT >= 29 && list.isNotEmpty()) pm.setForceShowIcon(true)
        var o = 100
        val unlocked = !isLayoutLocked()
        if (homeItem != null) {
            if (unlocked) {
                addIconSizeMenu(m, homeItem, o++, group = 2)
                m.add(2, M_FRONT, o++, "Bring to front"); m.add(2, M_BACK, o++, "Send to back")
                m.add(2, M_FOLDER, o++, "Move to folder…")
            }
            m.add(2, M_GESTURES, o++, "Swipe gestures…")
        }
        m.add(2, M_EDIT, o++, "Edit icon & name…")
        m.add(2, M_INFO, o++, "App info")
        if (unlocked) {
            m.add(2, M_DOCK, o++, if (component in model.dock) "Move to home" else "Move to dock")
            m.add(2, M_DRAWER, o++, if (component in model.drawerOnly || component in model.hidden) "Move to home" else "Move to app drawer")
            m.add(2, M_HIDE, o++, if (component in model.hidden) "Unhide app" else "Hide app")
        }
        m.add(2, M_UNINSTALL, o, "Uninstall")
        pm.setOnMenuItemClickListener { mi ->
            if (mi.hasSubMenu()) return@setOnMenuItemClickListener false // let it open
            val id = mi.itemId
            if (id >= M_SHORTCUT_BASE) {
                val sc = list[id - M_SHORTCUT_BASE]
                startShortcut(sc.`package`, sc.id, anchor)
            } else when (id) {
                M_INFO -> openAppInfo(component)
                M_UNINSTALL -> uninstall(component)
                M_EDIT -> Dialogs.editApp(this, component)
                M_DOCK -> if (component in model.dock) moveFromDock(component) else moveToDock(component)
                M_DRAWER -> if (component in model.drawerOnly || component in model.hidden) moveToHome(component) else moveToDrawer(component)
                M_HIDE -> {
                    if (component in model.hidden) setHidden(model.hidden - component)
                    else setHidden(model.hidden + component)
                }
                M_GESTURES -> Dialogs.gestures(this, component)
                else -> homeItem?.let { onItemMenu(it, anchor, id) }
            }
            onAction()
            true
        }
        pm.show()
    }

    fun startShortcut(pkg: String, id: String, from: View?) {
        val bounds = from?.let { v ->
            val l = IntArray(2); v.getLocationOnScreen(l); Rect(l[0], l[1], l[0] + v.width, l[1] + v.height)
        }
        val opts = from?.let { ActivityOptions.makeScaleUpAnimation(it, 0, 0, it.width, it.height).toBundle() }
        if (!shortcuts.start(pkg, id, bounds, opts)) toast("Can't open shortcut")
    }

    // ================================================================= icon gestures

    override fun isLayoutLocked() = model.config.layoutLocked

    override fun hasIconGesture(item: AppItem, dir: String) = model.gesture(item.component, dir) != null

    override fun onIconGesture(item: AppItem, dir: String, view: View) {
        model.gesture(item.component, dir)?.let { runGesture(it, view) }
    }

    fun gestureFor(component: String, dir: String) = model.gesture(component, dir)

    fun setGesture(component: String, dir: String, spec: String?) {
        model.setGesture(component, dir, spec)
        save()
    }

    /** Executes "app:…", "shortcut:pkg|id" or "sys:…". */
    fun runGesture(spec: String, from: View?) {
        val kind = spec.substringBefore(':')
        val arg = spec.substringAfter(':')
        when (kind) {
            "app" -> if (from != null) launch(arg, from) else launch(arg, root)
            "shortcut" -> startShortcut(arg.substringBefore('|'), arg.substringAfter('|'), from)
            "sys" -> HomeActions.perform(this, HomeAction.of(arg))
        }
    }

    /** Human-readable description of a gesture spec. */
    fun describeGesture(spec: String?): String {
        if (spec == null) return "Nothing"
        val arg = spec.substringAfter(':')
        return when (spec.substringBefore(':')) {
            "app" -> "Open " + labelFor(arg)
            "shortcut" -> {
                val pkg = arg.substringBefore('|')
                val sc = shortcuts.find(pkg, arg.substringAfter('|'))
                val app = apps.values.firstOrNull { it.packageName == pkg }?.label ?: pkg
                if (sc != null) "$app: ${shortcuts.label(sc)}" else "$app shortcut (unavailable)"
            }
            "sys" -> HomeAction.of(arg).label
            else -> "Nothing"
        }
    }

    // ================================================================= hidden apps

    val hiddenApps: Set<String> get() = model.hidden
    val drawerOnlyApps: Set<String> get() = model.drawerOnly

    /**
     * Applies a new hidden set: newly hidden apps leave the home screen and folders
     * (empty folders are removed); unhidden apps go to free cells starting at the current page.
     */
    fun setHidden(newHidden: Set<String>) {
        val added = newHidden - model.hidden
        val removed = model.hidden - newHidden
        model.hidden.clear()
        model.hidden += newHidden

        if (added.isNotEmpty()) {
            model.dock.removeAll(added)
            model.items.removeAll { it is AppItem && it.component in added }
            for (f in model.folders()) {
                f.apps.removeAll { it in added }
                if (f.apps.isEmpty()) model.items.remove(f)
            }
        }
        val back = removed.filter { it in apps && it !in model.dock && it !in model.drawerOnly }.map { AppItem(model.newId(), it) }
        if (back.isNotEmpty()) {
            model.items += back
            val (w, h) = workspace.contentSizeDp()
            model.placeItems(back, w, h, workspace.curX, workspace.curY)
        }
        saveAndSync()
        rebuildDock()
    }

    private fun openAppInfo(component: String) {
        val pkg = ComponentName.unflattenFromString(component)?.packageName ?: return
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun uninstall(component: String) {
        val pkg = ComponentName.unflattenFromString(component)?.packageName ?: return
        try {
            @Suppress("DEPRECATION")
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
        } catch (e: Exception) { toast("Can't uninstall") }
    }

    fun save() {
        pruneFolderIcons()
        store.save(model)
    }

    // ---------------------------------------------------------------- backup: export / import

    /** Asks where to save a backup of layout, settings, hidden apps, labels, gestures and custom icons. */
    fun exportSettings() {
        save()
        val name = "pLauncher-backup-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
            .format(java.util.Date()) + ".json"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, name)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_EXPORT)
        } catch (e: ActivityNotFoundException) { toast("No file picker available") }
    }

    fun importSettings() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_IMPORT)
        } catch (e: ActivityNotFoundException) { toast("No file picker available") }
    }

    private fun writeBackup(uri: Uri) {
        // Widget geometry also goes out as page fractions, so it fits another screen size.
        val (pw, ph) = workspace.contentSizeDp()
        val json = model.toJson(pw, ph)
        io.execute {
            val ok = try {
                val icons = org.json.JSONObject()
                customIconDir.listFiles()?.forEach { f ->
                    icons.put(f.name, android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP))
                }
                val root = org.json.JSONObject()
                    .put("format", BACKUP_FORMAT).put("formatVersion", 1)
                    .put("workspace", json).put("iconFiles", icons)
                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(root.toString().toByteArray(Charsets.UTF_8)) }
                true
            } catch (e: Exception) { false }
            main.post { toast(if (ok) "Settings exported" else "Export failed") }
        }
    }

    private fun confirmImport(uri: Uri) {
        android.app.AlertDialog.Builder(Palette.ui(this), Palette.dialogTheme)
            .setTitle("Import settings?")
            .setMessage("This replaces the current layout, settings, hidden apps and custom icons. " +
                    "Widgets are re-added in their places: Android may ask to allow each one " +
                    "(tick \"Always allow\" to skip the rest), and some widgets need setting up again.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Import") { _, _ -> readBackup(uri) }
            .show()
    }

    private fun readBackup(uri: Uri) {
        io.execute {
            val result = try {
                val text = contentResolver.openInputStream(uri)!!.use { String(it.readBytes(), Charsets.UTF_8) }
                val root = org.json.JSONObject(text)
                require(root.optString("format") == BACKUP_FORMAT) { "Not a pLauncher backup" }
                val ws = root.getJSONObject("workspace")
                val imported = WorkspaceModel.fromJson(ws) // validate before touching anything
                Pair(imported, root.optJSONObject("iconFiles"))
            } catch (e: Exception) { null }
            main.post {
                if (isDestroyed) return@post
                if (result == null) { toast("Not a valid pLauncher backup"); return@post }
                applyBackup(result.first, result.second)
            }
        }
    }

    private fun applyBackup(imported: WorkspaceModel, iconFiles: org.json.JSONObject?) {
        val (pw, ph) = workspace.contentSizeDp()
        if (!pw.isFinite() || !ph.isFinite() || pw < 1f || ph < 1f) {
            toast("Page size is not available; try importing again")
            return
        }
        // Widget ids belong to one install. Ids our host still owns are kept as they are;
        // the rest become placeholders that are re-created (bound again) after the restart.
        val ownIds = widgetHost.appWidgetIds.toHashSet()
        for (w in imported.widgets()) if (w.appWidgetId !in ownIds) w.appWidgetId = WidgetItem.NO_WIDGET_ID
        // Scale widgets to this screen (backups store them as page fractions) and re-fit
        // grid-fixed ones to the imported grid. Legacy dp geometry is clamped to the page.
        val cfg = imported.config
        for (w in imported.widgets()) w.fitToPage(pw, ph, cfg.columns, cfg.rows)

        customIconDir.listFiles()?.forEach { it.delete() }
        customIconCache.clear()
        iconFiles?.let { icons ->
            for (name in icons.keys()) {
                if (name.contains('/') || name.contains("..")) continue // stay inside the folder
                try {
                    File(customIconDir, name).writeBytes(android.util.Base64.decode(icons.getString(name), android.util.Base64.DEFAULT))
                } catch (_: Exception) { }
            }
        }
        // Drop icon specs whose file didn't come along.
        imported.customIcons.entries.removeAll { (_, spec) ->
            spec.startsWith("file:") && !File(customIconDir, spec.removePrefix("file:")).exists()
        }
        store.save(imported)
        toast("Settings imported")
        recreate() // reload everything from the saved file
    }

    // ---------------------------------------------------------------- folder icon & name

    fun folderIconKey(folder: FolderItem) = "folder:${folder.id}"

    /** Custom icon if set, otherwise the folder-style 2×2 preview of its apps. */
    fun bindFolderIcon(folder: FolderItem, view: AppIconView) {
        val custom = customIcon(folderIconKey(folder))
        if (custom != null) {
            view.folderIcons = null
            view.icon = custom
        } else {
            view.folderIcons = folder.apps.mapNotNull { iconFor(it) }
        }
    }

    fun renameFolder(folder: FolderItem, name: String) {
        folder.title = name.ifBlank { "Folder" }
        saveAndSync()
    }

    /** Re-opens the icon & name editor for an app component or a folder key. */
    fun reopenEditor(key: String) {
        if (key.startsWith("folder:")) {
            val id = key.removePrefix("folder:").toLongOrNull()
            model.folders().firstOrNull { it.id == id }?.let { Dialogs.editFolder(this, it) }
        } else {
            Dialogs.editApp(this, key)
        }
    }

    /** Drops icon overrides of folders that no longer exist (and their image files). */
    private fun pruneFolderIcons() {
        val live = model.folders().mapTo(HashSet()) { folderIconKey(it) }
        val dead = model.customIcons.keys.filter { it.startsWith("folder:") && it !in live }
        for (k in dead) {
            val spec = model.customIcons.remove(k) ?: continue
            if (spec.startsWith("file:")) {
                customIconCache.remove(spec)
                File(customIconDir, spec.removePrefix("file:")).delete()
            }
        }
    }

    fun saveAndSync() {
        save()
        workspace.sync()
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val WIDGET_HOST_ID = 0x0A11
        private const val REQ_BIND = 11
        private const val REQ_CONFIGURE = 12
        private const val REQ_RECONFIGURE = 13
        private const val STATE_PENDING_WIDGET = "pending_widget"
        private const val STATE_PLACEMENT = "pending_placement"
        private const val STATE_RESTORE = "pending_restore"
        private const val MAX_MENU_SHORTCUTS = 5
        private const val M_GESTURES = 13
        private const val M_HIDE = 14
        private const val M_EDIT = 15
        private const val M_DOCK = 16
        private const val M_DRAWER = 17
        private const val REQ_ICON_IMAGE = 14
        private const val REQ_EXPORT = 15
        private const val REQ_IMPORT = 16
        private const val BACKUP_FORMAT = "sheaf-backup"
        private const val STATE_ICON_COMPONENT = "pending_icon_component"
        private const val ICON_PX = 256
        private const val CROP_MAX_PX = 2048
        private const val M_SHORTCUT_BASE = 1000
        const val MIN_WIDGET_DP = 40f
        private const val M_SNAP = 20
        private const val M_SPAN_MENU = 21
        private const val M_REMOVE_PADDING = 22
        /** Icon size items: M_SPAN_BASE + index into GridItem.SPANS (must stay below M_SHORTCUT_BASE). */
        private const val M_SPAN_BASE = 900

        private const val M_FRONT = 1; private const val M_BACK = 2; private const val M_FOLDER = 3
        private const val M_INFO = 4; private const val M_UNINSTALL = 5; private const val M_OPEN = 6
        private const val M_RENAME = 7; private const val M_UNGROUP = 8; private const val M_RESIZE = 9
        private const val M_SIZE = 10; private const val M_RECONFIGURE = 11; private const val M_REMOVE = 12
    }
}
