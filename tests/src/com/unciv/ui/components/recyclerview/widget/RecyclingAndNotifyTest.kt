package com.unciv.ui.components.recyclerview.widget

import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Covers Adapter notify* -> relayout behavior, and ViewHolder create/bind/recycle/reuse. */
@RunWith(GdxTestRunner::class)
class RecyclingAndNotifyTest {

    private fun makeRecycler(itemCount: Int, itemSize: Float = 10f, width: Float = 50f, height: Float = 50f):
        Triple<RecyclerView, LinearLayoutManager, RecyclerViewTestSupport.StringAdapter> {
        val rv = RecyclerView()
        rv.setSize(width, height)
        val lm = LinearLayoutManager().apply { bufferItemCount = 0 }
        rv.layoutManager = lm
        val adapter = RecyclerViewTestSupport.StringAdapter((0 until itemCount).map { "item$it" }, itemSize = itemSize)
        rv.setAdapter(adapter)
        rv.layout()
        return Triple(rv, lm, adapter)
    }

    @Test
    fun `notifyDataSetChanged rebinds existing holders without recreating them`() {
        val (rv, _, adapter) = makeRecycler(itemCount = 5)
        val createsBefore = adapter.createCount
        val bindsBefore = adapter.bindCount

        adapter.items = (0 until 5).map { "changed$it" }
        adapter.notifyDataSetChanged()
        rv.layout()

        assertEquals(createsBefore, adapter.createCount) // reused from pool, no new instances
        assertTrue(adapter.bindCount > bindsBefore) // but rebound with fresh data
    }

    @Test
    fun `notifyItemInserted and notifyItemRemoved trigger a relayout reflecting the new item count`() {
        val (rv, lm, adapter) = makeRecycler(itemCount = 3, itemSize = 10f, width = 50f, height = 50f)
        adapter.items = adapter.items + "item3"
        adapter.notifyItemInserted(3)
        rv.layout()
        assertEquals(3, lm.findLastVisibleItemPosition())

        adapter.items = adapter.items.dropLast(2)
        adapter.notifyItemRemoved(2)
        rv.layout()
        assertTrue(rv.recycler.getPositions().all { it < adapter.items.size })
    }

    @Test
    fun `rapid consecutive notify calls settle on the final data without crashing`() {
        val (rv, _, adapter) = makeRecycler(itemCount = 10)
        repeat(20) { i ->
            adapter.items = (0 until 10).map { "v$i-$it" }
            adapter.notifyDataSetChanged()
            adapter.notifyItemChanged(0)
            adapter.notifyItemRangeChanged(0, 3)
        }
        rv.layout()
        val holder0 = rv.findViewHolderForAdapterPosition(0)
        assertTrue(holder0 != null)
    }

    @Test
    fun `scrolling recycles off-screen holders and reuses them for newly visible positions`() {
        val (rv, _, adapter) = makeRecycler(itemCount = 1000, itemSize = 10f, width = 50f, height = 50f)
        val createsAfterInitial = adapter.createCount
        assertTrue(createsAfterInitial <= 6) // 5 visible, no buffer, some slack

        // Scroll through the whole list; the pool should absorb re-use, so total created
        // holders should stay small relative to item count.
        for (step in 1..50) {
            rv.scrollBy(0f, 15f)
        }
        assertTrue("createCount=${adapter.createCount} should stay small (virtualized), not scale with item count",
            adapter.createCount < 30)
    }

    @Test
    fun `holders are recycled per view type - different types never share a pooled instance`() {
        val rv = RecyclerView()
        rv.setSize(20f, 20f) // exactly one item visible at a time
        val lm = LinearLayoutManager().apply { bufferItemCount = 0 }
        rv.layoutManager = lm
        // Alternate view types by position parity.
        val items = (0 until 20).map { "item$it" }
        val adapter = RecyclerViewTestSupport.StringAdapter(items, itemSize = 20f, viewTypeSelector = { s -> s.removePrefix("item").toInt() % 2 })
        rv.setAdapter(adapter)
        rv.layout()

        val seenHolderTypes = HashMap<RecyclerView.ViewHolder, Int>()
        for (pos in 0 until 20) {
            rv.scrollToPosition(pos)
            rv.layout()
            val holder = rv.findViewHolderForAdapterPosition(pos)!!
            val expectedType = pos % 2
            assertEquals(expectedType, holder.itemViewType)
            val previousType = seenHolderTypes[holder]
            if (previousType != null) assertEquals(previousType, holder.itemViewType)
            seenHolderTypes[holder] = holder.itemViewType
        }
        // Because two view types alternate and each pooled holder is only ever reused for its
        // own type, the number of distinct holder instances must stay small (pool efficiency).
        assertTrue(seenHolderTypes.size <= 4)
    }

    @Test
    fun `adapterPosition on a bound holder matches the position it was bound to`() {
        val (rv, _, _) = makeRecycler(itemCount = 5)
        for (pos in rv.recycler.getPositions()) {
            val holder = rv.findViewHolderForAdapterPosition(pos)!!
            assertEquals(pos, holder.adapterPosition)
        }
    }
}
