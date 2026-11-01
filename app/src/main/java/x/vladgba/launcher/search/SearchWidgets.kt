package x.vladgba.launcher.search

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import x.vladgba.launcher.R
import x.vladgba.launcher.model.WorkspaceStore
import x.vladgba.launcher.ui.Palette

/**
 * Home-screen widgets that open [SearchActivity]. A widget cannot host a text field
 * (RemoteViews has no EditText), so the bar is a tappable look-alike that opens the
 * search screen with the keyboard already up.
 *
 * Colours are chosen here from the launcher's own day/night mode (system / day / night) and
 * painted with fixed-colour drawables, because resources resolved by the widget host do not
 * reliably follow it. The launcher calls [refreshAll] whenever that mode changes.
 */
abstract class SearchWidget(private val layout: Int) : AppWidgetProvider() {
    protected abstract fun paint(views: RemoteViews, night: Boolean)

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val night = Palette.resolve(context, WorkspaceStore(context).load().config.colorMode)
        update(context, manager, appWidgetIds, night)
    }

    fun update(context: Context, manager: AppWidgetManager, ids: IntArray, night: Boolean) {
        val intent = Intent(context, SearchActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        for (id in ids) {
            val views = RemoteViews(context.packageName, layout)
            views.setOnClickPendingIntent(R.id.search_root, pending)
            paint(views, night)
            manager.updateAppWidget(id, views)
        }
    }

    companion object {
        /** Repaints every placed search widget for [night]. Safe to call often. */
        fun refreshAll(context: Context, night: Boolean) {
            val manager = AppWidgetManager.getInstance(context)
            for (provider in listOf(SearchBarWidget(), SearchButtonWidget())) {
                val ids = manager.getAppWidgetIds(ComponentName(context, provider.javaClass))
                if (ids.isNotEmpty()) provider.update(context, manager, ids, night)
            }
        }
    }
}

/** Full-width pill that reads "Search apps". */
class SearchBarWidget : SearchWidget(R.layout.widget_search_bar) {
    override fun paint(views: RemoteViews, night: Boolean) {
        views.setInt(R.id.search_body, "setBackgroundResource",
            if (night) R.drawable.widget_search_bar_bg_night else R.drawable.widget_search_bar_bg_day)
        views.setImageViewResource(R.id.search_icon,
            if (night) R.drawable.ic_search_dim_night else R.drawable.ic_search_dim_day)
        views.setTextColor(R.id.search_text, if (night) 0xFFA3A7B3.toInt() else 0xFF5F6370.toInt())
    }
}

/** Round accent button with a magnifier. */
class SearchButtonWidget : SearchWidget(R.layout.widget_search_button) {
    override fun paint(views: RemoteViews, night: Boolean) {
        views.setImageViewResource(R.id.search_icon,
            if (night) R.drawable.ic_search_button_night else R.drawable.ic_search_button_day)
    }
}
