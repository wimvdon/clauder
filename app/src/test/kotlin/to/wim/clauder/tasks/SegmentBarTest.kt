package to.wim.clauder.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentBarTest {
    @Test
    fun `one segment per step, the done ones filled`() {
        assertEquals(SegmentBar(5, 2), SegmentBar.from(done = 2, total = 5))
        assertEquals(SegmentBar(5, 5), SegmentBar.from(done = 5, total = 5))
    }

    @Test
    fun `a session without steps shows one empty segment`() {
        assertEquals(SegmentBar(1, 0), SegmentBar.from(done = 0, total = 0))
    }

    @Test
    fun `more steps than fit share segments, and only a finished session fills the bar`() {
        assertEquals(SegmentBar(10, 5), SegmentBar.from(done = 10, total = 20, max = SegmentBar.WIDGET_MAX))
        assertEquals(SegmentBar(10, 9), SegmentBar.from(done = 19, total = 20, max = SegmentBar.WIDGET_MAX))
        assertEquals(SegmentBar(10, 10), SegmentBar.from(done = 20, total = 20, max = SegmentBar.WIDGET_MAX))
        assertEquals(SegmentBar(10, 0), SegmentBar.from(done = 1, total = 12, max = SegmentBar.WIDGET_MAX))
    }
}
