package com.unciv.ui.components.recyclerview.widget

import com.badlogic.gdx.scenes.scene2d.actions.Actions
import com.badlogic.gdx.scenes.scene2d.ui.Widget
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Covers feature 5: the per-bind animation hook - leftover [com.badlogic.gdx.scenes.scene2d.Action]s
 * and stale alpha are wiped before each (re)bind, and [RecyclerView.Adapter.onBindViewHolder] can
 * freely start a fresh appear/update animation on the item view. */
@RunWith(GdxTestRunner::class)
class BindAnimationTest {

    private class Holder(private val widget: Widget) : RecyclerView.ViewHolder() {
        override fun getItemViews(): List<Widget> = listOf(widget)
    }

    /** Adds a fade-in on every bind, exactly as the Adapter KDoc example suggests. Item Widgets are
     *  real-sized ([RecyclerViewTestSupport.SizedActor], not a bare zero-pref [Widget]) to match
     *  [singleVisibleRecycler]'s 25px convention - there's no `itemSize` fallback in
     *  [LinearLayoutManager] to lean on instead. */
    private class FadeInAdapter(val count: Int) : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: RecyclerView, viewType: Int) = Holder(RecyclerViewTestSupport.SizedActor(25f))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.getItemViews()[0].addAction(Actions.fadeIn(0.2f))
        }
        override fun getItemCount(): Int = count
    }

    private fun singleVisibleRecycler(adapter: RecyclerView.Adapter<*>): RecyclerView {
        val rv = RecyclerView()
        // Item taller than the viewport => strictly one item visible at a time (no boundary item),
        // so each scroll recycles the previous holder into the pool and reuses it for the next.
        rv.setSize(20f, 20f)
        rv.layoutManager = LinearLayoutManager().apply { bufferItemCount = 0 }
        rv.setAdapter(adapter)
        rv.layout()
        return rv
    }

    @Test
    fun `onBindViewHolder can attach a per-bind action`() {
        val rv = singleVisibleRecycler(FadeInAdapter(10))
        val holder = rv.findViewHolderForAdapterPosition(0)!!
        assertEquals(1, holder.getItemViews()[0].actions.size)
    }

    @Test
    fun `recycled view starts each rebind with actions cleared - no accumulation`() {
        val rv = singleVisibleRecycler(FadeInAdapter(10))
        // Scroll through several positions; each reuse must clear the previous fade before rebinding,
        // so the reused actor never accumulates more than the single fresh action.
        for (pos in 1 until 6) {
            rv.scrollToPosition(pos)
            rv.layout()
            val holder = rv.findViewHolderForAdapterPosition(pos)!!
            assertEquals("stale actions must not pile up on reuse", 1, holder.getItemViews()[0].actions.size)
        }
    }

    @Test
    fun `stale alpha from an interrupted fade is reset before rebinding`() {
        val rv = singleVisibleRecycler(RecyclerViewTestSupport.StringAdapter((0 until 10).map { "item$it" }, itemSize = 25f))
        val holder0 = rv.findViewHolderForAdapterPosition(0)!!
        // Simulate a fade that got interrupted mid-flight when the holder was recycled.
        holder0.getItemViews()[0].color.a = 0.3f

        rv.scrollToPosition(1)
        rv.layout()
        val holder1 = rv.findViewHolderForAdapterPosition(1)!!
        // Pool reuse: the same actor instance comes back, now with alpha reset to fully opaque.
        assertSame(holder0.getItemViews()[0], holder1.getItemViews()[0])
        assertEquals(1f, holder1.getItemViews()[0].color.a, 0.001f)
    }

    @Test
    fun `clearActions on rebind removes an action left over from a previous bind`() {
        val rv = singleVisibleRecycler(RecyclerViewTestSupport.StringAdapter((0 until 10).map { "item$it" }, itemSize = 25f))
        val holder0 = rv.findViewHolderForAdapterPosition(0)!!
        holder0.getItemViews()[0].addAction(Actions.forever(Actions.rotateBy(1f)))
        assertTrue(holder0.getItemViews()[0].actions.size > 0)

        rv.scrollToPosition(1)
        rv.layout()
        val holder1 = rv.findViewHolderForAdapterPosition(1)!!
        assertSame(holder0.getItemViews()[0], holder1.getItemViews()[0])
        // The StringAdapter adds no per-bind action, so after the clear the reused view has none.
        assertEquals(0, holder1.getItemViews()[0].actions.size)
    }
}
