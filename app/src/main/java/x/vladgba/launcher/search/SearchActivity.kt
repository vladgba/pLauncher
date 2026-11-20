package x.vladgba.launcher.search

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import x.vladgba.launcher.R
import x.vladgba.launcher.model.AppInfo
import x.vladgba.launcher.model.AppRepository
import x.vladgba.launcher.model.LauncherConfig
import x.vladgba.launcher.model.WorkspaceStore
import x.vladgba.launcher.pui.PUi
import x.vladgba.launcher.pui.pColor
import x.vladgba.launcher.pui.pDpi
import x.vladgba.launcher.pui.pPadForSystemBars
import x.vladgba.launcher.pui.pShape
import x.vladgba.launcher.ui.Palette

/**
 * Type-to-find overlay for installed apps, opened by the search widgets. The window is
 * translucent, so the home screen stays visible behind a dim layer; tapping the dim area
 * or pressing Back closes it. Bar position (top/bottom) and size (big/compact) come from
 * the launcher settings. Results update per keystroke; tapping one (or the keyboard's
 * search key) launches it.
 */
class SearchActivity : Activity() {
    private lateinit var cfg: LauncherConfig
    private var allApps: List<AppInfo> = emptyList()
    /** Best match first. */
    private var matches: List<AppInfo> = emptyList()
    /** Display order: with the bar at the bottom the best match sits next to it. */
    private var shown: List<AppInfo> = emptyList()
    private lateinit var input: EditText
    private lateinit var empty: TextView
    private lateinit var list: ListView
    private val adapter = ResultsAdapter()
    private val io = Executors.newSingleThreadExecutor()

    private val barAtBottom get() = cfg.searchBar == "bottom"
    private val big get() = cfg.searchMode == "big"
    private val iconDp get() = if (big) 48 else 32
    private val rowPadDp get() = if (big) 10 else 6
    private val rowHeightDp get() = iconDp + rowPadDp * 2

    override fun attachBaseContext(newBase: Context) {
        // Follow the launcher's own color mode (system / day / night), not just the system's.
        cfg = WorkspaceStore(newBase).load().config
        Palette.night = Palette.resolve(newBase, cfg.colorMode)
        val conf = Configuration(newBase.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (Palette.night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        }
        super.attachBaseContext(newBase.createConfigurationContext(conf))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply {
            setBackgroundColor(if (big) 0x99000000.toInt() else pColor(R.color.p_scrim))
            setOnClickListener { finish() }
        }

        input = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(pColor(R.color.p_text))
            setHintTextColor(pColor(R.color.p_text3))
            textSize = if (big) 18f else 16f
            background = pShape(pColor(R.color.p_surface2), PUi.RADIUS_PILL, ripple = false)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_search_dim, 0, 0, 0)
            compoundDrawablePadding = pDpi(10)
            val v = if (big) 14 else 10
            setPadding(pDpi(16), pDpi(v), pDpi(16), pDpi(v))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = refilter()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) { matches.firstOrNull()?.let(::open); true } else false
            }
        }

        empty = TextView(this).apply {
            text = getString(R.string.search_no_results)
            setTextColor(pColor(R.color.p_text2))
            gravity = Gravity.CENTER
            setPadding(0, pDpi(24), 0, pDpi(24))
            visibility = View.GONE
        }

        list = ListView(this).apply {
            divider = null
            isStackFromBottom = barAtBottom
            adapter = this@SearchActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> open(shown[pos]) }
            // Dragging the list hides the keyboard so more results are visible.
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: AbsListView?, state: Int) {
                    if (state == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) hideKeyboard()
                }
                override fun onScroll(v: AbsListView?, first: Int, visible: Int, total: Int) {}
            })
        }

        val card = pColor(R.color.p_surface)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pShape(
                if (big) (card and 0x00FFFFFF) or 0xF2000000.toInt() else card, PUi.RADIUS_L, ripple = false)
            setPadding(pDpi(10), pDpi(10), pDpi(10), pDpi(10))
            isClickable = true // swallow taps so they don't close the overlay
            val listParams = if (big) LinearLayout.LayoutParams(-1, 0, 1f) else LinearLayout.LayoutParams(-1, 0)
            val gap = pDpi(8)
            if (barAtBottom) {
                addView(empty, LinearLayout.LayoutParams(-1, -2))
                addView(list, listParams.apply { bottomMargin = gap })
                addView(input, LinearLayout.LayoutParams(-1, -2))
            } else {
                addView(input, LinearLayout.LayoutParams(-1, -2))
                addView(empty, LinearLayout.LayoutParams(-1, -2))
                addView(list, listParams.apply { topMargin = gap })
            }
        }
        root.addView(panel, FrameLayout.LayoutParams(-1, if (big) -1 else -2,
            if (barAtBottom) Gravity.BOTTOM else Gravity.TOP).apply {
            val m = pDpi(12); setMargins(m, m, m, m)
        })
        setContentView(root)
        root.pPadForSystemBars()
        focusSearchBar()
        refilter()

        io.execute {
            val apps = try { AppRepository(this).query(cfg.effectiveCompression, cfg.colorDepth, null) } catch (_: Exception) { emptyList() }
            runOnUiThread { if (!isDestroyed) { allApps = apps; refilter() } }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Widget tapped again while open: start over with a clean field.
        input.setText("")
        focusSearchBar()
    }

    override fun onDestroy() {
        allApps = emptyList()
        matches = emptyList()
        shown = emptyList()
        if (cfg.aggressiveMemory || cfg.iconCompression > 1) {
            AppRepository.releaseIcons()
            System.gc()
        }
        io.shutdown()
        super.onDestroy()
    }

    private fun refilter() {
        val q = input.text.toString()
        matches = SearchMatcher.filterApps(allApps, q)
        shown = if (barAtBottom) matches.asReversed() else matches
        val noHits = q.isNotBlank() && allApps.isNotEmpty() && matches.isEmpty()
        empty.visibility = if (noHits) View.VISIBLE else View.GONE
        if (!big) {
            // Compact: the card hugs its content, at most five rows tall.
            list.layoutParams = list.layoutParams.apply {
                height = pDpi(rowHeightDp) * minOf(shown.size, MAX_COMPACT_ROWS)
            }
            list.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        }
        adapter.notifyDataSetChanged()
    }

    private fun focusSearchBar() {
        input.requestFocus()
        input.post {
            getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun open(app: AppInfo) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.componentName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
            finish()
        } catch (_: Exception) {
            Toast.makeText(this, "Can't open app", Toast.LENGTH_SHORT).show()
        }
    }

    private inner class ResultsAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@SearchActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val p = pDpi(rowPadDp)
                setPadding(p, p, p, p)
                background = pShape(0, PUi.RADIUS_M)
                addView(ImageView(context), LinearLayout.LayoutParams(pDpi(iconDp), pDpi(iconDp)))
                addView(TextView(context).apply {
                    textSize = if (big) 18f else 15f
                    setTextColor(pColor(R.color.p_text))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = pDpi(14) })
            }
            val app = shown[position]
            if (app.icon === AppRepository.placeholder) {
                AppRepository(this@SearchActivity).loadIcon(app.component, cfg.effectiveCompression, cfg.colorDepth)?.let {
                    app.icon = it
                }
            }
            (row.getChildAt(0) as ImageView).setImageDrawable(app.icon)
            (row.getChildAt(1) as TextView).text = app.label
            return row
        }
    }

    private companion object {
        const val MAX_COMPACT_ROWS = 5
    }
}
