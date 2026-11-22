package to.wim.clauder.tasks

/** The segmented progress bar on the Tasks screen and the widget: one segment per step, like the
 * notification, the done ones filled. */
data class SegmentBar(val segments: Int, val filled: Int) {
    companion object {
        /** The widget can hold at most ten segments in a row (a limit of home screen widgets). */
        const val WIDGET_MAX = 10

        /** With more steps than [max], each segment stands for several steps and fills once all of
         * them are done, so a full bar always means everything is done. */
        fun from(done: Int, total: Int, max: Int = Int.MAX_VALUE): SegmentBar {
            if (total <= 0) return SegmentBar(1, 0)
            val segments = minOf(total, max)
            return SegmentBar(segments, done.coerceIn(0, total) * segments / total)
        }

        fun of(list: TaskList, max: Int = Int.MAX_VALUE): SegmentBar = from(list.doneCount, list.items.size, max)
    }
}
