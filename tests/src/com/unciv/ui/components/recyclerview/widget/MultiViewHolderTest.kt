package com.unciv.ui.components.recyclerview.widget

import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.ui.Widget
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the two capabilities [RecyclerView.ViewHolder]/[RecyclerView.Adapter] gained beyond
 * Android's one-View-per-holder model: binding more than one Actor via
 * [RecyclerView.ViewHolder.getItemViews], and controlling attach order across holders via
 * [RecyclerView.Adapter.getViewComparator].
 */
@RunWith(GdxTestRunner::class)
class MultiViewHolderTest {

    /** Binds two Actors per item - e.g. an icon layer and a label layer that should move/recycle
     *  together as one logical item, the way [RecyclerView.ViewHolder] should require this. [icon] is
     *  a real-sized [RecyclerViewTestSupport.SizedActor] (not a bare zero-pref [Widget]) since it's
     *  first in [getItemViews] and so is what [LinearLayoutManager.measure] reads - there's no
     *  `itemSize` fallback to lean on instead. */
    private class TwoActorHolder : RecyclerView.ViewHolder() {
        val icon = RecyclerViewTestSupport.SizedActor(50f)
        val label = Widget()
        override fun getItemViews(): List<Widget> = listOf(icon, label)
    }

    private class TwoActorAdapter(val count: Int) : RecyclerView.Adapter<TwoActorHolder>() {
        override fun onCreateViewHolder(parent: RecyclerView, viewType: Int) = TwoActorHolder()
        override fun onBindViewHolder(holder: TwoActorHolder, position: Int) {}
        override fun getItemCount(): Int = count
    }

    private fun rv(width: Float = 100f, height: Float = 200f): RecyclerView {
        val view = RecyclerView()
        view.setSize(width, height)
        return view
    }

    @Test
    fun `a holder binding two actors gets both attached and positioned identically`() {
        val view = rv()
        view.setAdapter(TwoActorAdapter(3))
        view.layoutManager = LinearLayoutManager()
        view.layout()

        val holder = view.findViewHolderForAdapterPosition(0) as TwoActorHolder
        assertTrue(holder.icon.parent === view)
        assertTrue(holder.label.parent === view)
        // LinearLayoutManager.placeItem treats every getItemViews() entry as one overlapping "slot".
        assertEquals(holder.icon.x, holder.label.x, 0.001f)
        assertEquals(holder.icon.y, holder.label.y, 0.001f)
        assertEquals(holder.icon.width, holder.label.width, 0.001f)
        assertEquals(holder.icon.height, holder.label.height, 0.001f)
    }

    @Test
    fun `getChildViewHolder resolves either of a multi-view holder's actors`() {
        val view = rv()
        view.setAdapter(TwoActorAdapter(1))
        view.layoutManager = LinearLayoutManager()
        view.layout()

        val holder = view.findViewHolderForAdapterPosition(0) as TwoActorHolder
        assertSame(holder, view.getChildViewHolder(holder.icon))
        assertSame(holder, view.getChildViewHolder(holder.label))
    }

    @Test
    fun `scrolling a multi-view item out of the window keeps both its actors together as one unit`() {
        val view = rv(height = 60f) // only room for ~2 items at itemSize 50
        view.setAdapter(TwoActorAdapter(10))
        view.layoutManager = LinearLayoutManager().apply { bufferItemCount = 0 }
        view.layout()

        assertTrue(view.findViewHolderForAdapterPosition(0) != null)
        val attachedCountBefore = view.children.size

        view.scrollToPosition(9)
        view.layout()

        // Position 0 is no longer represented at all - its holder was recycled (rebound to
        // whichever new position needed a same-type holder, not necessarily detached-then-new),
        // which is correct recycling behavior; what this test actually guards is that recycling
        // never leaves a multi-view holder's actors split (one attached, one orphaned) or leaks a
        // growing child count as items scroll through.
        assertEquals(null, view.findViewHolderForAdapterPosition(0))
        assertTrue(view.findViewHolderForAdapterPosition(9) != null)
        assertEquals(attachedCountBefore, view.children.size)
        // Every currently-active holder still has both its actors attached (not split).
        val lm = view.layoutManager as LinearLayoutManager
        for (position in lm.findFirstVisibleItemPosition()..lm.findLastVisibleItemPosition()) {
            val holder = view.findViewHolderForAdapterPosition(position) as TwoActorHolder
            assertTrue(holder.icon.parent === view)
            assertTrue(holder.label.parent === view)
        }
    }

    // region getViewComparator

    /** [sortKey] is deliberately independent of adapter position, so a test can request holders in
     *  position order (as any [RecyclerView.LayoutManager] would) while attach order and sort order
     *  actually diverge. */
    private class SortedHolder : RecyclerView.ViewHolder() {
        var sortKey = 0
        val actor = Widget()
        override fun getItemViews(): List<Widget> = listOf(actor)
    }

    /** Position `p`'s sortKey is `count - 1 - p`: exactly reversed, so naturally requesting holders
     *  in ascending position order attaches them in descending sortKey order - the adversarial case
     *  sorted attach needs to correct. */
    private class ReverseSortedAdapter(val count: Int) : RecyclerView.Adapter<SortedHolder>() {
        // getViewComparator compares bare Actors, not ViewHolders (see its KDoc) - this adapter's own
        // bookkeeping (built once per holder, in onCreateViewHolder) is what resolves an Actor back to
        // the SortedHolder that owns it, the same pattern a real multi-view adapter (HexTileAdapter)
        // uses.
        private val holderByActor = HashMap<Actor, SortedHolder>()
        override fun onCreateViewHolder(parent: RecyclerView, viewType: Int) = SortedHolder().also { holderByActor[it.actor] = it }
        override fun onBindViewHolder(holder: SortedHolder, position: Int) {
            holder.sortKey = count - 1 - position
        }
        override fun getItemCount(): Int = count
        override fun getViewComparator(): Comparator<Actor> =
            Comparator { a, b -> holderByActor.getValue(a).sortKey.compareTo(holderByActor.getValue(b).sortKey) }
    }

    @Test
    fun `a non-null getViewComparator keeps attached views in sorted order regardless of attach order`() {
        val view = rv()
        view.setAdapter(ReverseSortedAdapter(3))
        val recycler = view.recycler

        // Request in ascending position order (0, 1, 2) - i.e. descending sortKey (2, 1, 0) - the
        // same traversal a LayoutManager uses. Without sorted attach, view.children would end up in
        // that exact append order (sortKey 2, 1, 0); getViewComparator should instead keep them
        // sorted ascending by sortKey as each new one is inserted.
        val holder0 = recycler.getHolderForPosition(0) as SortedHolder // sortKey 2
        val holder1 = recycler.getHolderForPosition(1) as SortedHolder // sortKey 1
        val holder2 = recycler.getHolderForPosition(2) as SortedHolder // sortKey 0

        assertEquals(3, view.children.size)
        val actorsBySortKeyAscending = listOf(holder2.actor, holder1.actor, holder0.actor) // 0, 1, 2
        for (i in actorsBySortKeyAscending.indices) {
            assertSame("child $i should be the sortKey=$i actor", actorsBySortKeyAscending[i], view.children[i])
        }
    }

    @Test
    fun `null getViewComparator (the default) just appends, matching plain attach order`() {
        val view = rv()
        view.setAdapter(TwoActorAdapter(3))
        val recycler = view.recycler

        val holder0 = recycler.getHolderForPosition(0) as TwoActorHolder
        val holder1 = recycler.getHolderForPosition(1) as TwoActorHolder

        // Appended in request order: holder0's two views, then holder1's two views.
        assertEquals(listOf(holder0.icon, holder0.label, holder1.icon, holder1.label), view.children.toList())
    }

    // endregion
}
