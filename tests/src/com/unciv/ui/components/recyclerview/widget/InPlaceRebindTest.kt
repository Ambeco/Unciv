package com.unciv.ui.components.recyclerview.widget

import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Widget
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers [RecyclerView.onItemsChanged]: [RecyclerView.Adapter.notifyItemChanged]/
 * [RecyclerView.Adapter.notifyItemRangeChanged] rebind only the affected, already-active holder(s)
 * in place - never detaching them from the scenegraph, never touching any other currently-visible
 * holder - and support Android's payload-aware [RecyclerView.Adapter.onBindViewHolder] overload for
 * even cheaper partial updates.
 */
@RunWith(GdxTestRunner::class)
class InPlaceRebindTest {

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
    fun `notifyItemChanged rebinds only the target position, not other visible holders`() {
        val (rv, _, adapter) = makeRecycler(itemCount = 5) // 5 visible, no buffer
        adapter.items = (0 until 5).map { "changed$it" }
        val boundBefore = adapter.boundPositions.size

        adapter.notifyItemChanged(2)
        rv.layout()

        assertEquals(listOf(2), adapter.boundPositions.drop(boundBefore))
    }

    @Test
    fun `notifyItemRangeChanged rebinds exactly the positions in range`() {
        val (rv, _, adapter) = makeRecycler(itemCount = 5)
        adapter.items = (0 until 5).map { "changed$it" }
        val boundBefore = adapter.boundPositions.size

        adapter.notifyItemRangeChanged(1, 2)
        rv.layout()

        assertEquals(listOf(1, 2), adapter.boundPositions.drop(boundBefore).sorted())
    }

    @Test
    fun `notifyItemChanged for an off-screen position does nothing (it binds fresh whenever it next scrolls in)`() {
        val (rv, _, adapter) = makeRecycler(itemCount = 100, itemSize = 10f, width = 50f, height = 50f) // ~5 visible
        val boundBefore = adapter.boundPositions.size

        adapter.notifyItemChanged(50) // far off-screen
        rv.layout()

        assertEquals(boundBefore, adapter.boundPositions.size)
        assertNull(rv.findViewHolderForAdapterPosition(50))
    }

    @Test
    fun `notifyItemChanged never detaches the holder - the same Actor stays attached throughout`() {
        val (rv, _, adapter) = makeRecycler(itemCount = 5)
        val actorBefore = rv.findViewHolderForAdapterPosition(2)!!.getItemViews()[0]
        assertTrue(actorBefore.parent === rv)

        adapter.items = (0 until 5).map { "changed$it" }
        adapter.notifyItemChanged(2)
        rv.layout()

        val holderAfter = rv.findViewHolderForAdapterPosition(2)!!
        assertSame(actorBefore, holderAfter.getItemViews()[0])
        assertTrue(actorBefore.parent === rv) // never left the scenegraph
    }

    // region multi-Actor holders: adding/removing a child Actor via notifyItemChanged

    /** [base] is always attached; [extra] is attached only while [showExtra] is true - toggled by
     *  [VariableActorsAdapter] on bind, to exercise a holder growing/shrinking its Actor set. Both
     *  Actors are pre-existing fields (not recreated per bind), matching how a real holder would
     *  reuse its own child actors across binds. */
    private class VariableActorsHolder : RecyclerView.ViewHolder() {
        // base is a real-sized SizedActor (not a bare zero-pref Widget) so the viewport-math
        // assumptions these tests rely on (e.g. "5 visible" in a 50px viewport) still hold - there's
        // no itemSize fallback in LinearLayoutManager to lean on instead. base is always first in
        // getItemViews(), so it alone determines measure()'s result regardless of extra.
        val base = RecyclerViewTestSupport.SizedActor(10f)
        val extra = Widget()
        var showExtra = false
        override fun getItemViews(): List<Widget> = if (showExtra) listOf(base, extra) else listOf(base)
    }

    private class VariableActorsAdapter(val count: Int) : RecyclerView.Adapter<VariableActorsHolder>() {
        val expandedPositions = mutableSetOf<Int>()
        override fun onCreateViewHolder(parent: RecyclerView, viewType: Int) = VariableActorsHolder()
        override fun onBindViewHolder(holder: VariableActorsHolder, position: Int) {
            holder.showExtra = position in expandedPositions
        }
        override fun getItemCount(): Int = count
    }

    @Test
    fun `notifyItemChanged that makes a holder add an Actor attaches just the new one`() {
        val rv = RecyclerView()
        rv.setSize(50f, 50f)
        rv.layoutManager = LinearLayoutManager().apply { bufferItemCount = 0 }
        val adapter = VariableActorsAdapter(5)
        rv.setAdapter(adapter)
        rv.layout()

        val holder = rv.findViewHolderForAdapterPosition(2) as VariableActorsHolder
        assertTrue(holder.base.parent === rv)
        assertFalse(holder.extra.parent === rv)

        adapter.expandedPositions.add(2)
        adapter.notifyItemChanged(2)
        rv.layout()

        assertTrue("base must remain attached, unaffected by the change", holder.base.parent === rv)
        assertTrue("newly-returned extra must now be attached", holder.extra.parent === rv)
    }

    @Test
    fun `notifyItemChanged that makes a holder drop an Actor detaches just that one`() {
        val rv = RecyclerView()
        rv.setSize(50f, 50f)
        rv.layoutManager = LinearLayoutManager().apply { bufferItemCount = 0 }
        val adapter = VariableActorsAdapter(5)
        adapter.expandedPositions.add(2)
        rv.setAdapter(adapter)
        rv.layout()

        val holder = rv.findViewHolderForAdapterPosition(2) as VariableActorsHolder
        assertTrue(holder.extra.parent === rv)

        adapter.expandedPositions.remove(2)
        adapter.notifyItemChanged(2)
        rv.layout()

        assertTrue("base must remain attached", holder.base.parent === rv)
        assertNull("dropped extra must be detached", holder.extra.parent)
        assertNull("dropped extra's lookup entry must be forgotten too", rv.getChildViewHolder(holder.extra))
    }

    // endregion

    // region view-type change under notifyItemChanged: falls back to full evict+rebind

    @Test
    fun `notifyItemChanged for a position whose view type changed falls back to a fresh holder of the new type`() {
        val rv = RecyclerView()
        rv.setSize(20f, 20f) // exactly one item visible
        rv.layoutManager = LinearLayoutManager().apply { bufferItemCount = 0 }
        val items = mutableListOf("even0")
        val adapter = RecyclerViewTestSupport.StringAdapter(items, itemSize = 20f, viewTypeSelector = { s -> if (s.startsWith("even")) 0 else 1 })
        rv.setAdapter(adapter)
        rv.layout()
        val holderBefore = rv.findViewHolderForAdapterPosition(0)!!
        assertEquals(0, holderBefore.itemViewType)

        items[0] = "odd0" // same position, different view type
        adapter.notifyItemChanged(0)
        rv.layout()

        val holderAfter = rv.findViewHolderForAdapterPosition(0)!!
        assertEquals(1, holderAfter.itemViewType)
    }

    // endregion

    // region payload-aware bind

    private class PayloadLog {
        val plainBindPositions = mutableListOf<Int>()
        val payloadBinds = mutableListOf<Pair<Int, List<Any>>>()
    }

    private class LoggingHolder : RecyclerView.ViewHolder() {
        val actor = RecyclerViewTestSupport.SizedActor(10f)
        override fun getItemViews(): List<Widget> = listOf(actor)
    }

    private class LoggingAdapter(val count: Int, val log: PayloadLog) : RecyclerView.Adapter<LoggingHolder>() {
        override fun onCreateViewHolder(parent: RecyclerView, viewType: Int) = LoggingHolder()
        override fun onBindViewHolder(holder: LoggingHolder, position: Int) {
            log.plainBindPositions.add(position)
        }
        override fun onBindViewHolder(holder: LoggingHolder, position: Int, payloads: List<Any>) {
            log.payloadBinds.add(position to payloads)
        }
        override fun getItemCount(): Int = count
    }

    private fun loggingRecycler(count: Int, log: PayloadLog): RecyclerView {
        val rv = RecyclerView()
        rv.setSize(50f, 50f)
        rv.layoutManager = LinearLayoutManager().apply { bufferItemCount = 0 }
        rv.setAdapter(LoggingAdapter(count, log))
        rv.layout()
        return rv
    }

    @Test
    fun `notifyItemChanged without a payload calls the plain onBindViewHolder overload`() {
        val log = PayloadLog()
        val rv = loggingRecycler(5, log)
        log.plainBindPositions.clear()

        rv.getAdapter()!!.let { (it as LoggingAdapter).notifyItemChanged(2) }
        rv.layout()

        assertEquals(listOf(2), log.plainBindPositions)
        assertTrue(log.payloadBinds.isEmpty())
    }

    @Test
    fun `notifyItemChanged with a payload calls the payload-aware onBindViewHolder overload instead`() {
        val log = PayloadLog()
        val rv = loggingRecycler(5, log)
        log.plainBindPositions.clear()

        (rv.getAdapter() as LoggingAdapter).notifyItemChanged(2, "myPayload")
        rv.layout()

        assertTrue("plain overload must not be called when a payload is given", log.plainBindPositions.isEmpty())
        assertEquals(listOf(2 to listOf("myPayload")), log.payloadBinds)
    }

    @Test
    fun `the default payload overload falls back to the plain onBindViewHolder when not overridden`() {
        // FadeInAdapter-style holder that never overrides the payload overload.
        val rv = RecyclerView()
        rv.setSize(50f, 50f)
        rv.layoutManager = LinearLayoutManager().apply { bufferItemCount = 0 }
        val adapter = RecyclerViewTestSupport.StringAdapter((0 until 5).map { "item$it" })
        rv.setAdapter(adapter)
        rv.layout()
        val boundBefore = adapter.boundPositions.size

        adapter.notifyItemChanged(1, "somePayload")
        rv.layout()

        assertEquals(listOf(1), adapter.boundPositions.drop(boundBefore))
    }

    @Test
    fun `a full (no-payload) in-place rebind clears leftover actions, matching the pooled-rebind animation hook`() {
        val log = PayloadLog()
        val rv = loggingRecycler(5, log)
        val holder = rv.findViewHolderForAdapterPosition(2) as LoggingHolder
        holder.actor.addAction(Actions.forever(Actions.rotateBy(1f)))
        assertTrue(holder.actor.actions.size > 0)

        (rv.getAdapter() as LoggingAdapter).notifyItemChanged(2)
        rv.layout()

        assertEquals(0, holder.actor.actions.size)
    }

    @Test
    fun `a payload-based in-place rebind leaves leftover actions alone (minimally disruptive)`() {
        val log = PayloadLog()
        val rv = loggingRecycler(5, log)
        val holder = rv.findViewHolderForAdapterPosition(2) as LoggingHolder
        holder.actor.addAction(Actions.forever(Actions.rotateBy(1f)))
        assertTrue(holder.actor.actions.size > 0)

        (rv.getAdapter() as LoggingAdapter).notifyItemChanged(2, "myPayload")
        rv.layout()

        assertTrue("a partial payload update must not clobber an unrelated in-flight animation", holder.actor.actions.size > 0)
    }

    // endregion
}
