package com.unciv.ui.components.recyclerview.widget

import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Covers feature 3: [RecyclerView.AdapterDataObserver] registration and the notify* -> callback
 * mapping, multiple observers, unregistration, and the preserved RecyclerView-relayout behavior. */
@RunWith(GdxTestRunner::class)
class AdapterDataObserverTest {

    /** Records the granular callbacks it receives so tests can assert on them. */
    private class RecordingObserver : RecyclerView.AdapterDataObserver() {
        val log = mutableListOf<String>()
        override fun onChanged() { log.add("changed") }
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { log.add("rangeChanged:$positionStart:$itemCount") }
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { log.add("rangeInserted:$positionStart:$itemCount") }
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { log.add("rangeRemoved:$positionStart:$itemCount") }
        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) { log.add("rangeMoved:$fromPosition:$toPosition:$itemCount") }
    }

    private fun adapter() = RecyclerViewTestSupport.StringAdapter((0 until 5).map { "item$it" })

    @Test
    fun `each notify method maps to the matching observer callback`() {
        val a = adapter()
        val obs = RecordingObserver()
        a.registerAdapterDataObserver(obs)

        a.notifyDataSetChanged()
        a.notifyItemChanged(2)
        a.notifyItemRangeChanged(1, 3)
        a.notifyItemInserted(4)
        a.notifyItemRangeInserted(0, 2)
        a.notifyItemRemoved(3)
        a.notifyItemRangeRemoved(1, 2)
        a.notifyItemMoved(0, 4)

        assertEquals(
            listOf(
                "changed",
                "rangeChanged:2:1",
                "rangeChanged:1:3",
                "rangeInserted:4:1",
                "rangeInserted:0:2",
                "rangeRemoved:3:1",
                "rangeRemoved:1:2",
                "rangeMoved:0:4:1"
            ),
            obs.log
        )
    }

    @Test
    fun `multiple observers all receive callbacks`() {
        val a = adapter()
        val o1 = RecordingObserver()
        val o2 = RecordingObserver()
        a.registerAdapterDataObserver(o1)
        a.registerAdapterDataObserver(o2)

        a.notifyItemChanged(1)
        assertEquals(listOf("rangeChanged:1:1"), o1.log)
        assertEquals(listOf("rangeChanged:1:1"), o2.log)
    }

    @Test
    fun `unregistered observer stops receiving callbacks`() {
        val a = adapter()
        val obs = RecordingObserver()
        a.registerAdapterDataObserver(obs)
        a.notifyDataSetChanged()
        a.unregisterAdapterDataObserver(obs)
        a.notifyDataSetChanged()
        assertEquals(1, obs.log.size) // only the first notification landed
    }

    @Test
    fun `registering the same observer twice does not double-dispatch`() {
        val a = adapter()
        val obs = RecordingObserver()
        a.registerAdapterDataObserver(obs)
        a.registerAdapterDataObserver(obs)
        a.notifyDataSetChanged()
        assertEquals(1, obs.log.size)
    }

    @Test
    fun `RecyclerView still relayouts on notify even with an extra observer attached`() {
        val rv = RecyclerView()
        rv.setSize(50f, 50f)
        rv.layoutManager = LinearLayoutManager().apply { bufferItemCount = 0 }
        val a = RecyclerViewTestSupport.StringAdapter((0 until 3).map { "item$it" }, itemSize = 10f)
        rv.setAdapter(a)
        rv.layout()

        val obs = RecordingObserver()
        a.registerAdapterDataObserver(obs)

        a.items = a.items + "item3" + "item4"
        a.notifyItemRangeInserted(3, 2)
        rv.layout()

        // Both the custom observer and the RecyclerView's own (relayout) observer fired.
        assertEquals(listOf("rangeInserted:3:2"), obs.log)
        assertTrue(rv.recycler.getPositions().contains(4))
    }
}
