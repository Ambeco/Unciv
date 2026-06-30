package com.unciv.ui.components.recyclerview.widget

import com.badlogic.gdx.scenes.scene2d.ui.Widget
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Covers [LinearLayoutManager]'s "in theory" cross-axis scrolling for an item Actor wider
 *  (VERTICAL) or taller (HORIZONTAL) than the viewport - see the class doc's cross-axis note and
 *  [LinearLayoutManager.measureCross]. The common case (items no bigger than the viewport,
 *  stretched to fill it exactly as before this existed) is covered by [LinearLayoutManagerTest]. */
@RunWith(GdxTestRunner::class)
class LinearLayoutManagerCrossAxisScrollTest {

    /** Reports an independently settable pref width/height, unlike [RecyclerViewTestSupport.SizedActor]
     *  (same value for both) - needed here since the cross-axis size must differ from the main-axis one. */
    private class SizedWidget(var prefW: Float, var prefH: Float) : Widget() {
        override fun getPrefWidth(): Float = prefW
        override fun getPrefHeight(): Float = prefH
    }

    private class WideViewHolder(val widget: SizedWidget) : RecyclerView.ViewHolder() {
        override fun getItemViews(): List<Widget> = listOf(widget)
    }

    private class WideAdapter(
        private val itemCount: Int,
        private val mainSize: Float,
        private val crossSize: Float,
        private val vertical: Boolean
    ) : RecyclerView.Adapter<WideViewHolder>() {
        override fun onCreateViewHolder(parent: RecyclerView, viewType: Int) =
            WideViewHolder(SizedWidget(if (vertical) crossSize else mainSize, if (vertical) mainSize else crossSize))
        override fun onBindViewHolder(holder: WideViewHolder, position: Int) {}
        override fun getItemCount(): Int = itemCount
    }

    @Test
    fun `item no bigger than the viewport is not cross-scrollable`() {
        val rv = RecyclerView()
        rv.setSize(100f, 100f)
        val lm = LinearLayoutManager(RecyclerView.Orientation.VERTICAL).apply { bufferItemCount = 0 }
        rv.layoutManager = lm
        rv.setAdapter(WideAdapter(itemCount = 3, mainSize = 20f, crossSize = 60f, vertical = true))
        rv.layout()
        assertFalse(lm.canScrollHorizontally())
    }

    @Test
    fun `item wider than the viewport becomes horizontally scrollable on a vertical list`() {
        val rv = RecyclerView()
        rv.setSize(100f, 100f)
        val lm = LinearLayoutManager(RecyclerView.Orientation.VERTICAL).apply { bufferItemCount = 0 }
        rv.layoutManager = lm
        rv.setAdapter(WideAdapter(itemCount = 3, mainSize = 20f, crossSize = 150f, vertical = true))
        rv.layout()
        assertTrue(lm.canScrollHorizontally())

        val holder0 = rv.findViewHolderForAdapterPosition(0)!!.getItemViews()[0]
        assertEquals(150f, holder0.width, 0.001f) // stretched to its own pref width, not clipped to 100
        assertEquals(0f, holder0.x, 0.001f)

        rv.scrollBy(30f, 0f) // drag the cross axis
        rv.layout()
        val moved = rv.findViewHolderForAdapterPosition(0)!!.getItemViews()[0]
        assertEquals(-30f, moved.x, 0.001f)

        // Clamp: can't scroll past the item's own overflow (150 - 100 = 50).
        rv.scrollBy(1000f, 0f)
        rv.layout()
        val end = rv.findViewHolderForAdapterPosition(0)!!.getItemViews()[0]
        assertEquals(-50f, end.x, 0.001f)
    }

    @Test
    fun `item taller than the viewport becomes vertically scrollable on a horizontal list`() {
        val rv = RecyclerView()
        rv.setSize(100f, 100f)
        val lm = LinearLayoutManager(RecyclerView.Orientation.HORIZONTAL).apply { bufferItemCount = 0 }
        rv.layoutManager = lm
        rv.setAdapter(WideAdapter(itemCount = 3, mainSize = 20f, crossSize = 150f, vertical = false))
        rv.layout()
        assertTrue(lm.canScrollVertically())

        rv.scrollBy(0f, 40f)
        rv.layout()
        val moved = rv.findViewHolderForAdapterPosition(0)!!.getItemViews()[0]
        assertEquals(-40f, moved.y, 0.001f)
    }
}
