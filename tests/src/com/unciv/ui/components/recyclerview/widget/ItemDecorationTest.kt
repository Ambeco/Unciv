package com.unciv.ui.components.recyclerview.widget

import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.Rectangle
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

/** Kotlin sees Mockito's `any()` as returning a platform (nullable-looking) type, but the
 * non-null-typed parameters it's passed to reject the `null` Mockito actually returns at
 * matcher-registration time. This wrapper suppresses the null-check that trips on that. */
private fun <T> anyNonNull(): T {
    org.mockito.ArgumentMatchers.any<T>()
    @Suppress("UNCHECKED_CAST")
    return null as T
}

@RunWith(GdxTestRunner::class)
class ItemDecorationTest {

    private fun decorationWithFixedInset(left: Float, top: Float, right: Float, bottom: Float) =
        object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rectangle, position: Int, parent: RecyclerView, state: RecyclerView.State) {
                outRect.set(left, top, right, bottom)
            }
        }

    @Test
    fun `multiple decorations accumulate insets`() {
        val rv = RecyclerView()
        rv.setSize(100f, 100f)
        rv.layoutManager = LinearLayoutManager()
        rv.setAdapter(RecyclerViewTestSupport.StringAdapter(listOf("a"), itemSize = 20f))
        rv.addItemDecoration(decorationWithFixedInset(1f, 2f, 3f, 4f))
        rv.addItemDecoration(decorationWithFixedInset(5f, 6f, 7f, 8f))

        val insets = rv.getItemDecorationInsetsForPosition(0)
        assertEquals(6f, insets.x, 0.001f)     // 1 + 5
        assertEquals(8f, insets.y, 0.001f)     // 2 + 6
        assertEquals(10f, insets.width, 0.001f) // 3 + 7
        assertEquals(12f, insets.height, 0.001f) // 4 + 8
    }

    @Test
    fun `decoration insets shrink and offset the item actor bounds`() {
        val rv = RecyclerView()
        rv.setSize(100f, 100f)
        rv.layoutManager = LinearLayoutManager()
        rv.setAdapter(RecyclerViewTestSupport.StringAdapter(listOf("a"), itemSize = 20f))
        rv.addItemDecoration(decorationWithFixedInset(10f, 2f, 0f, 0f))
        rv.layout()

        val itemView = rv.findViewHolderForAdapterPosition(0)!!.getItemViews()[0]
        assertEquals(10f, itemView.x, 0.001f)
        assertEquals(90f, itemView.width, 0.001f) // 100 - 10 left - 0 right
        assertEquals(18f, itemView.height, 0.001f) // 20 - 2 top - 0 bottom
    }

    @Test
    fun `onDraw and onDrawOver are invoked once per RecyclerView draw pass`() {
        val rv = RecyclerView()
        rv.setSize(50f, 50f)
        // Skip scene2d's affine-transform draw path (which needs a real Batch transform
        // matrix) - irrelevant to what this test is verifying (decoration hook dispatch).
        rv.isTransform = false
        rv.layoutManager = LinearLayoutManager()
        rv.setAdapter(RecyclerViewTestSupport.StringAdapter(listOf("a", "b"), itemSize = 10f))
        rv.layout()

        val decoration = mock(RecyclerView.ItemDecoration::class.java)
        rv.addItemDecoration(decoration)

        val batch = mock(Batch::class.java)
        rv.draw(batch, 1f)

        verify(decoration, times(1)).onDraw(anyNonNull(), anyNonNull(), anyNonNull())
    }
}
