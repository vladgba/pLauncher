package x.vladgba.launcher.ui

import android.content.Context
import android.view.MotionEvent
import android.widget.HorizontalScrollView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Scrolls dock icons one visible page at a time. */
class DockPager(context: Context) : HorizontalScrollView(context) {
    private var flung = false

    override fun fling(velocityX: Int) {
        if (width <= 0) return
        flung = true
        val page = when {
            velocityX > 400 -> (scrollX / width) + 1
            velocityX < -400 -> (scrollX / width)
            else -> (scrollX.toFloat() / width).roundToInt()
        }.coerceIn(0, ((getChildAt(0)?.width ?: width) / width - 1).coerceAtLeast(0))
        smoothScrollTo(page * width, 0)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) flung = false
        val handled = super.onTouchEvent(event)
        if ((event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) && !flung) {
            if (width > 0) {
                val maxPage = ((getChildAt(0)?.width ?: width) / width - 1).coerceAtLeast(0)
                val page = (scrollX.toFloat() / width).roundToInt().coerceIn(0, maxPage)
                smoothScrollTo(page * width, 0)
            }
        }
        return handled
    }
}
