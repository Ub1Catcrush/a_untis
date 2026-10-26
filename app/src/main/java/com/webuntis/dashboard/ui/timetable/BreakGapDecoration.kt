package com.webuntis.dashboard.ui.timetable

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * Leaves empty space above a lesson row for the break since the previous row, scaled with the
 * same px-per-minute factor the rows themselves use ([LessonAdapter.pxPerMin]). This makes the
 * day view proportional like the week grid: a 5-minute break is a sliver, a free period or the
 * lunch break is a visible block, and the red [TimeIndicatorView] line moves through it.
 *
 * Only the gap BETWEEN rows is added (nothing above the first row); overlapping or touching
 * rows get no gap.
 */
class BreakGapDecoration : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val pos = parent.getChildAdapterPosition(view)
        if (pos <= 0) return
        val list = (parent.adapter as? LessonAdapter)?.currentList ?: return
        val prev = list.getOrNull(pos - 1) ?: return
        val cur  = list.getOrNull(pos) ?: return
        outRect.top = gapPx(prev, cur, parent.resources.displayMetrics.density)
    }

    companion object {
        /** Pixels of break between two consecutive rows (0 if they touch or overlap). */
        fun gapPx(prev: LessonGroup, next: LessonGroup, density: Float): Int {
            val gapMin = LessonAdapter.toMinutes(next.startTime) - LessonAdapter.toMinutes(prev.endTime)
            return if (gapMin > 0) (gapMin * LessonAdapter.pxPerMin(density)).toInt() else 0
        }
    }
}
