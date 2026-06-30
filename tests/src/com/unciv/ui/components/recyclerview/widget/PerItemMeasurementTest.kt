package com.unciv.ui.components.recyclerview.widget

import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the anchor-model per-item measurement in [LinearLayoutManager]: every item view is a
 * [com.badlogic.gdx.scenes.scene2d.ui.Widget] - hence always a
 * [com.badlogic.gdx.scenes.scene2d.utils.Layout] - and is placed using its measured `prefHeight`/
 * `prefWidth` (or [RecyclerView.ViewHolder.layoutParams]'s explicit override); there is no
 * `itemSize`-style fallback constant, so a Widget reporting no meaningful size of its own simply
 * measures as 0.
 */
@RunWith(GdxTestRunner::class)
class PerItemMeasurementTest {

    private fun makeRecycler(count: Int, sizeFor: (Int) -> Float, height: Float = 200f):
        Pair<RecyclerView, LinearLayoutManager> {
        val rv = RecyclerView()
        rv.setSize(100f, height)
        val lm = LinearLayoutManager().apply { bufferItemCount = 0 }
        rv.layoutManager = lm
        rv.setAdapter(RecyclerViewTestSupport.SizedAdapter(count, sizeFor))
        rv.layout()
        return rv to lm
    }

    @Test
    fun `variable item heights stack using each measured pref height`() {
        // Even positions 20px tall, odd positions 40px tall.
        val (rv, _) = makeRecycler(count = 10, sizeFor = { if (it % 2 == 0) 20f else 40f })
        val h0 = rv.findViewHolderForAdapterPosition(0)!!.getItemViews()[0]
        val h1 = rv.findViewHolderForAdapterPosition(1)!!.getItemViews()[0]
        val h2 = rv.findViewHolderForAdapterPosition(2)!!.getItemViews()[0]

        assertEquals(20f, h0.height, 0.001f)
        assertEquals(40f, h1.height, 0.001f)
        // Item 1 sits directly below item 0 (item 0 is 20 tall), item 2 below item 1 (40 tall).
        assertEquals(h0.y - 40f, h1.y, 0.001f)
        assertEquals(h1.y - 20f, h2.y, 0.001f)
    }

    @Test
    fun `taller items mean fewer fit in the viewport than the uniform fallback would suggest`() {
        // 100px viewport. All items 50px tall => only ~2 fit, not the fallback 10 (itemSize=10).
        val (rv, lm) = makeRecycler(count = 100, sizeFor = { 50f }, height = 100f)
        assertEquals(0, lm.findFirstVisibleItemPosition())
        // 100 / 50 = 2 items fully fit; boundary item at exactly 100 is included -> last == 2.
        assertEquals(2, lm.findLastVisibleItemPosition())
        assertTrue(rv.recycler.getPositions().size <= 3)
    }

    @Test
    fun `scrolling consumes measured item sizes when advancing the anchor`() {
        val (rv, lm) = makeRecycler(count = 100, sizeFor = { 50f }, height = 100f)
        // Scroll by exactly one item height; the anchor should advance by one position.
        rv.scrollBy(0f, 50f)
        assertEquals(1, lm.anchorPosition)
        assertEquals(1, lm.findFirstVisibleItemPosition())
    }

    @Test
    fun `a Widget with no configured pref size measures as 0 - there is no itemSize fallback`() {
        val rv = RecyclerView()
        rv.setSize(50f, 50f)
        val lm = LinearLayoutManager().apply { bufferItemCount = 0 }
        rv.layoutManager = lm
        // itemSize defaults to 0f, so every item's SizedActor reports a 0 pref, same as a bare Widget.
        rv.setAdapter(RecyclerViewTestSupport.StringAdapter((0 until 20).map { "item$it" }))
        rv.layout()
        assertEquals(0f, rv.findViewHolderForAdapterPosition(0)!!.getItemViews()[0].height, 0.001f)
    }

    // region ViewHolder.layoutParams sizing

    /** Binds an explicit [RecyclerView.LayoutParams] override (not just the actor's own [Layout]
     *  pref) per position, so [LinearLayoutManager.measure]'s min/pref/max precedence can be
     *  exercised. `null` leaves the holder's default `layoutParams` fallback (the actor's own
     *  [Layout] size) in place. */
    private class LayoutParamsAdapter(
        val count: Int,
        private val paramsFor: (Int) -> RecyclerView.LayoutParams?
    ) : RecyclerView.Adapter<RecyclerViewTestSupport.SizedViewHolder>() {
        override fun onCreateViewHolder(parent: RecyclerView, viewType: Int) = RecyclerViewTestSupport.SizedViewHolder()
        override fun onBindViewHolder(holder: RecyclerViewTestSupport.SizedViewHolder, position: Int) {
            holder.sizedView.pref = 20f // a fixed Layout pref, to prove an explicit override takes precedence
            holder.layoutParams = paramsFor(position)
        }
        override fun getItemCount(): Int = count
    }

    private fun makeLayoutParamsRecycler(paramsFor: (Int) -> RecyclerView.LayoutParams?, height: Float = 200f):
        Pair<RecyclerView, LinearLayoutManager> {
        val rv = RecyclerView()
        rv.setSize(100f, height)
        val lm = LinearLayoutManager().apply { bufferItemCount = 0 }
        rv.layoutManager = lm
        rv.setAdapter(LayoutParamsAdapter(10, paramsFor))
        rv.layout()
        return rv to lm
    }

    @Test
    fun `an explicit layoutParams prefHeight overrides the actor's own Layout pref size`() {
        val (rv, _) = makeLayoutParamsRecycler({ RecyclerView.LayoutParams(prefHeightValue = 35f) })
        // sizedView.pref is 20f for every item, but the explicit layoutParams.prefHeight (35f) wins.
        assertEquals(35f, rv.findViewHolderForAdapterPosition(0)!!.getItemViews()[0].height, 0.001f)
    }

    @Test
    fun `layoutParams minHeight clamps a smaller measured size up`() {
        // No prefHeight set, so the 20f Layout pref would normally apply - minHeight should raise it.
        val (rv, _) = makeLayoutParamsRecycler({ RecyclerView.LayoutParams(minHeightValue = 30f) })
        assertEquals(30f, rv.findViewHolderForAdapterPosition(0)!!.getItemViews()[0].height, 0.001f)
    }

    @Test
    fun `layoutParams maxHeight clamps a larger measured size down`() {
        val (rv, _) = makeLayoutParamsRecycler({ RecyclerView.LayoutParams(prefHeightValue = 20f, maxHeightValue = 12f) })
        assertEquals(12f, rv.findViewHolderForAdapterPosition(0)!!.getItemViews()[0].height, 0.001f)
    }

    @Test
    fun `layoutParams minHeight and maxHeight together clamp an explicit prefHeight on both sides`() {
        val (rv, _) = makeLayoutParamsRecycler({
            RecyclerView.LayoutParams(prefHeightValue = 5f, minHeightValue = 8f, maxHeightValue = 15f)
        })
        // prefHeight (5f) is below minHeight (8f), so it's raised to 8f.
        assertEquals(8f, rv.findViewHolderForAdapterPosition(0)!!.getItemViews()[0].height, 0.001f)
    }

    @Test
    fun `an all-zero explicit layoutParams reports no usable size - there is no itemSize fallback`() {
        // All fields default to 0f, which measure() treats as "unset" - not the actor's 20f pref,
        // and (since it's an explicit override, not the default fallback) not the actor's own pref
        // either - just 0, with nothing further to fall back to.
        val (rv, _) = makeLayoutParamsRecycler({ RecyclerView.LayoutParams() })
        assertEquals(0f, rv.findViewHolderForAdapterPosition(0)!!.getItemViews()[0].height, 0.001f)
    }

    @Test
    fun `a null layoutParams override falls back to the actor's own Layout pref size`() {
        val (rv, _) = makeLayoutParamsRecycler({ null })
        assertEquals(20f, rv.findViewHolderForAdapterPosition(0)!!.getItemViews()[0].height, 0.001f)
    }

    // endregion
}
