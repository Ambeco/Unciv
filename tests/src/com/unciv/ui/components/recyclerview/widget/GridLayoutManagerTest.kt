package com.unciv.ui.components.recyclerview.widget

import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class GridLayoutManagerTest {

    private fun makeRecycler(
        width: Float, height: Float, itemCount: Int, itemSize: Float, spanCount: Int,
        orientation: RecyclerView.Orientation = RecyclerView.Orientation.VERTICAL
    ): Pair<RecyclerView, GridLayoutManager> {
        val rv = RecyclerView()
        rv.setSize(width, height)
        val lm = GridLayoutManager(orientation, spanCount).apply { this.bufferItemCount = 0 }
        rv.layoutManager = lm
        rv.setAdapter(RecyclerViewTestSupport.StringAdapter((0 until itemCount).map { "item$it" }, itemSize = itemSize))
        rv.layout()
        return rv to lm
    }

    @Test
    fun `vertical grid places items in row-major cells`() {
        val (rv, _) = makeRecycler(width = 100f, height = 100f, itemCount = 6, itemSize = 20f, spanCount = 2)
        // crossSize = width / spanCount = 50
        val h0 = rv.findViewHolderForAdapterPosition(0)!!.getItemViews()[0]
        val h1 = rv.findViewHolderForAdapterPosition(1)!!.getItemViews()[0]
        val h2 = rv.findViewHolderForAdapterPosition(2)!!.getItemViews()[0]

        assertEquals(0f, h0.x, 0.001f)
        assertEquals(50f, h1.x, 0.001f) // second column
        assertEquals(0f, h2.x, 0.001f) // wraps to next row, first column
        assertEquals(h0.y - 20f, h2.y, 0.001f) // one row below item 0
        assertEquals(50f, h0.width, 0.001f)
        assertEquals(20f, h0.height, 0.001f)
    }

    @Test
    fun `horizontal grid places items in column-major cells`() {
        val (rv, _) = makeRecycler(
            width = 100f, height = 100f, itemCount = 6, itemSize = 20f, spanCount = 2,
            orientation = RecyclerView.Orientation.HORIZONTAL
        )
        val h0 = rv.findViewHolderForAdapterPosition(0)!!.getItemViews()[0]
        val h1 = rv.findViewHolderForAdapterPosition(1)!!.getItemViews()[0]
        assertEquals(0f, h0.x, 0.001f)
        assertEquals(0f, h1.x, 0.001f)
        // crossSize = height / spanCount = 50
        assertEquals(50f, h1.y, 0.001f)
    }

    @Test
    fun `virtualization bounds the number of attached grid cells for huge item counts`() {
        val (rv, _) = makeRecycler(width = 100f, height = 100f, itemCount = 1_000_000, itemSize = 20f, spanCount = 4)
        // ~5-6 rows visible (boundary row included) * 4 columns = at most 24, no buffer -
        // nowhere close to the 1,000,000 total items, demonstrating virtualization.
        assertTrue(rv.recycler.getPositions().size <= 24)
    }

    @Test
    fun `scrollToPosition scrolls to the row containing the position`() {
        val (rv, lm) = makeRecycler(width = 100f, height = 100f, itemCount = 200, itemSize = 20f, spanCount = 2)
        rv.scrollToPosition(20) // row 10
        rv.layout()
        // scrollOffsetY is in row-and-fraction units (anchorRow + scrollIntoRow/anchorRowSize), not
        // pixels-from-start - see GridLayoutManager's class doc. Row 10 is far from the last row (99),
        // so no end-of-list clamp applies: exactly row 10, nothing scrolled into it.
        assertEquals(10f, lm.scrollOffsetY, 0.001f)
    }

    @Test
    fun `empty grid produces no cells`() {
        val (rv, _) = makeRecycler(width = 100f, height = 100f, itemCount = 0, itemSize = 20f, spanCount = 3)
        assertTrue(rv.recycler.getPositions().isEmpty())
    }

    /**
     * After scrolling to an arbitrary (not row-boundary-aligned) point, every rendered cell must
     * still be *on screen* - see [LinearLayoutManagerTest]'s identical test for why this checks
     * overlap with the `[0, viewport]` rectangle rather than full containment (the fill loop
     * legitimately renders one row straddling the trailing edge by design, not a bug).
     */
    @Test
    fun `after scrolling to an arbitrary point, every rendered cell is on screen`() {
        val (rv, _) = makeRecycler(width = 100f, height = 100f, itemCount = 400, itemSize = 17f, spanCount = 2)
        rv.scrollBy(0f, 133f) // arbitrary: not a multiple of itemSize (17), row count, or viewport size (100)
        rv.layout()

        val positions = rv.recycler.getPositions()
        assertTrue("expected at least one rendered cell", positions.isNotEmpty())
        for (position in positions) {
            val view = rv.findViewHolderForAdapterPosition(position)!!.getItemViews()[0]
            assertTrue("cell at position $position (x=${view.x}, width=${view.width}) should overlap the horizontal viewport [0, ${rv.width}]",
                view.x < rv.width && view.x + view.width > 0f)
            assertTrue("cell at position $position (y=${view.y}, height=${view.height}) should overlap the vertical viewport [0, ${rv.height}]",
                view.y < rv.height && view.y + view.height > 0f)
        }
    }
}
