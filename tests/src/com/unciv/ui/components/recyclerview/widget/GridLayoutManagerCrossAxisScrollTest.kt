package com.unciv.ui.components.recyclerview.widget

import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Covers [GridLayoutManager]'s opt-in cross-axis (2D) scrolling via [GridLayoutManager.cellCrossSize] -
 *  see that property's KDoc. The default (auto-fit, `cellCrossSize <= 0`) path is covered by
 *  [GridLayoutManagerTest] and must keep behaving exactly as before this feature existed. */
@RunWith(GdxTestRunner::class)
class GridLayoutManagerCrossAxisScrollTest {

    private fun makeRecycler(
        width: Float, height: Float, itemCount: Int, itemSize: Float, spanCount: Int, cellCrossSize: Float,
        orientation: RecyclerView.Orientation = RecyclerView.Orientation.VERTICAL
    ): Pair<RecyclerView, GridLayoutManager> {
        val rv = RecyclerView()
        rv.setSize(width, height)
        val lm = GridLayoutManager(orientation, spanCount, cellCrossSize).apply { this.bufferItemCount = 0 }
        rv.layoutManager = lm
        rv.setAdapter(RecyclerViewTestSupport.StringAdapter((0 until itemCount).map { "item$it" }, itemSize = itemSize))
        rv.layout()
        return rv to lm
    }

    @Test
    fun `auto-fit default never enables cross-axis scrolling`() {
        val (_, lm) = makeRecycler(width = 100f, height = 100f, itemCount = 6, itemSize = 20f, spanCount = 2, cellCrossSize = -1f)
        assertFalse(lm.canScrollHorizontally()) // VERTICAL orientation: cross axis is horizontal
        assertEquals(0, lm.anchorColumn)
        assertEquals(0f, lm.scrollIntoColumn, 0.001f)
    }

    @Test
    fun `cellCrossSize smaller than the fit-to-viewport size stays non-scrollable`() {
        // 2 columns * 40 = 80, fits inside a 100-wide viewport - no overflow, so still not scrollable.
        val (_, lm) = makeRecycler(width = 100f, height = 100f, itemCount = 6, itemSize = 20f, spanCount = 2, cellCrossSize = 40f)
        assertFalse(lm.canScrollHorizontally())
    }

    @Test
    fun `oversized cellCrossSize enables cross-axis scrolling on a vertical grid`() {
        // 2 columns * 80 = 160, overflows a 100-wide viewport by 60 - now scrollable.
        val (rv, lm) = makeRecycler(width = 100f, height = 100f, itemCount = 6, itemSize = 20f, spanCount = 2, cellCrossSize = 80f)
        assertTrue(lm.canScrollHorizontally())

        val h0Before = rv.findViewHolderForAdapterPosition(0)!!.getItemViews()[0]
        assertEquals(0f, h0Before.x, 0.001f)

        rv.scrollBy(30f, 0f) // scroll the cross axis right by 30px
        rv.layout()
        val h0After = rv.findViewHolderForAdapterPosition(0)!!.getItemViews()[0]
        assertEquals(-30f, h0After.x, 0.001f)
        assertEquals(0, lm.anchorColumn)
        assertEquals(30f, lm.scrollIntoColumn, 0.001f)

        // Clamp at the trailing edge: can't scroll past overflow (60px).
        rv.scrollBy(1000f, 0f)
        rv.layout()
        val h0End = rv.findViewHolderForAdapterPosition(0)!!.getItemViews()[0]
        assertEquals(-60f, h0End.x, 0.001f)

        // Clamp at the leading edge.
        rv.scrollBy(-1000f, 0f)
        rv.layout()
        val h0Start = rv.findViewHolderForAdapterPosition(0)!!.getItemViews()[0]
        assertEquals(0f, h0Start.x, 0.001f)
    }

    @Test
    fun `oversized cellCrossSize enables cross-axis scrolling on a horizontal grid`() {
        // Horizontal orientation: main axis is X (rows advance sideways), cross axis is Y (columns).
        val (rv, lm) = makeRecycler(
            width = 100f, height = 100f, itemCount = 6, itemSize = 20f, spanCount = 2, cellCrossSize = 80f,
            orientation = RecyclerView.Orientation.HORIZONTAL
        )
        assertTrue(lm.canScrollVertically())

        rv.scrollBy(0f, 40f)
        rv.layout()
        val h0 = rv.findViewHolderForAdapterPosition(0)!!.getItemViews()[0]
        assertEquals(-40f, h0.y, 0.001f)
    }
}
