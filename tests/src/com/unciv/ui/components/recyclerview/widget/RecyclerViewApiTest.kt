package com.unciv.ui.components.recyclerview.widget

import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Covers RecyclerView's public, Android-shaped API surface: setAdapter/setLayoutManager
 * swapping, scrollToPosition, invalidateItemDecorations, and empty-adapter edge cases. */
@RunWith(GdxTestRunner::class)
class RecyclerViewApiTest {

    private fun rv(width: Float = 100f, height: Float = 200f): RecyclerView {
        val rv = RecyclerView()
        rv.setSize(width, height)
        return rv
    }

    @Test
    fun `getAdapter and getLayoutManager reflect what was set`() {
        val view = rv()
        assertNull(view.getAdapter())
        assertNull(view.layoutManager)

        val adapter = RecyclerViewTestSupport.StringAdapter(listOf("a", "b"), itemSize = 20f)
        val lm = LinearLayoutManager()
        view.setAdapter(adapter)
        view.layoutManager = lm

        assertEquals(adapter, view.getAdapter())
        assertEquals(lm, view.layoutManager)
    }

    @Test
    fun `swapping adapters clears previously bound holders`() {
        val view = rv()
        view.layoutManager = LinearLayoutManager()
        val adapter1 = RecyclerViewTestSupport.StringAdapter((1..5).map { "item$it" }, itemSize = 20f)
        view.setAdapter(adapter1)
        view.layout()
        assertTrue(view.recycler.getPositions().isNotEmpty())

        val adapter2 = RecyclerViewTestSupport.StringAdapter(emptyList())
        view.setAdapter(adapter2)
        // Old adapter's items must be fully detached, not leaked into the new adapter's window.
        assertTrue(view.recycler.getPositions().isEmpty())
    }

    @Test
    fun `swapping layout managers relayouts using the new manager`() {
        val view = rv(width = 100f, height = 100f)
        val adapter = RecyclerViewTestSupport.StringAdapter((1..10).map { "item$it" }, itemSize = 10f)
        view.setAdapter(adapter)
        view.layoutManager = LinearLayoutManager()
        view.layout()
        val verticalPositions = view.recycler.getPositions()
        assertTrue(verticalPositions.isNotEmpty())

        view.layoutManager = GridLayoutManager(spanCount = 2)
        view.layout()
        val gridPositions = view.recycler.getPositions()
        assertTrue(gridPositions.isNotEmpty())
    }

    @Test
    fun `empty adapter produces no visible children`() {
        val view = rv()
        view.layoutManager = LinearLayoutManager()
        view.setAdapter(RecyclerViewTestSupport.StringAdapter(emptyList()))
        view.layout()
        assertTrue(view.recycler.getPositions().isEmpty())
        assertEquals(0, view.children.size)
    }

    @Test
    fun `no adapter or layout manager attached does not throw`() {
        val view = rv()
        // No adapter, no layout manager - layout() must be a no-op, not a crash.
        view.layout()
        assertTrue(view.recycler.getPositions().isEmpty())

        view.layoutManager = LinearLayoutManager()
        view.layout() // adapter still null
        assertTrue(view.recycler.getPositions().isEmpty())
    }

    @Test
    fun `scrollToPosition moves the visible window`() {
        val view = rv(width = 50f, height = 50f)
        val adapter = RecyclerViewTestSupport.StringAdapter((0 until 100).map { "item$it" }, itemSize = 10f)
        view.setAdapter(adapter)
        val lm = LinearLayoutManager().apply { bufferItemCount = 0 }
        view.layoutManager = lm
        view.layout()
        assertEquals(0, lm.findFirstVisibleItemPosition())

        view.scrollToPosition(50)
        view.layout()
        assertEquals(50, lm.findFirstVisibleItemPosition())
    }

    @Test
    fun `smoothScrollToPosition animates toward the target over several frames`() {
        val view = rv(width = 50f, height = 50f)
        val adapter = RecyclerViewTestSupport.StringAdapter((0 until 100).map { "item$it" }, itemSize = 10f)
        view.setAdapter(adapter)
        val lm = LinearLayoutManager().apply { bufferItemCount = 0 }
        view.layoutManager = lm
        view.layout()

        view.smoothScrollToPosition(30)
        // Not an immediate jump: the anchor is still at the start on the very next layout.
        view.layout()
        assertEquals(0, lm.findFirstVisibleItemPosition())

        // Pump act() frames; the anchor walks toward and settles exactly on the target.
        repeat(200) { view.act(1f / 60f); view.layout() }
        assertEquals(30, lm.findFirstVisibleItemPosition())
    }

    @Test
    fun `invalidateItemDecorations triggers a relayout without throwing`() {
        val view = rv()
        view.layoutManager = LinearLayoutManager()
        view.setAdapter(RecyclerViewTestSupport.StringAdapter(listOf("a", "b"), itemSize = 20f))
        view.layout()
        view.invalidateItemDecorations()
        view.layout() // should not throw, content should still be present
        assertTrue(view.recycler.getPositions().isNotEmpty())
    }

    @Test
    fun `addItemDecoration and removeItemDecoration toggle offsets`() {
        val view = rv(width = 100f, height = 100f)
        view.layoutManager = LinearLayoutManager()
        view.setAdapter(RecyclerViewTestSupport.StringAdapter(listOf("a", "b", "c"), itemSize = 20f))

        val decoration = object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: com.badlogic.gdx.math.Rectangle,
                position: Int,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                outRect.set(5f, 5f, 5f, 5f)
            }
        }

        view.layout()
        val holder = view.findViewHolderForAdapterPosition(0)!!
        val widthWithoutDecoration = holder.getItemViews()[0].width

        view.addItemDecoration(decoration)
        view.layout()
        val widthWithDecoration = view.findViewHolderForAdapterPosition(0)!!.getItemViews()[0].width
        assertTrue(widthWithDecoration < widthWithoutDecoration)

        view.removeItemDecoration(decoration)
        view.layout()
        val widthAfterRemoval = view.findViewHolderForAdapterPosition(0)!!.getItemViews()[0].width
        assertEquals(widthWithoutDecoration, widthAfterRemoval, 0.001f)
    }

    @Test
    fun `setHasFixedSize just stores the flag`() {
        val view = rv()
        assertFalse(view.hasFixedSize)
        view.setHasFixedSize(true)
        assertTrue(view.hasFixedSize)
    }

    @Test
    fun `getChildViewHolder resolves the holder for an attached item actor`() {
        val view = rv(width = 50f, height = 50f)
        view.setAdapter(RecyclerViewTestSupport.StringAdapter(listOf("a", "b"), itemSize = 10f))
        view.layoutManager = LinearLayoutManager()
        view.layout()
        val holder = view.findViewHolderForAdapterPosition(0)
        assertNotNull(holder)
        assertEquals(holder, view.getChildViewHolder(holder!!.getItemViews()[0]))
    }
}
