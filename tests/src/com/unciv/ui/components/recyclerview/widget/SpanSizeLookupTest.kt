package com.unciv.ui.components.recyclerview.widget

import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Covers feature 4: [GridLayoutManager.SpanSizeLookup] - multi-span items and Android-style row
 * wrapping when an item doesn't fit in the remaining columns. */
@RunWith(GdxTestRunner::class)
class SpanSizeLookupTest {

    private fun makeGrid(count: Int, spanCount: Int, width: Float, height: Float = 300f, itemSize: Float = 20f):
        Pair<RecyclerView, GridLayoutManager> {
        val rv = RecyclerView()
        rv.setSize(width, height)
        val lm = GridLayoutManager(spanCount = spanCount).apply { bufferItemCount = 0 }
        rv.layoutManager = lm
        rv.setAdapter(RecyclerViewTestSupport.StringAdapter((0 until count).map { "item$it" }, itemSize = itemSize))
        rv.layout()
        return rv to lm
    }

    @Test
    fun `a full-span header occupies the whole row and pushes following items down`() {
        val (rv, _) = makeGrid(count = 5, spanCount = 3, width = 90f)
        // Position 0 spans the full width; the rest are single-span.
        rv.layoutManager!!.let { lm ->
            (lm as GridLayoutManager).spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (position == 0) 3 else 1
            }
        }
        rv.layout()

        val h0 = rv.findViewHolderForAdapterPosition(0)!!.getItemViews()[0]
        val h1 = rv.findViewHolderForAdapterPosition(1)!!.getItemViews()[0]
        assertEquals(90f, h0.width, 0.001f)   // full 3 spans * 30px cell
        assertEquals(0f, h1.x, 0.001f)        // item 1 starts a new row, first column
        assertEquals(30f, h1.width, 0.001f)   // single span
        assertTrue("item 1 is one row below the header", h1.y < h0.y)
    }

    @Test
    fun `an item that does not fit wraps to the next row (Android DefaultSpanSizeLookup semantics)`() {
        val (rv, _) = makeGrid(count = 5, spanCount = 3, width = 90f)
        // Spans: [2, 2, 1, 1, 1]. Row0={0}, then 1 (span2) doesn't fit after the first 2 -> Row1={1,2}, Row2={3,4}.
        (rv.layoutManager as GridLayoutManager).spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (position < 2) 2 else 1
        }
        rv.layout()

        val h0 = rv.findViewHolderForAdapterPosition(0)!!.getItemViews()[0]
        val h1 = rv.findViewHolderForAdapterPosition(1)!!.getItemViews()[0]
        val h2 = rv.findViewHolderForAdapterPosition(2)!!.getItemViews()[0]

        assertEquals(60f, h0.width, 0.001f) // span 2 => 60px
        assertEquals(0f, h1.x, 0.001f)      // wrapped: new row, first column
        assertEquals(60f, h2.x, 0.001f)     // item 2 sits after item 1 (span 2) in the same row
        assertTrue("item 1 wrapped below item 0", h1.y < h0.y)
        assertEquals("items 1 and 2 share a row", h1.y, h2.y, 0.001f)
    }

    @Test
    fun `default span size lookup places one item per cell`() {
        val (rv, _) = makeGrid(count = 6, spanCount = 2, width = 100f)
        val h0 = rv.findViewHolderForAdapterPosition(0)!!.getItemViews()[0]
        val h1 = rv.findViewHolderForAdapterPosition(1)!!.getItemViews()[0]
        assertEquals(0f, h0.x, 0.001f)
        assertEquals(50f, h1.x, 0.001f) // second column, one cell each
        assertEquals(50f, h0.width, 0.001f)
    }

    @Test
    fun `changing spanSizeLookup invalidates packing and re-lays out`() {
        val (rv, _) = makeGrid(count = 6, spanCount = 2, width = 100f)
        val widthBefore = rv.findViewHolderForAdapterPosition(0)!!.getItemViews()[0].width
        assertEquals(50f, widthBefore, 0.001f)

        (rv.layoutManager as GridLayoutManager).spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = 2 // everything full-width now
        }
        rv.layout()
        assertEquals(100f, rv.findViewHolderForAdapterPosition(0)!!.getItemViews()[0].width, 0.001f)
    }
}
