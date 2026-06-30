package com.unciv.ui.components.recyclerview.widget

import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class LinearLayoutManagerTest {

    private fun makeRecycler(
        width: Float,
        height: Float,
        itemCount: Int,
        itemSize: Float,
        orientation: RecyclerView.Orientation = RecyclerView.Orientation.VERTICAL,
        bufferItemCount: Int = 1
    ): Pair<RecyclerView, LinearLayoutManager> {
        val rv = RecyclerView()
        rv.setSize(width, height)
        val lm = LinearLayoutManager(orientation).apply { this.bufferItemCount = bufferItemCount }
        rv.layoutManager = lm
        rv.setAdapter(RecyclerViewTestSupport.StringAdapter((0 until itemCount).map { "item$it" }, itemSize = itemSize))
        rv.layout()
        return rv to lm
    }

    @Test
    fun `vertical layout stacks items top to bottom`() {
        val (rv, _) = makeRecycler(width = 100f, height = 100f, itemCount = 5, itemSize = 20f, bufferItemCount = 0)
        // Item 0 should be at the very top of the viewport.
        val holder0 = rv.findViewHolderForAdapterPosition(0)!!
        assertEquals(80f, holder0.getItemViews()[0].y, 0.001f) // height - itemSize
        assertEquals(100f, holder0.getItemViews()[0].width, 0.001f)
        assertEquals(20f, holder0.getItemViews()[0].height, 0.001f)

        val holder1 = rv.findViewHolderForAdapterPosition(1)!!
        assertEquals(60f, holder1.getItemViews()[0].y, 0.001f)
    }

    @Test
    fun `horizontal layout stacks items left to right`() {
        val (rv, _) = makeRecycler(
            width = 100f, height = 100f, itemCount = 5, itemSize = 20f,
            orientation = RecyclerView.Orientation.HORIZONTAL, bufferItemCount = 0
        )
        val holder0 = rv.findViewHolderForAdapterPosition(0)!!
        assertEquals(0f, holder0.getItemViews()[0].x, 0.001f)
        assertEquals(20f, holder0.getItemViews()[0].width, 0.001f)
        assertEquals(100f, holder0.getItemViews()[0].height, 0.001f)

        val holder1 = rv.findViewHolderForAdapterPosition(1)!!
        assertEquals(20f, holder1.getItemViews()[0].x, 0.001f)
    }

    @Test
    fun `only visible plus buffer items are materialized - virtualization works`() {
        val (rv, lm) = makeRecycler(width = 50f, height = 50f, itemCount = 100_000, itemSize = 10f, bufferItemCount = 1)
        // Viewport fits 5 items exactly, +1 buffer each side => at most 7 attached.
        assertTrue(rv.recycler.getPositions().size <= 7)
        assertEquals(0, lm.findFirstVisibleItemPosition())
        assertTrue(lm.findLastVisibleItemPosition() < 10)
    }

    @Test
    fun `findFirstVisibleItemPosition and findLastVisibleItemPosition track scrolling`() {
        val (rv, lm) = makeRecycler(width = 50f, height = 50f, itemCount = 1000, itemSize = 10f, bufferItemCount = 0)
        assertEquals(0, lm.findFirstVisibleItemPosition())
        // Viewport exactly fits 5 items (0..49px); the boundary item (index 5, starting exactly
        // at the viewport edge) is included per the >= boundary in the visible-range formula.
        assertEquals(5, lm.findLastVisibleItemPosition())

        rv.scrollBy(0f, 25f) // scroll down by 25px
        assertEquals(2, lm.findFirstVisibleItemPosition())
        assertEquals(7, lm.findLastVisibleItemPosition())
    }

    @Test
    fun `scrollBy clamps at content bounds`() {
        val (rv, _) = makeRecycler(width = 50f, height = 50f, itemCount = 10, itemSize = 10f, bufferItemCount = 0)
        // scrollOffsetY is in position units (anchorPosition + scrollIntoAnchor/anchorSize), not
        // pixels-from-start - see LinearLayoutManager's class doc. 100px of content (10 items * 10px)
        // in a 50px viewport: the last 5 items (positions 5..9) exactly fill it, so max scroll pins
        // the anchor at position 5 with nothing scrolled into it.
        rv.scrollBy(0f, 1000f)
        assertEquals(5f, rv.scrollOffsetY, 0.001f)

        rv.scrollBy(0f, -10000f)
        assertEquals(0f, rv.scrollOffsetY, 0.001f)
    }

    @Test
    fun `disabled adapter with zero items produces empty visible range`() {
        val (rv, lm) = makeRecycler(width = 50f, height = 50f, itemCount = 0, itemSize = 10f)
        assertTrue(rv.recycler.getPositions().isEmpty())
        assertEquals(RecyclerView.NO_POSITION, lm.findFirstVisibleItemPosition())
    }

    @Test
    fun `scrollToPosition on LinearLayoutManager jumps directly to the item offset`() {
        val (rv, lm) = makeRecycler(width = 50f, height = 50f, itemCount = 200, itemSize = 10f, bufferItemCount = 0)
        rv.scrollToPosition(20)
        rv.layout()
        assertEquals(20, lm.findFirstVisibleItemPosition())
    }

    @Test
    fun `canScrollVertically and canScrollHorizontally reflect orientation`() {
        val vertical = LinearLayoutManager(RecyclerView.Orientation.VERTICAL)
        assertTrue(vertical.canScrollVertically())
        assertTrue(!vertical.canScrollHorizontally())

        val horizontal = LinearLayoutManager(RecyclerView.Orientation.HORIZONTAL)
        assertTrue(horizontal.canScrollHorizontally())
        assertTrue(!horizontal.canScrollVertically())
    }

    /**
     * After scrolling to an arbitrary (not item-boundary-aligned) point, every rendered child must
     * still be *on screen* - i.e. its bounds overlap the `[0, viewport]` rectangle in each axis, not
     * placed thousands of pixels away or on the wrong side entirely (the kind of bug a sign error or
     * stale offset in the anchor math would produce). This checks overlap, not full containment:
     * [onLayoutChildren]'s fill loop legitimately renders one item straddling the trailing edge
     * (it includes any item whose *leading* edge is still within the viewport, even if that item's
     * own size then carries its trailing edge past it) - normal virtualized-list behavior, not a bug,
     * so a stricter "fully contained" assertion would be flaky by design rather than catching bugs.
     */
    @Test
    fun `after scrolling to an arbitrary point, every rendered child is on screen`() {
        val (rv, _) = makeRecycler(width = 100f, height = 100f, itemCount = 200, itemSize = 17f, bufferItemCount = 0)
        rv.scrollBy(0f, 133f) // arbitrary: not a multiple of itemSize (17) or the viewport size (100)
        rv.layout()

        val positions = rv.recycler.getPositions()
        assertTrue("expected at least one rendered child", positions.isNotEmpty())
        for (position in positions) {
            val view = rv.findViewHolderForAdapterPosition(position)!!.getItemViews()[0]
            assertTrue("child at position $position (x=${view.x}, width=${view.width}) should overlap the horizontal viewport [0, ${rv.width}]",
                view.x < rv.width && view.x + view.width > 0f)
            assertTrue("child at position $position (y=${view.y}, height=${view.height}) should overlap the vertical viewport [0, ${rv.height}]",
                view.y < rv.height && view.y + view.height > 0f)
        }
    }
}
